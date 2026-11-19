package com.junesoon.mim.crypto;

import com.junesoon.mim.model.SenderCertificateView;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 发送者证书验签。
 *
 * <p>证书只证明「服务端认定这条消息来自 username/deviceId」，不提供
 * 提交匿名。验证通过后仍要按 TOFU 规则校验该设备的身份公钥，
 * 两者都通过才可以使用证书里的归属。
 *
 * <p>时钟偏差容忍 5 分钟：客户端时间偏快时不会把刚签发的证书判成过期。
 *
 * [INPUT]: 依赖 SenderCertificateView 与服务端证书公钥（Base64 X.509）
 * [OUTPUT]: verify/verifyAt 两个静态方法
 * [POS]: MessageService 首条消息解密路径；不接触网络与存储
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
public final class SenderCertVerifier {

    private static final long CLOCK_SKEW_MS = 5L * 60 * 1000;

    private SenderCertVerifier() {
    }

    public static boolean verify(SenderCertificateView certificate, String publicKeyBase64) {
        return verifyAt(certificate, publicKeyBase64, System.currentTimeMillis());
    }

    public static boolean verifyAt(SenderCertificateView certificate, String publicKeyBase64,
        long now) {
        if (certificate == null || publicKeyBase64 == null || certificate.getSignature() == null
            || certificate.getKeyId() == null || certificate.getIdentityKey() == null
            || certificate.getUsername() == null || certificate.getDeviceId() == null
            || certificate.getIssuedAt() == null || certificate.getExpiresAt() == null) {
            return false;
        }
        if (certificate.getExpiresAt() + CLOCK_SKEW_MS < now
            || certificate.getIssuedAt() - CLOCK_SKEW_MS > now) {
            return false;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
            // keyId 必须等于公钥指纹，避免「签名有效但 keyId 指向别的密钥」的混淆
            if (!certificate.getKeyId().equals(sha256Hex(keyBytes))) {
                return false;
            }
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(keyBytes));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(certificate.canonical().getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(certificate.getSignature()));
        } catch (Exception e) {
            // 任何解析/验签异常都按不可信处理，绝不降级为「信任」
            return false;
        }
    }

    /**
     * 证书里的身份公钥必须与 X3DH 握手参数里的一致，否则拒绝这条消息。
     */
    public static boolean matchesHandshake(SenderCertificateView certificate,
        String handshakeIdentityKey) {
        return certificate != null && certificate.getIdentityKey() != null
            && certificate.getIdentityKey().equals(handshakeIdentityKey);
    }

    private static String sha256Hex(byte[] value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }
}
