package com.junesoon.mim.crypto;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;

/**
 * 双棘轮（Double Ratchet）实现。
 *
 * <p>相比原先的静态 ECDH，这里补上了两个关键性质：
 * <ul>
 *   <li><b>前向安全性</b>：每条消息的密钥由链密钥单向派生，用完即弃。
 *       私钥泄漏不影响已删除的旧消息密钥。</li>
 *   <li><b>后向恢复性（post-compromise security）</b>：每次完成一次 DH 棘轮，
 *       新的根密钥混入新的 DH 输出，攻击者即使拿到旧状态也无法继续解密新消息。</li>
 * </ul>
 *
 * <p>会话初始化采用 X3DH 的简化形式（无预密钥包，依赖公钥指纹线下核验）：
 * <pre>
 *   SS  = DH(A_identity, B_identity) || DH(A_ephemeral, B_identity)
 *   RK  = HKDF(SS, "MIM/v2/root")
 * </pre>
 *
 * <p>棘轮推进的密钥派生：
 * <pre>
 *   KDF_RK(rk, dhOut) : prk = HKDF-Extract(salt=rk, ikm=dhOut)
 *                       newRK = HKDF-Expand(prk, "MIM/v2/root", 32)
 *                       newCK = HKDF-Expand(prk, "MIM/v2/chain", 32)
 *   KDF_CK(ck)        : mk = HMAC-SHA256(ck, 0x01)
 *                       nextCk = HMAC-SHA256(ck, 0x03)
 * </pre>
 *
 * <p>消息头 {@code {dh, pn, n}} 参与 AAD，篡改会导致解密失败。
 */
@Slf4j
public class RatchetService {

    public static final String ALGORITHM = "X3DH-P256-DOUBLE-RATCHET-AES-256-GCM";

    private static final String INFO_ROOT = "MIM/v2/root";
    private static final String INFO_CHAIN = "MIM/v2/chain";
    private static final String INFO_INITIAL = "MIM/v2/initial";

    private static final byte CHAIN_ADVANCE_MESSAGE = 0x01;
    private static final byte CHAIN_ADVANCE_NEXT = 0x03;

    private static final int KEY_BYTES = 32;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    /**
     * 单次允许跳过的消息数上限。防止恶意超大序号导致内存爆炸。
     */
    private static final int MAX_SKIP = 200;

    /**
     * 一条消息的发送头。接收方靠它判断是否需要推进 DH 棘轮。
     */
    public static final class Header {
        private final String dhPublic;
        private final int previousChainLength;
        private final int messageNumber;

        public Header(String dhPublic, int previousChainLength, int messageNumber) {
            this.dhPublic = dhPublic;
            this.previousChainLength = previousChainLength;
            this.messageNumber = messageNumber;
        }

        public String getDhPublic() {
            return dhPublic;
        }

        public int getPreviousChainLength() {
            return previousChainLength;
        }

        public int getMessageNumber() {
            return messageNumber;
        }

        /**
         * 序列化成 AAD 字符串。顺序与字段名都固定，改动等于破坏兼容性。
         */
        String toAad() {
            return dhPublic + "|" + previousChainLength + "|" + messageNumber;
        }
    }

    /**
     * 加密结果。
     */
    public static final class Encrypted {
        private final Header header;
        private final String iv;
        private final String ciphertext;

        Encrypted(Header header, String iv, String ciphertext) {
            this.header = header;
            this.iv = iv;
            this.ciphertext = ciphertext;
        }

        public Header getHeader() {
            return header;
        }

        public String getIv() {
            return iv;
        }

        public String getCiphertext() {
            return ciphertext;
        }
    }

    // ==================== 会话初始化（X3DH） ====================

    /**
     * 发起方建会话（完整 X3DH）。
     *
     * <pre>
     *   DH1 = DH(IK_A, SPK_B)   身份密钥 × 对方签名预密钥
     *   DH2 = DH(EK_A, IK_B)    临时密钥 × 对方身份密钥
     *   DH3 = DH(EK_A, SPK_B)   临时密钥 × 对方签名预密钥
     *   DH4 = DH(EK_A, OPK_B)   临时密钥 × 对方一次性预密钥（可能省略）
     *   SK  = KDF(DH1 || DH2 || DH3 || DH4)
     * </pre>
     *
     * <p>调用前必须已用<b>已固定的身份公钥</b>验证过 {@code signedPreKeySignature}，
     * 否则攻击者可以用自己伪造的预密钥包冒充对方。
     *
     * @param peer                   对方用户名
     * @param selfIdentity           自己的身份私钥
     * @param selfIdentityPub        自己的身份公钥（Base64）
     * @param peerIdentityPub        对方身份公钥（Base64，已 TOFU 固定）
     * @param peerSignedPreKeyPub    对方签名预密钥公钥（Base64）
     * @param peerOneTimePreKeyPub   对方一次性预密钥公钥（Base64），可为 null
     */
    public InitiatorResult initiatorSession(String peer, PrivateKey selfIdentity,
        String selfIdentityPub, String peerIdentityPub, String peerSignedPreKeyPub,
        String peerOneTimePreKeyPub) {
        KeyPair ephemeral = DhKeyUtil.generateKeyPair();
        PublicKey peerIdentity = DhKeyUtil.decodePublicKey(peerIdentityPub);
        PublicKey peerSigned = DhKeyUtil.decodePublicKey(peerSignedPreKeyPub);

        // DH1 = DH(IK_A, SPK_B)
        byte[] dh1 = DhKeyUtil.agree(selfIdentity, peerSigned);
        // DH2 = DH(EK_A, IK_B)
        byte[] dh2 = DhKeyUtil.agree(ephemeral.getPrivate(), peerIdentity);
        // DH3 = DH(EK_A, SPK_B)
        byte[] dh3 = DhKeyUtil.agree(ephemeral.getPrivate(), peerSigned);
        // DH4 = DH(EK_A, OPK_B)，可选
        byte[] dh4 = null;
        if (peerOneTimePreKeyPub != null && !peerOneTimePreKeyPub.isEmpty()) {
            dh4 = DhKeyUtil.agree(ephemeral.getPrivate(),
                DhKeyUtil.decodePublicKey(peerOneTimePreKeyPub));
        }

        byte[] shared = concat(dh1, dh2, dh3, dh4);
        Hkdf.wipe(dh1);
        Hkdf.wipe(dh2);
        Hkdf.wipe(dh3);
        Hkdf.wipe(dh4);

        byte[] rootKey = Hkdf.derive(null, shared, INFO_INITIAL, KEY_BYTES);
        Hkdf.wipe(shared);

        SessionState state = new SessionState();
        state.setPeer(peer);
        state.setCreatedAt(System.currentTimeMillis());
        state.setDhSelfPrivate(Base64.getEncoder().encodeToString(ephemeral.getPrivate().getEncoded()));
        state.setDhSelfPublic(DhKeyUtil.encodePublicKey(ephemeral.getPublic()));
        state.setDhRemotePublic(peerSignedPreKeyPub);
        state.setRootKey(rootKey);

        // 发起方立即做一次 DH 棘轮，建立发送链：
        // 用临时私钥与对方签名预密钥协商，对应接收方用 SPK 私钥与 EK 公钥算出的同一值
        byte[] dhOut = DhKeyUtil.agree(ephemeral.getPrivate(), peerSigned);
        DerivedKeys derived = kdfRoot(rootKey, dhOut);
        Hkdf.wipe(dhOut);
        Hkdf.wipe(rootKey);
        state.setRootKey(derived.rootKey);
        state.setSendChainKey(derived.chainKey);
        state.setRecvChainKey(null);
        state.setSendCount(0);
        state.setRecvCount(0);
        state.setPrevSendCount(0);

        // 初始消息要把自己的身份公钥与临时公钥一起带过去，接收方才能完成 X3DH
        return new InitiatorResult(state, selfIdentityPub,
            DhKeyUtil.encodePublicKey(ephemeral.getPublic()),
            peerOneTimePreKeyPub != null && !peerOneTimePreKeyPub.isEmpty());
    }

    /**
     * 发起方会话初始化结果，含需要随首条消息带过去的公开参数。
     */
    public static final class InitiatorResult {
        private final SessionState state;
        private final String senderIdentityKey;
        private final String senderEphemeralKey;
        private final boolean usedOneTimePreKey;

        InitiatorResult(SessionState state, String senderIdentityKey, String senderEphemeralKey,
            boolean usedOneTimePreKey) {
            this.state = state;
            this.senderIdentityKey = senderIdentityKey;
            this.senderEphemeralKey = senderEphemeralKey;
            this.usedOneTimePreKey = usedOneTimePreKey;
        }

        public SessionState getState() {
            return state;
        }

        public String getSenderIdentityKey() {
            return senderIdentityKey;
        }

        public String getSenderEphemeralKey() {
            return senderEphemeralKey;
        }

        public boolean isUsedOneTimePreKey() {
            return usedOneTimePreKey;
        }
    }

    /**
     * 接收方用首条消息带来的参数完成 X3DH。
     *
     * <pre>
     *   DH1 = DH(SPK_B, IK_A)   自己签名预密钥私钥 × 对方身份公钥
     *   DH2 = DH(IK_B, EK_A)    自己身份私钥 × 对方临时公钥
     *   DH3 = DH(SPK_B, EK_A)   自己签名预密钥私钥 × 对方临时公钥
     *   DH4 = DH(OPK_B, EK_A)   自己一次性预密钥私钥 × 对方临时公钥（若对方用了）
     * </pre>
     *
     * <p>ECDH 的对称性保证与发起方算出的 DH1..DH4 完全相同。
     *
     * @param peer               对方用户名
     * @param selfIdentity       自己的身份私钥
     * @param selfIdentityPub    自己的身份公钥
     * @param selfSignedPreKey   自己签名预密钥的私钥
     * @param selfSignedPreKeyPub 自己签名预密钥的公钥（Base64）
     * @param selfOneTimePreKey  自己被用掉的那个一次性预密钥的私钥；未使用则传 null
     * @param peerIdentityPub    对方身份公钥（来自首条消息）
     * @param peerEphemeralPub   对方临时公钥（来自首条消息）
     * @param usedOneTimePreKey  对方是否用了自己的一次性预密钥
     */
    public SessionState responderSession(String peer, PrivateKey selfIdentity,
        String selfIdentityPub, PrivateKey selfSignedPreKey, String selfSignedPreKeyPub,
        PrivateKey selfOneTimePreKey, String peerIdentityPub, String peerEphemeralPub,
        boolean usedOneTimePreKey) {
        PublicKey peerIdentity = DhKeyUtil.decodePublicKey(peerIdentityPub);
        PublicKey peerEphemeral = DhKeyUtil.decodePublicKey(peerEphemeralPub);

        byte[] dh1 = DhKeyUtil.agree(selfSignedPreKey, peerIdentity);
        byte[] dh2 = DhKeyUtil.agree(selfIdentity, peerEphemeral);
        byte[] dh3 = DhKeyUtil.agree(selfSignedPreKey, peerEphemeral);
        byte[] dh4 = null;
        if (usedOneTimePreKey) {
            if (selfOneTimePreKey == null) {
                throw new IllegalStateException(
                    "对方使用了我们的一次性预密钥，但本地找不到对应私钥；这条消息无法解密");
            }
            dh4 = DhKeyUtil.agree(selfOneTimePreKey, peerEphemeral);
        }

        byte[] shared = concat(dh1, dh2, dh3, dh4);
        Hkdf.wipe(dh1);
        Hkdf.wipe(dh2);
        Hkdf.wipe(dh3);
        Hkdf.wipe(dh4);

        byte[] rootKey = Hkdf.derive(null, shared, INFO_INITIAL, KEY_BYTES);
        Hkdf.wipe(shared);

        SessionState state = new SessionState();
        state.setPeer(peer);
        state.setCreatedAt(System.currentTimeMillis());
        // 接收方初始棘轮私钥用签名预密钥，对应发起方记录的 dhRemotePublic（SPK_B）
        state.setDhSelfPrivate(Base64.getEncoder().encodeToString(selfSignedPreKey.getEncoded()));
        state.setDhSelfPublic(selfSignedPreKeyPub);
        // 留空以强制首条消息触发 DH 棘轮
        state.setDhRemotePublic(null);
        state.setRootKey(rootKey);
        state.setSendChainKey(null);
        state.setRecvChainKey(null);
        state.setSendCount(0);
        state.setRecvCount(0);
        state.setPrevSendCount(0);
        return state;
    }

    private byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            if (part != null) {
                total += part.length;
            }
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            if (part != null) {
                System.arraycopy(part, 0, result, offset, part.length);
                offset += part.length;
            }
        }
        return result;
    }

    // ==================== 加密 ====================

    /**
     * 加密一条消息，并推进发送链。
     *
     * <p>调用方必须在本方法返回后立即持久化 {@code state}，
     * 否则崩溃会导致链密钥不同步、后续消息永久无法解密。
     */
    public Encrypted encrypt(SessionState state, String plaintext) throws IdentityException {
        if (state.getSendChainKey() == null) {
            throw new IdentityException("会话尚未建立发送链，请先接收对方的第一条消息");
        }
        byte[] chainKey = state.getSendChainKey();
        byte[] messageKey = kdfChainMessageKey(chainKey);
        byte[] nextChainKey = kdfChainNextKey(chainKey);

        Header header = new Header(state.getDhSelfPublic(), state.getPrevSendCount(), state.getSendCount());

        try {
            byte[] iv = DhKeyUtil.randomBytes(GCM_IV_BYTES);
            byte[] ciphertext = AesGcm.encrypt(messageKey, iv, header.toAad(), plaintext);
            // 推进链密钥：本条消息的密钥用完即弃（前向安全的关键）
            state.setSendChainKey(nextChainKey);
            state.setSendCount(state.getSendCount() + 1);
            return new Encrypted(header, Base64.getEncoder().encodeToString(iv),
                Base64.getEncoder().encodeToString(ciphertext));
        } catch (Exception e) {
            throw new IdentityException("消息加密失败：" + e.getMessage());
        } finally {
            Hkdf.wipe(messageKey);
            Hkdf.wipe(chainKey);
        }
    }

    // ==================== 解密 ====================

    /**
     * 解密一条消息。必要时推进 DH 棘轮与接收链。
     */
    public String decrypt(SessionState state, Header header, String ivBase64, String ciphertextBase64)
        throws IdentityException {
        // 先在副本上推进棘轮。GCM 失败就丢掉副本，调用方手里的会话保持原样。
        // 否则历史里一条对不上的密文会把当前发送链一起毁掉，后面每条都解不开。
        SessionState trial = state.copy();
        String plaintext = decryptInPlace(trial, header, ivBase64, ciphertextBase64);
        state.accept(trial);
        return plaintext;
    }

    private String decryptInPlace(SessionState state, Header header, String ivBase64,
        String ciphertextBase64) throws IdentityException {
        // 1. 优先查已跳过的密钥缓存（乱序到达）
        byte[] messageKey = state.takeSkipped(header.getDhPublic(), header.getMessageNumber());
        boolean fromCache = messageKey != null;

        if (!fromCache) {
            // 2. 棘轮公钥变化 -> 推进 DH 棘轮，并把旧链剩余密钥跳过缓存
            if (!header.getDhPublic().equals(state.getDhRemotePublic())) {                skipMessageKeys(state, header.getPreviousChainLength());
                dhRatchet(state, header.getDhPublic());
            }
            skipMessageKeys(state, header.getMessageNumber());
            if (state.getRecvChainKey() == null) {
                throw new IdentityException("会话尚未建立接收链");
            }
            messageKey = kdfChainMessageKey(state.getRecvChainKey());
            byte[] nextChainKey = kdfChainNextKey(state.getRecvChainKey());
            Hkdf.wipe(state.getRecvChainKey());
            state.setRecvChainKey(nextChainKey);
            state.setRecvCount(state.getRecvCount() + 1);
        }

        try {
            byte[] iv = Base64.getDecoder().decode(ivBase64);
            byte[] ciphertext = Base64.getDecoder().decode(ciphertextBase64);
            return AesGcm.decrypt(messageKey, iv, header.toAad(), ciphertext);
        } catch (Exception e) {
            throw new IdentityException("消息解密失败：密文被篡改或密钥不匹配");
        } finally {
            Hkdf.wipe(messageKey);
        }
    }

    /**
     * 把接收链推进到 {@code until}，中间跳过的密钥缓存起来供乱序消息使用。
     */
    private void skipMessageKeys(SessionState state, int until) throws IdentityException {
        if (state.getRecvChainKey() == null) {
            return;
        }
        if (until - state.getRecvCount() > MAX_SKIP) {
            throw new IdentityException("跳过的消息过多，拒绝处理（可能是重放或攻击）");
        }
        while (state.getRecvCount() < until) {
            byte[] messageKey = kdfChainMessageKey(state.getRecvChainKey());
            state.cacheSkipped(state.getDhRemotePublic(), state.getRecvCount(), messageKey);
            byte[] nextChainKey = kdfChainNextKey(state.getRecvChainKey());
            Hkdf.wipe(messageKey);
            Hkdf.wipe(state.getRecvChainKey());
            state.setRecvChainKey(nextChainKey);
            state.setRecvCount(state.getRecvCount() + 1);
        }
    }

    /**
     * DH 棘轮：换上新收到的对端公钥，更新接收链；再生成自己的新密钥对，更新发送链。
     */
    private void dhRatchet(SessionState state, String remotePublic) throws IdentityException {
        state.setPrevSendCount(state.getSendCount());
        state.setSendCount(0);
        state.setRecvCount(0);
        state.setDhRemotePublic(remotePublic);

        PrivateKey selfPrivate = DhKeyUtil.decodePrivateKey(
            Base64.getDecoder().decode(state.getDhSelfPrivate()));

        // 接收链：用自己当前私钥 + 对端新公钥
        byte[] dhRecv = DhKeyUtil.agree(selfPrivate, remotePublic);
        DerivedKeys recvDerived = kdfRoot(state.getRootKey(), dhRecv);
        Hkdf.wipe(dhRecv);
        Hkdf.wipe(state.getRootKey());
        state.setRootKey(recvDerived.rootKey);
        if (state.getRecvChainKey() != null) {
            Hkdf.wipe(state.getRecvChainKey());
        }
        state.setRecvChainKey(recvDerived.chainKey);

        // 换自己的棘轮密钥对，建立新的发送链
        KeyPair newPair = DhKeyUtil.generateKeyPair();
        byte[] dhSend = DhKeyUtil.agree(newPair.getPrivate(), DhKeyUtil.decodePublicKey(remotePublic));
        DerivedKeys sendDerived = kdfRoot(state.getRootKey(), dhSend);
        Hkdf.wipe(dhSend);
        Hkdf.wipe(state.getRootKey());
        state.setRootKey(sendDerived.rootKey);
        if (state.getSendChainKey() != null) {
            Hkdf.wipe(state.getSendChainKey());
        }
        state.setSendChainKey(sendDerived.chainKey);
        state.setDhSelfPrivate(Base64.getEncoder().encodeToString(newPair.getPrivate().getEncoded()));
        state.setDhSelfPublic(DhKeyUtil.encodePublicKey(newPair.getPublic()));
    }

    // ==================== KDF ====================

    private static final class DerivedKeys {
        private final byte[] rootKey;
        private final byte[] chainKey;

        DerivedKeys(byte[] rootKey, byte[] chainKey) {
            this.rootKey = rootKey;
            this.chainKey = chainKey;
        }
    }

    private DerivedKeys kdfRoot(byte[] rootKey, byte[] dhOut) {
        byte[] prk = Hkdf.extract(rootKey, dhOut);
        try {
            byte[] newRoot = Hkdf.expand(prk, INFO_ROOT, KEY_BYTES);
            byte[] chain = Hkdf.expand(prk, INFO_CHAIN, KEY_BYTES);
            return new DerivedKeys(newRoot, chain);
        } finally {
            Hkdf.wipe(prk);
        }
    }

    private byte[] kdfChainMessageKey(byte[] chainKey) {
        return hmac(chainKey, CHAIN_ADVANCE_MESSAGE);
    }

    private byte[] kdfChainNextKey(byte[] chainKey) {
        return hmac(chainKey, CHAIN_ADVANCE_NEXT);
    }

    private byte[] hmac(byte[] key, byte marker) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(new byte[] {marker});
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /**
     * 计算公钥指纹，供用户线下核对。会话建立前必须核验，防止中间人替换公钥。
     */
    public String fingerprint(byte[] publicKeyBytes) {
        return Hkdf.sha256Hex(publicKeyBytes);
    }

    public String fingerprint(String publicKeyBase64) {
        return fingerprint(Base64.getDecoder().decode(publicKeyBase64));
    }

    /**
     * 清理状态中的密钥材料。会话销毁时调用。
     */
    public void destroy(SessionState state) {
        if (state == null) {
            return;
        }
        Hkdf.wipe(state.getRootKey());
        Hkdf.wipe(state.getSendChainKey());
        Hkdf.wipe(state.getRecvChainKey());
        state.getSkippedKeys().clear();
    }

    /**
     * AES-256-GCM 薄封装，把异常统一收敛成受检异常之外的运行时异常。
     */
    static final class AesGcm {

        private AesGcm() {
        }

        static byte[] encrypt(byte[] key, byte[] iv, String aad, String plaintext) throws Exception {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        }

        static String decrypt(byte[] key, byte[] iv, String aad, byte[] ciphertext) throws Exception {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        }
    }
}
