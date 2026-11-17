package com.junesoon.mim.service;

import com.junesoon.mim.components.ApplicatonStore;
import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.components.Request;
import com.junesoon.mim.crypto.Hkdf;
import com.junesoon.mim.crypto.IdentityException;
import com.junesoon.mim.crypto.IdentityStore;
import com.junesoon.mim.crypto.OwnDeviceTrust;
import com.junesoon.mim.crypto.PeerCipher;
import com.junesoon.mim.crypto.PlaintextStore;
import com.junesoon.mim.crypto.PreKeyStore;
import com.junesoon.mim.crypto.RatchetService;
import com.junesoon.mim.crypto.SessionState;
import com.junesoon.mim.crypto.SessionStore;
import com.junesoon.mim.crypto.SyncPayload;
import com.junesoon.mim.crypto.TrustChangedException;
import com.junesoon.mim.crypto.TrustStore;
import com.junesoon.mim.feign.MessageFeign;
import com.junesoon.mim.model.CipherMessage;
import com.junesoon.mim.model.PreKeyBundle;
import com.junesoon.mim.model.ReadRequest;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 客户端消息收发（双棘轮 + 多设备）。
 *
 * <p>明文只存在于本类内部：发送时在此加密，接收时在此解密。
 * 上层 UI、网络层、磁盘拿到的永远都是密文。
 *
 * <p>多设备的关键点：
 * <ul>
 *   <li>会话键是 <b>(对端用户名, 对端设备)</b>，不是只有用户名。
 *       同一个人在不同设备上是两条独立的棘轮链；</li>
 *   <li>发送时向服务端领取对方<b>所有设备</b>的预密钥包，
 *       为每个设备各加密一份密文，一次性提交；</li>
 *   <li>接收时按「消息来自哪个设备」找回对应会话。</li>
 * </ul>
 *
 * <p>棘轮状态必须<b>先持久化成功再发送</b>。若加密后没落盘就崩溃，
 * 恢复时链密钥停留在旧位置，会导致后续消息与对端永久不同步。
 */
@Slf4j
public class MessageService {

    private static final MessageService INSTANCE = new MessageService();

    public static MessageService getInstance() {
        return INSTANCE;
    }

    private final KeyManager keyManager = KeyManager.getInstance();
    private final PreKeyManager preKeyManager = PreKeyManager.getInstance();
    private final RatchetService ratchet = new RatchetService();
    private final SessionStore sessionStore = new SessionStore();
    private final PlaintextStore plaintextStore = new PlaintextStore();

    /**
     * 会话缓存。key 为 {@code peer@deviceId}。每个设备槽里可以同时有我方发起链和对方发起链。
     */
    private final ConcurrentHashMap<String, PeerCipher> sessions = new ConcurrentHashMap<>();

    /**
     * 发送和历史解密会碰同一条棘轮，必须串行，否则历史解到一半被发送推进，后面的消息就对不上。
     */
    private final Object cryptoLock = new Object();

    /**
     * 首次收到某台本账号设备的同步消息时，需要用户确认其身份。
     */
    public interface SyncApprover {
        boolean approve(String deviceId, String fingerprint);
    }

    private static volatile SyncApprover syncApprover;

    /**
     * 用户拒绝过的「同步设备 + 指纹」，本次会话不再重复弹窗。
     */
    private static final Set<String> SYNC_DENIED = ConcurrentHashMap.newKeySet();

    public static void setSyncApprover(SyncApprover approver) {
        syncApprover = approver;
    }

    private static String sessionKey(String peer, String deviceId) {
        return peer + "@" + deviceId;
    }

    private static String normalizeDevice(String deviceId) {
        return deviceId == null || deviceId.isEmpty()
            ? SessionStore.DEFAULT_DEVICE_ID : deviceId;
    }

    /**
     * 明文缓存键。必须带上发送者与发送设备，避免不同发送者复用 clientMsgId 时串内容。
     */
    private static String cacheKey(String fromUsername, String fromDeviceId, String clientMsgId) {
        return (fromUsername == null ? "" : fromUsername) + "|"
            + normalizeDevice(fromDeviceId) + "|"
            + (clientMsgId == null ? "" : clientMsgId);
    }

    /**
     * 设备间同步会话的对端名。不能用账号名，否则会和「跟自己聊天」抢同一个会话槽。
     */
    private static String syncSlot(String deviceId) {
        return "sync-" + deviceId;
    }

    /**
     * 解密后的消息视图。
     */
    public static final class DecryptedMessage {
        private final CipherMessage raw;
        private final String plaintext;
        private final boolean decryptFailed;

        DecryptedMessage(CipherMessage raw, String plaintext, boolean decryptFailed) {
            this.raw = raw;
            this.plaintext = plaintext;
            this.decryptFailed = decryptFailed;
        }

        public CipherMessage getRaw() {
            return raw;
        }

        public String getPlaintext() {
            return plaintext;
        }

        public boolean isDecryptFailed() {
            return decryptFailed;
        }

        public boolean isRecalled() {
            return Boolean.TRUE.equals(raw.getRecalled());
        }

        public boolean isRead() {
            return Boolean.TRUE.equals(raw.getReadFlag());
        }

        public boolean isMine() {
            return raw.getFromUsername() != null
                && raw.getFromUsername().equals(ApplicatonStore.getUsername());
        }

        public long getTimestamp() {
            return raw.getClientTime() == null ? 0L : raw.getClientTime();
        }

        /**
         * 撤回操作用的稳定标识：多设备副本共用同一个 clientMsgId。
         */
        public String getClientMsgId() {
            return raw.getClientMsgId();
        }

        /**
         * UI 展示用文本。撤回消息统一显示占位，不暴露原文。
         */
        public String displayText() {
            if (isRecalled()) {
                return isMine() ? "你撤回了一条消息" : "对方撤回了一条消息";
            }
            return plaintext;
        }
    }

    // ==================== 会话管理 ====================

    public void loadSessions(String selfUsername, String password) {
        synchronized (cryptoLock) {
            sessions.clear();
            plaintextStore.load(selfUsername, password);
            java.util.List<String> entries = sessionStore.listPeers(selfUsername);
            log.info("准备加载 {} 个会话文件：{}", entries.size(), entries);
            for (String entry : entries) {
                String peer = entry;
                String device = SessionStore.DEFAULT_DEVICE_ID;
                int at = entry.lastIndexOf('@');
                if (at > 0) {
                    peer = entry.substring(0, at);
                    device = entry.substring(at + 1);
                }
                try {
                    SessionState state = sessionStore.load(selfUsername, peer, device, password);
                    if (state == null) {
                        log.warn("会话文件存在但加载返回 null：{}", entry);
                        continue;
                    }
                    if (!device.equals(state.getDeviceId())) {
                        log.warn("会话 {} 的设备标识不一致（文件={} 状态={}），已按文件纠正",
                            peer, device, state.getDeviceId());
                        state.setDeviceId(device);
                    }
                    PeerCipher slot = slot(state.getPeer(), state.getDeviceId());
                    if (state.isInitiator()) {
                        slot.setOutbound(state);
                    } else {
                        slot.setInbound(state);
                    }
                    SessionState inbound = sessionStore.loadInbound(selfUsername, peer, device, password);
                    if (inbound != null) {
                        inbound.setDeviceId(device);
                        slot.setInbound(inbound);
                    }
                    log.info("已加载会话 {}（设备 {}）", peer, device);
                } catch (IdentityException e) {
                    log.warn("加载与 {} 设备 {} 的会话失败：{}", peer, device, e.getMessage());
                }
            }
            log.info("已加载 {} 个本地会话，键集合={}", sessions.size(), sessions.keySet());
        }
    }

    public void reset() {
        synchronized (cryptoLock) {
            sessions.clear();
            plaintextStore.clear();
        }
    }

    /**
     * 是否与「对方某个设备」已建立会话。
     */
    public boolean hasSession(String peer) {
        for (Map.Entry<String, PeerCipher> entry : sessions.entrySet()) {
            if (!entry.getKey().startsWith(peer + "@")) {
                continue;
            }
            if (entry.getValue().getOutbound() != null || entry.getValue().getInbound() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 为对方的一个设备发起新会话。
     */
    private SessionState createSession(PreKeyBundle bundle) throws IdentityException {
        IdentityStore.Identity identity = requireIdentity();
        String password = currentPassword();

        // 顺序必须是「先验签，再信任」：签名不通过的包绝不能进入信任库，
        // 否则攻击者可以用一个坏包把固定值污染成自己的公钥。
        if (!com.junesoon.mim.crypto.DhKeyUtil.verifySignedPreKey(bundle.getIdentityPublicKey(),
            bundle.getSignedPreKeyId(), bundle.getSignedPreKeyPublic(),
            bundle.getSignedPreKeySignature())) {
            throw new IdentityException("对方预密钥签名验证失败，可能遭到中间人攻击，已中止");
        }
        TrustStore.VerificationResult trust = keyManager.getTrustStore()
            .verify(bundle.getUsername(), bundle.getDeviceId(), bundle.getIdentityPublicKey(),
                password);
        if (!trust.isSendable()) {
            // 并发场景下信任库可能刚被另一条线程更新；抛专用异常让界面还能走核对流程
            java.util.List<TrustChangedException.Issue> issues = new java.util.ArrayList<>(1);
            issues.add(new TrustChangedException.Issue(trust.getDeviceId(),
                trust.getStatus() == TrustStore.TrustStatus.NEW_DEVICE
                    ? TrustChangedException.Reason.NEW_DEVICE
                    : TrustChangedException.Reason.KEY_CHANGED,
                trust.getPreviousFingerprint(), trust.getCurrentFingerprint(),
                trust.getPublicKey()));
            throw new TrustChangedException(bundle.getUsername(), issues, trust.describe());
        }

        RatchetService.InitiatorResult result = ratchet.initiatorSession(bundle.getUsername(),
            identity.getPrivateKey(), identity.getPublicKeyBase64(), bundle.getIdentityPublicKey(),
            bundle.getSignedPreKeyPublic(), bundle.getOneTimePreKeyPublic());

        // 把握手参数写进会话状态并持久化：只放内存的话，重启后新发的消息
        // 不再携带参数，对端若还没建会话就永远解不开
        SessionState state = result.getState();
        // 必须显式设置设备标识：initiatorSession 不知道对方的设备 ID，
        // 若留默认值 "default"，则内存中的会话键与文件名的设备段不一致，
        // 重新加载后 sessions.get() 永远命中不了，每条消息都会新建会话（序号恒为 0）。
        state.setDeviceId(bundle.getDeviceId());
        state.setInitiator(true);
        state.setHandshakeSenderIdentityKey(result.getSenderIdentityKey());
        state.setHandshakeSenderEphemeralKey(result.getSenderEphemeralKey());
        state.setHandshakeUsedOneTimePreKey(result.isUsedOneTimePreKey());
        state.setHandshakeOneTimePreKeyId(bundle.getOneTimePreKeyId());
        state.setHandshakeRecipientSignedPreKeyId(bundle.getSignedPreKeyId());
        // 诊断：确认握手参数确实写入了状态（为空则对端永远建不出会话）
        log.info("createSession 完成：peer={} device={} hasHandshake={} idKeyLen={} ephKeyLen={}",
            bundle.getUsername(), bundle.getDeviceId(), state.hasPendingHandshake(),
            result.getSenderIdentityKey() == null ? -1 : result.getSenderIdentityKey().length(),
            result.getSenderEphemeralKey() == null ? -1 : result.getSenderEphemeralKey().length());
        return state;
    }

    private PeerCipher slot(String peer, String deviceId) {
        return sessions.computeIfAbsent(sessionKey(peer, deviceId), key -> new PeerCipher(ratchet));
    }

    /**
     * 我方发起链写主文件；两条链同时存在时，应答链写独立文件，避免互相覆盖。
     */
    private void persistSlot(String peer, String deviceId, PeerCipher slot) throws IdentityException {
        String self = requireIdentity().getUsername();
        String password = currentPassword();
        SessionState outbound = slot.getOutbound();
        SessionState inbound = slot.getInbound();
        if (outbound != null) {
            sessionStore.save(self, peer, deviceId, outbound, password);
            if (inbound != null) {
                sessionStore.saveInbound(self, peer, deviceId, inbound, password);
            } else {
                sessionStore.deleteInbound(self, peer, deviceId);
            }
        } else if (inbound != null) {
            sessionStore.save(self, peer, deviceId, inbound, password);
            sessionStore.deleteInbound(self, peer, deviceId);
        }
    }
    private String currentPassword() throws IdentityException {
        char[] password = ApplicatonStore.getSessionPassword();
        if (password == null || password.length == 0) {
            throw new IdentityException("会话已锁定，请重新登录");
        }
        return new String(password);
    }

    private IdentityStore.Identity requireIdentity() throws IdentityException {
        IdentityStore.Identity identity = keyManager.getIdentity();
        if (identity == null) {
            throw new IdentityException("本地加密身份尚未初始化，请重新登录");
        }
        return identity;
    }

    // ==================== 发送 ====================

    /**
     * 加密并发送一条消息给对方的<b>所有设备</b>。
     */
    public DecryptedMessage send(String peer, String plaintext) throws IdentityException {
        IdentityStore.Identity identity = requireIdentity();
        String self = identity.getUsername();

        // 先拿设备列表（不消耗 OPK），再只为「本地还没有会话」的设备领取预密钥包。
        // 旧实现每条消息都领取全部设备的包，会把对方的 OPK 白白耗尽。
        List<com.junesoon.mim.model.KeyBundle> devices =
            preKeyManager.fetchVerifiedDevices(peer);
        if (devices.isEmpty()) {
            throw new IdentityException("对方没有可用的加密设备");
        }

        String clientMsgId = java.util.UUID.randomUUID().toString();
        long clientTime = System.currentTimeMillis();
        List<CipherMessage> replicas = new ArrayList<>(devices.size());

        // 网络请求放在 cryptoLock 外，避免一次慢请求阻塞所有会话
        Map<String, PreKeyBundle> pendingBundles = new LinkedHashMap<>();
        for (com.junesoon.mim.model.KeyBundle device : devices) {
            if (device.getDeviceId() == null || device.getDeviceId().isEmpty()) {
                continue;
            }
            PeerCipher existing = slot(peer, device.getDeviceId());
            if (existing.sendingState() == null) {
                try {
                    pendingBundles.put(device.getDeviceId(),
                        preKeyManager.fetchVerifiedBundle(peer, device.getDeviceId()));
                } catch (com.junesoon.mim.crypto.PreKeyUnavailableException e) {
                    // 对方可能刚注册、预密钥还没上传完：跳过这台设备，
                    // 继续发给其它已就绪的设备，而不是让整条消息失败
                    log.warn("跳过设备 {}：{}", device.getDeviceId(), e.getMessage());
                }
            }
        }

        for (com.junesoon.mim.model.KeyBundle device : devices) {
            String deviceId = device.getDeviceId();
            if (deviceId == null || deviceId.isEmpty()) {
                continue;
            }
            String key = sessionKey(peer, deviceId);
            PeerCipher slot = slot(peer, deviceId);
            SessionState state;
            boolean isNew;
            synchronized (cryptoLock) {
                state = slot.sendingState();
                isNew = state == null;
                log.info("发送前检查 key={} 命中={} 现有键={}", key, !isNew, sessions.keySet());
                if (isNew) {
                    log.warn("会话未命中，新建：key={} 已有键={}", key, sessions.keySet());
                    PreKeyBundle bundle = pendingBundles.get(deviceId);
                    if (bundle == null) {
                        log.warn("设备 {} 没有可用预密钥，本次跳过", deviceId);
                        continue;
                    }
                    state = createSession(bundle);
                    slot.setOutbound(state);
                }

                RatchetService.Encrypted encrypted = ratchet.encrypt(state, plaintext);
                persistSlot(peer, deviceId, slot);
                log.info("发送到 {}/{}：序号={} 发送链长度={}",
                    peer, deviceId, encrypted.getHeader().getMessageNumber(),
                    state.getSendCount());

                CipherMessage replica = new CipherMessage();
                replica.setClientMsgId(clientMsgId);
                replica.setFromUsername(self);
                replica.setToUsername(peer);
                replica.setTargetDeviceId(deviceId);
                replica.setAlg(RatchetService.ALGORITHM);
                replica.setIv(encrypted.getIv());
                replica.setCiphertext(encrypted.getCiphertext());
                replica.setRatchetPublic(encrypted.getHeader().getDhPublic());
                replica.setPreviousChainLength(encrypted.getHeader().getPreviousChainLength());
                replica.setMessageNumber(encrypted.getHeader().getMessageNumber());
                replica.setClientTime(clientTime);

                // 从会话状态读握手参数：它随会话持久化，重启后仍能带上。
                // 只要状态里还留着参数就继续携带——对端可能还没建过这条链。
                if (state.hasPendingHandshake()) {
                    replica.setSenderIdentityKey(state.getHandshakeSenderIdentityKey());
                    replica.setSenderEphemeralKey(state.getHandshakeSenderEphemeralKey());
                    replica.setSenderUsedOneTimePreKey(state.isHandshakeUsedOneTimePreKey());
                    replica.setConsumedOneTimePreKeyId(state.getHandshakeOneTimePreKeyId());
                    replica.setRecipientSignedPreKeyId(
                        state.getHandshakeRecipientSignedPreKeyId());
                } else {
                    log.info("发送到 {}/{} 走已建立的应答链，不再附带握手参数", peer, deviceId);
                }
                replicas.add(replica);
            }
        }

        if (replicas.isEmpty()) {
            throw new IdentityException("对方设备尚未准备好接收加密消息（预密钥未发布）");
        }

        ObjectRestResponse<List<CipherMessage>> response;
        try {
            response = Request.connector(MessageFeign.class).send(replicas);
        } catch (Exception e) {
            throw new IdentityException("消息发送失败：" + e.getMessage());
        }
        if (response == null || response.getStatus() != 200 || response.getData() == null) {
            String error = response == null ? "服务端无响应" : response.getMessage();
            throw new IdentityException(error == null ? "消息发送失败" : error);
        }

        CipherMessage first = response.getData().isEmpty() ? replicas.get(0)
            : response.getData().get(0);
        if (first.getClientMsgId() == null) {
            first.setClientMsgId(clientMsgId);
        }
        synchronized (cryptoLock) {
            plaintextStore.put(cacheKey(self, keyManager.deviceId(), clientMsgId), plaintext);
        }
        return new DecryptedMessage(first, plaintext, false);
    }

    /**
     * 把一条已有原文重新加密给自己的另一台设备。不经过普通发送接口，避免被记成一条新消息。
     *
     * @param bundle 目标设备的预密钥包；已有会话时可为 null
     */
    public CipherMessage wrapForOwnDevice(String targetDeviceId, String plaintext,
        String clientMsgId, long clientTime, PreKeyBundle bundle) throws IdentityException {
        if (targetDeviceId == null || targetDeviceId.isEmpty()) {
            throw new IdentityException("目标设备不能为空");
        }
        if (plaintext == null) {
            throw new IdentityException("没有可转发的原文");
        }
        String slotPeer = syncSlot(targetDeviceId);
        synchronized (cryptoLock) {
            PeerCipher cipher = slot(slotPeer, targetDeviceId);
            SessionState state = cipher.sendingState();
            if (state == null) {
                if (bundle == null) {
                    throw new IdentityException("缺少目标设备的预密钥包");
                }
                state = createSyncSession(bundle, slotPeer);
                cipher.setOutbound(state);
            }
            RatchetService.Encrypted encrypted = ratchet.encrypt(state, plaintext);
            persistSlot(slotPeer, targetDeviceId, cipher);

            CipherMessage replica = new CipherMessage();
            replica.setClientMsgId(clientMsgId);
            replica.setTargetDeviceId(targetDeviceId);
            replica.setAlg(RatchetService.ALGORITHM);
            replica.setIv(encrypted.getIv());
            replica.setCiphertext(encrypted.getCiphertext());
            replica.setRatchetPublic(encrypted.getHeader().getDhPublic());
            replica.setPreviousChainLength(encrypted.getHeader().getPreviousChainLength());
            replica.setMessageNumber(encrypted.getHeader().getMessageNumber());
            replica.setClientTime(clientTime);
            if (state.hasPendingHandshake()) {
                replica.setSenderIdentityKey(state.getHandshakeSenderIdentityKey());
                replica.setSenderEphemeralKey(state.getHandshakeSenderEphemeralKey());
                replica.setSenderUsedOneTimePreKey(state.isHandshakeUsedOneTimePreKey());
                replica.setConsumedOneTimePreKeyId(state.getHandshakeOneTimePreKeyId());
                replica.setRecipientSignedPreKeyId(state.getHandshakeRecipientSignedPreKeyId());
            }
            return replica;
        }
    }

    /**
     * 丢弃与某台本账号设备的同步会话。
     *
     * <p>用户刚刚确认一台新设备、或设备公钥发生变化时必须重建会话，
     * 不能复用旧版本未经确认时建立的链。
     */
    public void invalidateSyncSession(String targetDeviceId) {
        if (targetDeviceId == null || targetDeviceId.isEmpty()) {
            return;
        }
        String slotPeer = syncSlot(targetDeviceId);
        synchronized (cryptoLock) {
            sessions.remove(sessionKey(slotPeer, targetDeviceId));
            IdentityStore.Identity identity = keyManager.getIdentity();
            if (identity != null) {
                sessionStore.delete(identity.getUsername(), slotPeer, targetDeviceId);
                sessionStore.deleteInbound(identity.getUsername(), slotPeer, targetDeviceId);
            }
        }
    }

    /**
     * 和自己另一台设备建会话。身份公钥必须来自本机已授权的设备清单，
     * 不能使用服务端随包返回的公钥。
     */
    private SessionState createSyncSession(PreKeyBundle bundle, String slotPeer)
        throws IdentityException {
        IdentityStore.Identity identity = requireIdentity();
        preKeyManager.verifyOwnDeviceBundle(bundle);
        RatchetService.InitiatorResult result = ratchet.initiatorSession(slotPeer,
            identity.getPrivateKey(), identity.getPublicKeyBase64(), bundle.getIdentityPublicKey(),
            bundle.getSignedPreKeyPublic(), bundle.getOneTimePreKeyPublic());
        SessionState state = result.getState();
        state.setDeviceId(bundle.getDeviceId());
        state.setInitiator(true);
        state.setHandshakeSenderIdentityKey(result.getSenderIdentityKey());
        state.setHandshakeSenderEphemeralKey(result.getSenderEphemeralKey());
        state.setHandshakeUsedOneTimePreKey(result.isUsedOneTimePreKey());
        state.setHandshakeOneTimePreKeyId(bundle.getOneTimePreKeyId());
        state.setHandshakeRecipientSignedPreKeyId(bundle.getSignedPreKeyId());
        return state;
    }

    // ==================== 接收 ====================

    /**
     * 解密一条消息。对方发起的链和我方发起的链分开保存，解密失败不改任何一条。
     */
    public DecryptedMessage decrypt(CipherMessage message) {
        if (message == null) {
            return null;
        }
        if (Boolean.TRUE.equals(message.getRecalled())) {
            return new DecryptedMessage(message, "", false);
        }
        IdentityStore.Identity identity = keyManager.getIdentity();
        if (identity == null) {
            return new DecryptedMessage(message, "[未解锁本地密钥，无法解密]", true);
        }
        String self = identity.getUsername();
        final boolean deviceSync = message.getSyncFromDevice() != null
            && !message.getSyncFromDevice().isEmpty();
        boolean isSelf = self.equals(message.getFromUsername());

        // 设备间同步先确认发送设备的身份，再用转发设备的签名覆盖服务端给的归属字段。
        // 这一步可能弹窗，放在 cryptoLock 外，避免用户思考期间阻塞其它会话解密。
        if (deviceSync) {
            try {
                ensureSyncSenderTrusted(message);
            } catch (Exception e) {
                log.warn("同步设备确认失败：{}", e.getMessage());
                return new DecryptedMessage(message, "[此消息无法解密]", true);
            }
        }

        // 缓存键必须包含「谁发的、哪台设备发的」。只按 clientMsgId 会让不同发送者
        // 复用同一个 ID 时命中别人的明文，等于绕过解密直接伪造消息内容。
        final String cacheKey;
        if (deviceSync) {
            cacheKey = syncCacheKey(message);
        } else {
            cacheKey = cacheKey(message.getFromUsername(),
                normalizeDevice(message.getFromDeviceId()), message.getClientMsgId());
        }
        String cached = readCache(message, cacheKey, deviceSync, isSelf);
        if (cached != null) {
            return new DecryptedMessage(message, cached, false);
        }
        if (isSelf && !deviceSync) {
            // 自己发出的密文是用对方的接收链加密的，本机棘轮解不开。
            // 原文只存在于发送当时写入的本地记录里。另一台设备转来的副本不走这里。
            return new DecryptedMessage(message, "（本机未保留原文）", true);
        }

        final String peer;
        final String peerDevice;
        if (deviceSync) {
            peer = syncSlot(message.getSyncFromDevice());
            peerDevice = message.getSyncFromDevice();
        } else {
            peer = message.getFromUsername();
            peerDevice = normalizeDevice(message.getFromDeviceId());
        }
        boolean hasHandshake = message.getSenderIdentityKey() != null
            && !message.getSenderIdentityKey().isEmpty()
            && message.getSenderEphemeralKey() != null
            && !message.getSenderEphemeralKey().isEmpty();
        if (message.getRatchetPublic() == null || message.getPreviousChainLength() == null
            || message.getMessageNumber() == null) {
            return new DecryptedMessage(message, "[此消息无法解密]", true);
        }

        try {
            synchronized (cryptoLock) {
                cached = readCache(message, cacheKey, deviceSync, isSelf);
                if (cached != null) {
                    return new DecryptedMessage(message, cached, false);
                }
                PeerCipher slot = slot(peer, peerDevice);
                final int[] usedOpk = new int[] {-1};
                RatchetService.Header header = new RatchetService.Header(message.getRatchetPublic(),
                    message.getPreviousChainLength(), message.getMessageNumber());
                String plaintext = slot.open(hasHandshake, message.getSenderEphemeralKey(), header,
                    message.getIv(), message.getCiphertext(), () -> {
                        if (deviceSync) {
                            // 设备间同步只证明「发送方持有本账号某台设备的身份私钥」。
                            // 真正的安全边界在发送侧：只有用户批准过的设备才能拿到历史。
                            keyManager.assertDeviceIdentity(message.getSyncFromDevice(),
                                message.getSenderIdentityKey());
                        }
                        Integer opkId = prepareResponder(peer, message, deviceSync);
                        if (opkId != null) {
                            usedOpk[0] = opkId;
                        }
                        return buildResponderSession(peer, peerDevice, message);
                    });
                if (deviceSync) {
                    // 用转发设备的身份公钥验证「原消息归属 + 内容」的签名，
                    // 再以签名覆盖服务端给的 from/to/fromDevice。
                    if (!syncSignatureValid(message, plaintext)) {
                        throw new IdentityException("同步消息来源签名无效");
                    }
                    applySyncSignedFields(message);
                }
                if (usedOpk[0] >= 0) {
                    preKeyManager.getPreKeyStore().consumeOneTimePreKey(usedOpk[0]);
                    try {
                        String password = currentPassword();
                        preKeyManager.getPreKeyStore().save(self, keyManager.deviceId(), password);
                        preKeyManager.replenishIfNeeded(password);
                    } catch (IdentityException e) {
                        log.warn("一次性预密钥保存失败：{}", e.getMessage());
                    }
                }
                try {
                    persistSlot(peer, peerDevice, slot);
                } catch (IdentityException e) {
                    log.warn("会话状态保存失败，可能导致后续消息解密异常：{}", e.getMessage());
                }
                plaintextStore.put(cacheKey, plaintext);
                return new DecryptedMessage(message, plaintext, false);
            }
        } catch (Exception e) {
            log.warn("解密消息 {} 失败：{}", message.getClientMsgId(), e.getMessage());
            return new DecryptedMessage(message, "[此消息无法解密]", true);
        }
    }

    /**
     * 读本地明文缓存。
     *
     * <p>同步消息必须先验证转发设备的签名才能使用缓存，否则被攻破的服务端可以
     * 用同一个 clientMsgId 让设备展示另一条旧消息的内容。普通消息继续按
     * 「发送者 + 发送设备 + clientMsgId」查缓存。
     */
    private String readCache(CipherMessage message, String cacheKey, boolean deviceSync,
        boolean isSelf) {
        if (deviceSync) {
            String cached = plaintextStore.get(cacheKey);
            if (cached != null && syncSignatureValid(message, cached)) {
                applySyncSignedFields(message);
                return cached;
            }
            return null;
        }
        String cached = plaintextStore.get(cacheKey);
        if (cached == null && isSelf) {
            // 兼容升级前只按 clientMsgId 保存的旧记录，仅用于自己发出的消息
            cached = plaintextStore.get(message.getClientMsgId());
        }
        return cached;
    }

    /**
     * 同步消息发送设备必须是用户批准过的本账号设备。
     *
     * <p>刚登录的新设备第一次收到旧设备同步时会弹窗展示旧设备指纹，
     * 用户应与旧设备「本机设备安全码」核对后再确认。
     */
    private void ensureSyncSenderTrusted(CipherMessage message) throws IdentityException {
        String deviceId = message.getSyncFromDevice();
        String identityKey = message.getSenderIdentityKey();
        if (deviceId == null || identityKey == null) {
            throw new IdentityException("同步消息缺少发送设备身份");
        }
        String fingerprint = fingerprintOf(identityKey);
        String denyKey = deviceId + "|" + fingerprint;
        if (SYNC_DENIED.contains(denyKey)) {
            throw new IdentityException("已拒绝该设备的同步");
        }
        OwnDeviceTrust trust = keyManager.getOwnDeviceTrust();
        if (trust.isTrusted(deviceId, identityKey)) {
            return;
        }
        SyncApprover approver = syncApprover;
        if (approver == null || !approver.approve(deviceId, fingerprint)) {
            SYNC_DENIED.add(denyKey);
            throw new IdentityException("未授权的设备同步，已拒绝");
        }
        trust.trustDevice(deviceId, identityKey, currentPassword());
    }

    private static String syncCacheKey(CipherMessage message) {
        return "sync|" + normalizeDevice(message.getSyncFromDevice()) + "|"
            + (message.getClientMsgId() == null ? "" : message.getClientMsgId());
    }

    private static boolean syncSignatureValid(CipherMessage message, String plaintext) {
        String signature = message.getSyncSignature();
        String from = message.getSyncOriginalFrom();
        String to = message.getSyncOriginalTo();
        String device = message.getSyncOriginalDevice();
        if (signature == null || from == null || to == null) {
            return false;
        }
        String canonical = SyncPayload.canonical(from, to, device, message.getClientMsgId(),
            message.getClientTime() == null ? 0L : message.getClientTime(),
            message.getTargetDeviceId(), plaintext);
        return SyncPayload.verify(message.getSenderIdentityKey(), canonical, signature);
    }

    private static void applySyncSignedFields(CipherMessage message) {
        message.setFromUsername(message.getSyncOriginalFrom());
        message.setToUsername(message.getSyncOriginalTo());
        message.setFromDeviceId(message.getSyncOriginalDevice());
    }

    private static String fingerprintOf(String identityKey) throws IdentityException {
        try {
            return TrustStore.shortFingerprint(Hkdf.sha256Hex(
                java.util.Base64.getDecoder().decode(identityKey)));
        } catch (IllegalArgumentException e) {
            throw new IdentityException("同步设备身份公钥格式错误");
        }
    }

    /**
     * 确认这条握手消息要消耗的一次性预密钥还在本地。只查看，不删除。
     *
     * @return 需要在解密成功后删除的预密钥编号；未使用则返回 null
     */
    private Integer prepareResponder(String peer, CipherMessage message, boolean deviceSync)
        throws IdentityException {
        if (message.getSenderIdentityKey() == null || message.getSenderEphemeralKey() == null) {
            throw new IdentityException("收到未知会话的消息，但缺少 X3DH 握手参数，无法解密");
        }
        if (!deviceSync) {
            String password = currentPassword();
            TrustStore.VerificationResult trust = keyManager.getTrustStore()
                .verify(peer, normalizeDevice(message.getFromDeviceId()),
                    message.getSenderIdentityKey(), password);
            if (!trust.isSendable()) {
                throw new IdentityException(trust.describe());
            }
        }
        if (!Boolean.TRUE.equals(message.getSenderUsedOneTimePreKey())) {
            return null;
        }
        Integer consumedId = message.getConsumedOneTimePreKeyId();
        if (consumedId == null
            || preKeyManager.getPreKeyStore().peekOneTimePreKey(consumedId) == null) {
            throw new IdentityException("对方使用了本地不存在的一次性预密钥 id="
                + message.getConsumedOneTimePreKeyId() + "，可能是本地预密钥数据已损坏");
        }
        return consumedId;
    }

    /**
     * 用消息里的 X3DH 参数建立一条尚未写入会话槽的应答链。
     */
    private SessionState buildResponderSession(String peer, String peerDevice, CipherMessage message)
        throws IdentityException {
        IdentityStore.Identity identity = requireIdentity();
        PreKeyStore preKeyStore = preKeyManager.getPreKeyStore();
        PreKeyStore.LocalSignedPreKey signed;
        Integer requestedSpk = message.getRecipientSignedPreKeyId();
        if (requestedSpk != null) {
            signed = preKeyStore.findSignedPreKey(requestedSpk);
            if (signed == null) {
                throw new IdentityException("找不到编号 " + requestedSpk
                    + " 的签名预密钥，可能已轮换过期");
            }
        } else {
            // 旧客户端不带编号，退回当前签名预密钥
            signed = preKeyStore.getSignedPreKey();
        }
        if (signed == null) {
            throw new IdentityException("本地签名预密钥缺失，无法建立会话");
        }

        PrivateKey oneTimePrivate = null;
        boolean usedOneTime = Boolean.TRUE.equals(message.getSenderUsedOneTimePreKey());
        if (usedOneTime) {
            PreKeyStore.LocalOneTimePreKey oneTime =
                preKeyStore.peekOneTimePreKey(message.getConsumedOneTimePreKeyId());
            if (oneTime == null) {
                throw new IdentityException("对方使用了本地不存在的一次性预密钥 id="
                    + message.getConsumedOneTimePreKeyId());
            }
            oneTimePrivate = oneTime.getPrivateKey();
        }

        SessionState state = ratchet.responderSession(peer, identity.getPrivateKey(),
            identity.getPublicKeyBase64(), signed.getPrivateKey(), signed.getPublicKeyBase64(),
            oneTimePrivate, message.getSenderIdentityKey(), message.getSenderEphemeralKey(),
            usedOneTime);
        state.setInitiator(false);
        state.setDeviceId(peerDevice);
        return state;
    }

    public List<DecryptedMessage> decryptAll(List<CipherMessage> raw) {
        List<DecryptedMessage> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        List<CipherMessage> ordered = new ArrayList<>(raw);
        ordered.sort((left, right) -> {
            long leftId = left.getId() == null ? 0L : left.getId();
            long rightId = right.getId() == null ? 0L : right.getId();
            return Long.compare(leftId, rightId);
        });
        for (CipherMessage message : ordered) {
            DecryptedMessage decrypted = decrypt(message);
            if (decrypted != null) {
                result.add(decrypted);
            }
        }
        return result;
    }

    // ==================== 查询与状态 ====================

    public List<DecryptedMessage> history(String peer, Long beforeId, int limit) {
        try {
            ObjectRestResponse<List<CipherMessage>> response =
                Request.connector(MessageFeign.class).history(peer, beforeId, limit);
            List<CipherMessage> data = (response == null || response.getData() == null)
                ? new ArrayList<>() : response.getData();
            return decryptAll(data);
        } catch (Exception e) {
            log.warn("拉取历史消息失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<DecryptedMessage> undelivered(int limit) {
        try {
            ObjectRestResponse<List<CipherMessage>> response =
                Request.connector(MessageFeign.class).undelivered(limit);
            List<CipherMessage> data = (response == null || response.getData() == null)
                ? new ArrayList<>() : response.getData();
            return decryptAll(data);
        } catch (Exception e) {
            log.warn("拉取离线消息失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public void markDelivered(long beforeId) {
        try {
            Request.connector(MessageFeign.class).delivered(beforeId);
        } catch (Exception e) {
            log.debug("确认投递失败：{}", e.getMessage());
        }
    }

    public void markRead(String peer, Long beforeId) {
        try {
            Request.connector(MessageFeign.class).read(new ReadRequest(peer, beforeId));
        } catch (Exception e) {
            log.debug("标记已读失败：{}", e.getMessage());
        }
    }

    public long unread(String peer) {
        try {
            ObjectRestResponse<Long> response = Request.connector(MessageFeign.class).unread(peer);
            return (response == null || response.getData() == null) ? 0L : response.getData();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 撤回自己发送的消息（按 clientMsgId 撤回所有设备副本）。
     */
    public void recall(String clientMsgId) throws IdentityException {
        try {
            ObjectRestResponse<CipherMessage> response =
                Request.connector(MessageFeign.class).recall(clientMsgId);
            if (response == null || response.getStatus() != 200) {
                String error = response == null ? "服务端无响应" : response.getMessage();
                throw new IdentityException(error == null ? "撤回失败" : error);
            }
        } catch (IdentityException e) {
            throw e;
        } catch (Exception e) {
            throw new IdentityException("撤回失败：" + e.getMessage());
        }
    }

    /**
     * 清除与某人的对话。forBoth 为真时对方的记录也一起清掉。
     */
    public void clearConversation(String peer, boolean forBoth) throws IdentityException {
        try {
            ObjectRestResponse<com.junesoon.mim.model.ClearResult> response =
                Request.connector(MessageFeign.class).clear(
                    new com.junesoon.mim.model.ClearRequest(peer, forBoth));
            if (response == null || response.getStatus() != 200) {
                String error = response == null ? "服务端无响应" : response.getMessage();
                throw new IdentityException(error == null ? "清除失败" : error);
            }
        } catch (IdentityException e) {
            throw e;
        } catch (Exception e) {
            throw new IdentityException("清除失败：" + e.getMessage());
        }
    }

    /**
     * 当前会话的键快照，供排查与调试。
     */
    public Map<String, SessionState> sessionSnapshot() {
        Map<String, SessionState> view = new LinkedHashMap<>();
        for (Map.Entry<String, PeerCipher> entry : sessions.entrySet()) {
            SessionState state = entry.getValue().sendingState();
            if (state == null) {
                state = entry.getValue().getInbound();
            }
            if (state != null) {
                view.put(entry.getKey(), state);
            }
        }
        return view;
    }
}
