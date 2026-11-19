package com.junesoon.mim.service;

import com.junesoon.mim.components.ApplicatonStore;
import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.components.Request;
import com.junesoon.mim.crypto.Hkdf;
import com.junesoon.mim.crypto.IdentityException;
import com.junesoon.mim.crypto.OwnDeviceTrust;
import com.junesoon.mim.crypto.SessionStore;
import com.junesoon.mim.crypto.SyncEnvelope;
import com.junesoon.mim.crypto.SyncPayload;
import com.junesoon.mim.crypto.TrustStore;
import com.junesoon.mim.feign.ChatFeign;
import com.junesoon.mim.feign.DeviceFeign;
import com.junesoon.mim.feign.MessageFeign;
import com.junesoon.mim.model.BaseNotify;
import com.junesoon.mim.model.ChatContact;
import com.junesoon.mim.model.ChatUser;
import com.junesoon.mim.model.CipherMessage;
import com.junesoon.mim.model.PreKeyBundle;
import com.junesoon.mim.service.MessageService.DecryptedMessage;
import com.junesoon.mim.websocket.WSClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 把本机已经解开的历史，重新加密给自己的另一台设备。
 *
 * <p>这是攻击面最大的一处：转发的是<b>明文</b>。因此绝不能相信服务端给出的
 * 设备列表——必须只发给本机用户显式批准过的设备（{@link OwnDeviceTrust}）。
 * 第一次向一台设备转发时，界面会展示它的身份指纹，由用户确认。
 *
 * <p>每个会话只带最近 50 条。本机没有原文的消息补不了，这是端到端加密的边界。
 */
@Slf4j
public final class HistorySync {

    private static final int PAGE = 50;
    private static final int BATCH = 50;

    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    /**
     * 用户拒绝过的「设备 + 指纹」，本次会话内不再重复弹窗。
     */
    private static final Set<String> DENIED = ConcurrentHashMap.newKeySet();

    private static volatile WSClient bound;

    /**
     * 需要用户确认一台新设备时回调。必须在 JavaFX 线程弹窗，
     * 并由实现方阻塞等待用户选择。未设置时默认拒绝。
     */
    public interface DeviceApprover {
        boolean approve(String deviceId, String fingerprint);
    }

    private static volatile DeviceApprover approver;

    private HistorySync() {
    }

    public static void setApprover(DeviceApprover value) {
        approver = value;
    }

    /**
     * 在当前这条连接上监听「请把历史给我」的通知。换连接后要再调用一次。
     */
    public static void arm() {
        WSClient client = WSClient.getInstance();
        if (bound == client) {
            return;
        }
        client.addListener(HistorySync::onNotify);
        bound = client;
    }

    /**
     * 登录后：请其他设备补给我，同时把我有的原文补给我已经信任的设备。
     */
    public static void requestInBackground() {
        Thread worker = new Thread(() -> {
            request();
            forwardToSiblings();
        }, "history-sync");
        worker.setDaemon(true);
        worker.start();
    }

    private static void onNotify(BaseNotify<?> notify) {
        if (notify == null || notify.getType() == null
            || notify.getType() != WSClient.NOTIFY_HISTORY_SYNC) {
            return;
        }
        if (!(notify.getData() instanceof Map)) {
            return;
        }
        Object deviceId = ((Map<?, ?>) notify.getData()).get("deviceId");
        if (deviceId == null) {
            return;
        }
        String target = deviceId.toString();
        String self = KeyManager.getInstance().deviceId();
        if (target.equals(self)) {
            return;
        }
        // 对方明确请求了历史：允许向用户申请一次设备授权
        Thread worker = new Thread(() -> forward(target, true), "history-sync");
        worker.setDaemon(true);
        worker.start();
    }

    private static void request() {
        try {
            ObjectRestResponse<Boolean> response = Request.connector(MessageFeign.class).requestSync();
            if (response == null || response.getStatus() != 200) {
                String message = response == null ? "服务端无响应" : response.getMessage();
                log.warn("请求其他设备转发历史失败：{}", message);
            }
        } catch (Exception e) {
            log.warn("请求其他设备转发历史失败：{}", e.getMessage());
        }
    }

    /**
     * 登录时主动补发给其它设备。这里<b>只发给自己已经信任的设备</b>，
     * 不弹窗：新设备会自己请求历史，届时再走交互式确认。
     */
    private static void forwardToSiblings() {
        String self = KeyManager.getInstance().deviceId();
        OwnDeviceTrust trust = KeyManager.getInstance().getOwnDeviceTrust();
        for (String deviceId : siblingDevices()) {
            if (deviceId == null || deviceId.equals(self)) {
                continue;
            }
            if (trust.getPinned(deviceId) == null) {
                log.info("跳过未授权的本账号设备 {}，等待其主动请求历史", deviceId);
                continue;
            }
            forward(deviceId, false);
        }
    }

    private static void forward(String targetDeviceId, boolean interactive) {
        Object lock = LOCKS.computeIfAbsent(targetDeviceId, key -> new Object());
        synchronized (lock) {
            OwnDeviceTrust trust = KeyManager.getInstance().getOwnDeviceTrust();
            String password;
            try {
                password = currentPassword();
            } catch (IdentityException e) {
                log.warn("会话已锁定，跳过历史转发：{}", e.getMessage());
                return;
            }

            PreKeyBundle bundle;
            try {
                bundle = PreKeyManager.getInstance().fetchOwnDevice(targetDeviceId);
            } catch (IdentityException e) {
                log.warn("获取设备 {} 的预密钥失败，跳过历史转发：{}", targetDeviceId,
                    e.getMessage());
                return;
            }

            boolean trusted = trust.isTrusted(targetDeviceId, bundle.getIdentityPublicKey());
            if (!trusted) {
                if (!interactive) {
                    log.info("设备 {} 未获本机授权，忽略非交互式历史同步", targetDeviceId);
                    return;
                }
                String fingerprint;
                try {
                    fingerprint = fingerprintOf(bundle.getIdentityPublicKey());
                } catch (IdentityException e) {
                    log.warn("设备 {} 的身份公钥格式错误，拒绝转发：{}", targetDeviceId,
                        e.getMessage());
                    return;
                }
                String denyKey = targetDeviceId + "|" + fingerprint;
                if (DENIED.contains(denyKey)) {
                    return;
                }
                DeviceApprover current = approver;
                if (current == null) {
                    log.warn("设备 {} 请求历史，但没有可用的确认界面，已拒绝", targetDeviceId);
                    DENIED.add(denyKey);
                    return;
                }
                if (!current.approve(targetDeviceId, fingerprint)) {
                    log.warn("用户拒绝向设备 {} 转发历史", targetDeviceId);
                    DENIED.add(denyKey);
                    return;
                }
                try {
                    trust.trustDevice(targetDeviceId, bundle.getIdentityPublicKey(), password);
                    // 旧版本可能在未授权时建立过会话，必须重建
                    MessageService.getInstance().invalidateSyncSession(targetDeviceId);
                } catch (IdentityException e) {
                    log.warn("保存设备授权失败：{}", e.getMessage());
                    return;
                }
            }

            // 无论新老授权，都按已固定公钥重新验一遍预密钥签名
            try {
                PreKeyManager.getInstance().verifyOwnDeviceBundle(bundle);
            } catch (IdentityException e) {
                log.warn("设备 {} 预密钥校验失败，拒绝转发：{}", targetDeviceId, e.getMessage());
                return;
            }

            Set<String> done = loadDone(targetDeviceId);
            List<CipherMessage> batch = new ArrayList<>();
            int forwarded = 0;
            for (String peer : peers()) {
                List<DecryptedMessage> page = MessageService.getInstance().history(peer, null, PAGE);
                for (DecryptedMessage message : page) {
                    String clientMsgId = message.getClientMsgId();
                    if (clientMsgId == null || done.contains(clientMsgId)) {
                        continue;
                    }
                    CipherMessage replica = toReplica(targetDeviceId, peer, message, bundle);
                    if (replica == null) {
                        continue;
                    }
                    batch.add(replica);
                    if (batch.size() >= BATCH) {
                        forwarded += flush(targetDeviceId, batch, done);
                    }
                }
            }
            forwarded += flush(targetDeviceId, batch, done);
            if (forwarded > 0) {
                log.info("已向设备 {} 转发 {} 条历史", targetDeviceId, forwarded);
            }
        }
    }

    private static CipherMessage toReplica(String targetDeviceId, String conversationPeer,
        DecryptedMessage message, PreKeyBundle bundle) {
        // 被屏蔽的会话不转发给其它设备：屏蔽状态是用户明确的意愿。
        if (MessageService.getInstance().isBlocked(conversationPeer)) {
            return null;
        }
        if (message.isRecalled()) {
            CipherMessage tombstone = new CipherMessage();
            tombstone.setClientMsgId(message.getClientMsgId());
            tombstone.setTargetDeviceId(targetDeviceId);
            return tombstone;
        }
        if (message.isDecryptFailed()) {
            return null;
        }
        String text = message.getPlaintext();
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            // 撤回凭证随加密副本一起走，服务端只看到密文。
            // 目标设备没声明能力时保持纯文本，旧客户端不会看到信封。
            String recallToken = message.isMine()
                ? MessageService.getInstance().recallTokenFor(conversationPeer,
                    message.getClientMsgId())
                : null;
            String payload = Boolean.TRUE.equals(bundle.getSupportsRecallToken())
                ? SyncEnvelope.pack(recallToken, text) : text;
            CipherMessage replica = MessageService.getInstance().wrapForOwnDevice(
                targetDeviceId, payload, message.getClientMsgId(), message.getTimestamp(), bundle);
            java.util.List<String> tags =
                MessageService.getInstance().conversationTags(conversationPeer);
            if (!tags.isEmpty()) {
                // 同步副本属于同一会话：带上标签，服务端才能按标签找到原消息。
                replica.setConversationTag(tags.get(0));
            }

            // 用本机身份私钥签住「原消息归属 + 内容」，接收端验签后以这些字段为准。
            // 否则被攻破的服务端可以把任意历史内容挂到任意联系人名下。
            com.junesoon.mim.crypto.IdentityStore.Identity identity =
                KeyManager.getInstance().getIdentity();
            CipherMessage raw = message.getRaw();
            if (identity == null || raw == null) {
                return null;
            }
            String originalFrom;
            String originalTo;
            String originalDevice;
            if (Boolean.TRUE.equals(raw.getSealed())) {
                // sealed 消息的 from/to 中有一侧是 HMAC 盲引用，不能用它签名；
                // 用当前会话对端 + 本机账号还原原始归属。发送设备已被隐藏，
                // 只能使用默认设备标识。
                String self = ApplicatonStore.getUsername();
                originalFrom = message.isMine() ? self : conversationPeer;
                originalTo = message.isMine() ? conversationPeer : self;
                originalDevice = SessionStore.DEFAULT_DEVICE_ID;
            } else {
                originalFrom = raw.getFromUsername();
                originalTo = raw.getToUsername();
                originalDevice = raw.getFromDeviceId() == null
                    ? SessionStore.DEFAULT_DEVICE_ID : raw.getFromDeviceId();
            }
            long clientTime = message.getTimestamp();
            replica.setSyncOriginalFrom(originalFrom);
            replica.setSyncOriginalTo(originalTo);
            replica.setSyncOriginalDevice(originalDevice);
            String canonical = SyncPayload.canonical(originalFrom, originalTo, originalDevice,
                message.getClientMsgId(), clientTime, targetDeviceId, payload);
            replica.setSyncSignature(SyncPayload.sign(identity.getPrivateKey(), canonical));
            return replica;
        } catch (IdentityException e) {
            log.warn("转发消息 {} 失败：{}", message.getClientMsgId(), e.getMessage());
            return null;
        }
    }

    private static int flush(String targetDeviceId, List<CipherMessage> batch, Set<String> done) {
        if (batch.isEmpty()) {
            return 0;
        }
        List<CipherMessage> sending = new ArrayList<>(batch);
        batch.clear();
        try {
            ObjectRestResponse<List<CipherMessage>> response =
                Request.connector(MessageFeign.class).sync(sending);
            if (response == null || response.getStatus() != 200 || response.getData() == null) {
                String message = response == null ? "服务端无响应" : response.getMessage();
                log.warn("上传历史副本失败：{}", message);
                return 0;
            }
            int accepted = 0;
            for (CipherMessage item : response.getData()) {
                if (item != null && item.getClientMsgId() != null) {
                    done.add(item.getClientMsgId());
                    accepted++;
                }
            }
            saveDone(targetDeviceId, done);
            return accepted;
        } catch (Exception e) {
            log.warn("上传历史副本失败：{}", e.getMessage());
            return 0;
        }
    }

    private static List<String> peers() {
        List<String> peers = new ArrayList<>();
        try {
            List<ChatContact> groups = Request.connector(ChatFeign.class).getContacts();
            if (groups == null) {
                return peers;
            }
            for (ChatContact group : groups) {
                if (group.getUsers() == null) {
                    continue;
                }
                for (ChatUser user : group.getUsers()) {
                    if (user.getUsername() != null && !user.getUsername().isEmpty()) {
                        peers.add(user.getUsername());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取联系人失败，跳过历史转发：{}", e.getMessage());
        }
        return peers;
    }

    private static List<String> siblingDevices() {
        List<String> devices = new ArrayList<>();
        try {
            ObjectRestResponse<Map<String, Object>> response =
                Request.connector(DeviceFeign.class).list();
            if (response == null || response.getData() == null) {
                return devices;
            }
            Object raw = response.getData().get("devices");
            if (!(raw instanceof List)) {
                return devices;
            }
            for (Object item : (List<?>) raw) {
                if (item instanceof Map) {
                    Object id = ((Map<?, ?>) item).get("deviceId");
                    if (id != null) {
                        devices.add(id.toString());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取设备列表失败，跳过历史转发：{}", e.getMessage());
        }
        return devices;
    }

    private static String currentPassword() throws IdentityException {
        char[] password = ApplicatonStore.getSessionPassword();
        if (password == null || password.length == 0) {
            throw new IdentityException("会话已锁定，请重新登录");
        }
        return new String(password);
    }

    private static String fingerprintOf(String identityPublicKey) throws IdentityException {
        try {
            return TrustStore.shortFingerprint(
                Hkdf.sha256Hex(java.util.Base64.getDecoder().decode(identityPublicKey)));
        } catch (IllegalArgumentException e) {
            throw new IdentityException("设备身份公钥格式错误");
        }
    }

    private static Set<String> loadDone(String targetDeviceId) {
        Path file = stamp(targetDeviceId);
        if (!Files.isRegularFile(file)) {
            return new LinkedHashSet<>();
        }
        try {
            return new LinkedHashSet<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("读取已转发记录失败：{}", e.getMessage());
            return new LinkedHashSet<>();
        }
    }

    private static void saveDone(String targetDeviceId, Set<String> done) {
        Path file = stamp(targetDeviceId);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, new HashSet<>(done), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("保存已转发记录失败：{}", e.getMessage());
        }
    }

    private static Path stamp(String targetDeviceId) {
        String user = ApplicatonStore.getUsername() == null ? "anon" : ApplicatonStore.getUsername();
        String self = KeyManager.getInstance().deviceId();
        String name = safe(user) + "__" + safe(self) + "__" + safe(targetDeviceId) + ".ids";
        return Paths.get(System.getProperty("user.home"), ".mim", "sync", name);
    }

    private static String safe(String value) {
        if (value == null) {
            return "none";
        }
        // 旧实现只保留 ASCII，中文用户名会碰撞。这里用哈希，既避免碰撞又没有非法字符。
        return Hkdf.sha256Hex(value.getBytes(StandardCharsets.UTF_8)).toLowerCase();
    }
}
