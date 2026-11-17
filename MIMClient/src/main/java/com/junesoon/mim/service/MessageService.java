package com.junesoon.mim.service;

import com.junesoon.mim.components.ApplicatonStore;
import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.components.Request;
import com.junesoon.mim.crypto.IdentityException;
import com.junesoon.mim.crypto.IdentityStore;
import com.junesoon.mim.crypto.PeerCipher;
import com.junesoon.mim.crypto.PlaintextStore;
import com.junesoon.mim.crypto.PreKeyStore;
import com.junesoon.mim.crypto.RatchetService;
import com.junesoon.mim.crypto.SessionState;
import com.junesoon.mim.crypto.SessionStore;
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

    private static String sessionKey(String peer, String deviceId) {
        return peer + "@" + deviceId;
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

        // 先用已固定的身份公钥做 TOFU 校验，再验预密钥签名
        TrustStore.VerificationResult trust = keyManager.getTrustStore()
            .verify(bundle.getUsername(), bundle.getIdentityPublicKey(), password);
        if (!trust.isSendable()) {
            throw new IdentityException(trust.describe());
        }
        if (!com.junesoon.mim.crypto.DhKeyUtil.verifySignedPreKey(bundle.getIdentityPublicKey(),
            bundle.getSignedPreKeyId(), bundle.getSignedPreKeyPublic(),
            bundle.getSignedPreKeySignature())) {
            throw new IdentityException("对方预密钥签名验证失败，可能遭到中间人攻击，已中止");
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

        // 领取对方所有有效设备的预密钥包；每个包会消耗对应设备一个 OPK
        List<PreKeyBundle> bundles = preKeyManager.fetchVerifiedBundles(peer);
        if (bundles.isEmpty()) {
            throw new IdentityException("对方没有可用的加密设备");
        }

        String clientMsgId = java.util.UUID.randomUUID().toString();
        long clientTime = System.currentTimeMillis();
        List<CipherMessage> replicas = new ArrayList<>(bundles.size());

        for (PreKeyBundle bundle : bundles) {
            String key = sessionKey(peer, bundle.getDeviceId());
            PeerCipher slot = slot(peer, bundle.getDeviceId());
            SessionState state;
            boolean isNew;
            synchronized (cryptoLock) {
                state = slot.sendingState();
                isNew = state == null;
                log.info("发送前检查 key={} 命中={} 现有键={}", key, !isNew, sessions.keySet());
                if (isNew) {
                    log.warn("会话未命中，新建：key={} 已有键={}", key, sessions.keySet());
                    state = createSession(bundle);
                    slot.setOutbound(state);
                }

                RatchetService.Encrypted encrypted = ratchet.encrypt(state, plaintext);
                persistSlot(peer, bundle.getDeviceId(), slot);
                log.info("发送到 {}/{}：序号={} 发送链长度={}",
                    peer, bundle.getDeviceId(), encrypted.getHeader().getMessageNumber(),
                    state.getSendCount());

                CipherMessage replica = new CipherMessage();
                replica.setClientMsgId(clientMsgId);
                replica.setFromUsername(self);
                replica.setToUsername(peer);
                replica.setTargetDeviceId(bundle.getDeviceId());
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
                } else {
                    log.info("发送到 {}/{} 走已建立的应答链，不再附带握手参数", peer, bundle.getDeviceId());
                }
                replicas.add(replica);
            }
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
            plaintextStore.put(clientMsgId, plaintext);
        }
        return new DecryptedMessage(first, plaintext, false);
    }

    /**
     * 把一条已有原文重新加密给自己的另一台设备。不经过普通发送接口，避免被记成一条新消息。
     */
    public CipherMessage wrapForOwnDevice(String targetDeviceId, String plaintext,
        String clientMsgId, long clientTime) throws IdentityException {
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
                PreKeyBundle bundle = preKeyManager.fetchOwnDevice(targetDeviceId);
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
            }
            return replica;
        }
    }

    /**
     * 和自己另一台设备建会话。信任记在 sync-设备号上，不占用账号名的 TOFU 记录。
     */
    private SessionState createSyncSession(PreKeyBundle bundle, String slotPeer)
        throws IdentityException {
        IdentityStore.Identity identity = requireIdentity();
        String password = currentPassword();
        TrustStore.VerificationResult trust = keyManager.getTrustStore()
            .verify(slotPeer, bundle.getIdentityPublicKey(), password);
        if (!trust.isSendable()) {
            throw new IdentityException(trust.describe());
        }
        if (!com.junesoon.mim.crypto.DhKeyUtil.verifySignedPreKey(bundle.getIdentityPublicKey(),
            bundle.getSignedPreKeyId(), bundle.getSignedPreKeyPublic(),
            bundle.getSignedPreKeySignature())) {
            throw new IdentityException("设备预密钥签名验证失败，已中止同步");
        }
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
        String cached = plaintextStore.get(message.getClientMsgId());
        if (cached != null) {
            return new DecryptedMessage(message, cached, false);
        }
        String self = identity.getUsername();
        final boolean deviceSync = message.getSyncFromDevice() != null
            && !message.getSyncFromDevice().isEmpty();
        boolean isSelf = self.equals(message.getFromUsername());
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
            String deviceOnMessage = message.getFromDeviceId();
            peerDevice = (deviceOnMessage == null || deviceOnMessage.isEmpty())
                ? SessionStore.DEFAULT_DEVICE_ID : deviceOnMessage;
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
                cached = plaintextStore.get(message.getClientMsgId());
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
                            keyManager.assertDeviceIdentity(message.getSyncFromDevice(),
                                message.getSenderIdentityKey());
                        }
                        Integer opkId = prepareResponder(peer, message);
                        if (opkId != null) {
                            usedOpk[0] = opkId;
                        }
                        return buildResponderSession(peer, peerDevice, message);
                    });
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
                plaintextStore.put(message.getClientMsgId(), plaintext);
                return new DecryptedMessage(message, plaintext, false);
            }
        } catch (Exception e) {
            log.warn("解密消息 {} 失败：{}", message.getClientMsgId(), e.getMessage());
            return new DecryptedMessage(message, "[此消息无法解密]", true);
        }
    }

    /**
     * 确认这条握手消息要消耗的一次性预密钥还在本地。只查看，不删除。
     *
     * @return 需要在解密成功后删除的预密钥编号；未使用则返回 null
     */
    private Integer prepareResponder(String peer, CipherMessage message) throws IdentityException {
        if (message.getSenderIdentityKey() == null || message.getSenderEphemeralKey() == null) {
            throw new IdentityException("收到未知会话的消息，但缺少 X3DH 握手参数，无法解密");
        }
        String password = currentPassword();
        TrustStore.VerificationResult trust = keyManager.getTrustStore()
            .verify(peer, message.getSenderIdentityKey(), password);
        if (!trust.isSendable()) {
            throw new IdentityException(trust.describe());
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
        PreKeyStore.LocalSignedPreKey signed = preKeyStore.getSignedPreKey();
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
