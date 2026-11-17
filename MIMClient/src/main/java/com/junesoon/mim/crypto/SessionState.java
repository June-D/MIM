package com.junesoon.mim.crypto;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import lombok.Data;

/**
 * 一个对端会话的双棘轮状态。
 *
 * <p>字段语义（对照 Signal Double Ratchet 规范）：
 * <ul>
 *   <li>{@code rootKey} —— 根密钥，每次 DH 棘轮推进都会更新；</li>
 *   <li>{@code sendChainKey} —— 发送链密钥，每发一条消息前进一次；</li>
 *   <li>{@code recvChainKey} —— 接收链密钥，每成功解一条消息前进一次；</li>
 *   <li>{@code dhSelf} —— 本地当前棘轮私钥；{@code dhRemote} —— 对端当前棘轮公钥；</li>
 *   <li>{@code sendCount} / {@code recvCount} —— 当前链上的消息序号；</li>
 *   <li>{@code prevSendCount} —— 上一次发送链的长度，对端收到新 DH 公钥时需要用它跳过旧链。</li>
 * </ul>
 *
 * <p>状态里含私钥，<b>必须加密落盘</b>，由 {@link SessionStore} 负责。
 */
@Data
public class SessionState {

    private String peer;

    /**
     * 对端设备标识。同一对端的不同设备是独立会话。
     */
    private String deviceId = "default";

    /**
     * 会话建立时间（毫秒），用于展示与排查。
     */
    private long createdAt;

    private byte[] rootKey;

    /**
     * 发送链。为 null 表示尚未建立发送能力（首次发起会话的一方在发出第一条消息前就已具备）。
     */
    private byte[] sendChainKey;

    private byte[] recvChainKey;

    /**
     * 已跳过的消息密钥缓存："{ratchetPubBase64}:{messageNumber}" -> Base64(messageKey)。
     * 用于乱序到达的消息解密。容量有上限，防止无限增长。
     */
    private Map<String, String> skippedKeys = new LinkedHashMap<String, String>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_SKIPPED;
        }
    };

    /**
     * 每条链最多允许跳过的消息数。超过则拒绝，避免被恶意超大序号拖爆内存。
     */
    public static final int MAX_SKIPPED = 1000;

    private String dhSelfPrivate;

    private String dhSelfPublic;

    private String dhRemotePublic;

    private int sendCount;

    private int recvCount;

    private int prevSendCount;

    // ==================== X3DH 握手参数 ====================

    /**
     * 本会话的首条消息需要携带的握手参数（发起方才有）。
     *
     * <p>持久化到会话状态而不是放内存：发送方无法知道接收方是否已经成功建立会话
     * （对端可能因为换设备、丢本地状态而需要重建）。若这些参数只存在内存里，
     * 重启后新消息不再携带它们，对端就永远建不出会话，表现为"无法解密"。
     */
    private String handshakeSenderIdentityKey;

    private String handshakeSenderEphemeralKey;

    private boolean handshakeUsedOneTimePreKey;

    private Integer handshakeOneTimePreKeyId;

    /**
     * 是否有未确认的握手参数。
     */
    public boolean hasPendingHandshake() {
        return handshakeSenderIdentityKey != null && handshakeSenderEphemeralKey != null;
    }

    /**
     * 本会话是否由我方发起。
     *
     * <p>用于解决"双方各自独立发起会话"的冲突：若我方是发起方，而收到对方
     * 带握手参数的消息，说明对方也发起了——此时必须以<b>应答方</b>身份
     * 用对方的参数重建接收链，否则两条链永远对不上。
     */
    private boolean initiator = true;

    public boolean isInitiator() {
        return initiator;
    }

    public void setInitiator(boolean initiator) {
        this.initiator = initiator;
    }

    public String skippedKeyId(String ratchetPublic, int messageNumber) {
        return ratchetPublic + ":" + messageNumber;
    }

    public void cacheSkipped(String ratchetPublic, int messageNumber, byte[] messageKey) {
        skippedKeys.put(skippedKeyId(ratchetPublic, messageNumber),
            Base64.getEncoder().encodeToString(messageKey));
    }

    public byte[] takeSkipped(String ratchetPublic, int messageNumber) {
        String value = skippedKeys.remove(skippedKeyId(ratchetPublic, messageNumber));
        return value == null ? null : Base64.getDecoder().decode(value);
    }

    /**
     * 用一次试解密得到的状态覆盖自己。只在认证通过后调用。
     */
    public void accept(SessionState trial) {
        Hkdf.wipe(rootKey);
        rootKey = trial.rootKey;
        trial.rootKey = null;
        Hkdf.wipe(sendChainKey);
        sendChainKey = trial.sendChainKey;
        trial.sendChainKey = null;
        Hkdf.wipe(recvChainKey);
        recvChainKey = trial.recvChainKey;
        trial.recvChainKey = null;
        dhSelfPrivate = trial.dhSelfPrivate;
        dhSelfPublic = trial.dhSelfPublic;
        dhRemotePublic = trial.dhRemotePublic;
        sendCount = trial.sendCount;
        recvCount = trial.recvCount;
        prevSendCount = trial.prevSendCount;
        handshakeSenderIdentityKey = trial.handshakeSenderIdentityKey;
        handshakeSenderEphemeralKey = trial.handshakeSenderEphemeralKey;
        handshakeUsedOneTimePreKey = trial.handshakeUsedOneTimePreKey;
        handshakeOneTimePreKeyId = trial.handshakeOneTimePreKeyId;
        initiator = trial.initiator;
        skippedKeys.clear();
        if (trial.skippedKeys != null) {
            skippedKeys.putAll(trial.skippedKeys);
        }
    }

    /**
     * 深拷贝，供加密落盘前做无副作用的序列化。
     */
    public SessionState copy() {
        SessionState copy = new SessionState();
        copy.peer = peer;
        copy.deviceId = deviceId;
        copy.createdAt = createdAt;
        copy.rootKey = rootKey == null ? null : rootKey.clone();
        copy.sendChainKey = sendChainKey == null ? null : sendChainKey.clone();
        copy.recvChainKey = recvChainKey == null ? null : recvChainKey.clone();
        copy.skippedKeys = new TreeMap<>(skippedKeys);
        copy.dhSelfPrivate = dhSelfPrivate;
        copy.dhSelfPublic = dhSelfPublic;
        copy.dhRemotePublic = dhRemotePublic;
        copy.sendCount = sendCount;
        copy.recvCount = recvCount;
        copy.prevSendCount = prevSendCount;
        copy.handshakeSenderIdentityKey = handshakeSenderIdentityKey;
        copy.handshakeSenderEphemeralKey = handshakeSenderEphemeralKey;
        copy.handshakeUsedOneTimePreKey = handshakeUsedOneTimePreKey;
        copy.handshakeOneTimePreKeyId = handshakeOneTimePreKeyId;
        copy.initiator = initiator;
        return copy;
    }
}
