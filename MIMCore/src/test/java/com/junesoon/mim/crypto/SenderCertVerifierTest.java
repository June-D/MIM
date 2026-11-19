package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.model.SenderCertificateView;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 发送者证书验签测试。
 *
 * <p>证书是首条消息归属的唯一信任来源，验签必须对篡改、过期、
 * 密钥不匹配全部失败。
 *
 * [INPUT]: 只依赖 JDK RSA 与证书模型
 * [OUTPUT]: 验证正常通过、字段篡改、过期、keyId 不符与错误公钥
 * [POS]: 首条消息解密路径的信任检查
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
class SenderCertVerifierTest {

    private KeyPair serverKeyPair;
    private String serverPublicKey;
    private String keyId;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        serverKeyPair = generator.generateKeyPair();
        serverPublicKey = Base64.getEncoder().encodeToString(
            serverKeyPair.getPublic().getEncoded());
        keyId = sha256Hex(serverKeyPair.getPublic().getEncoded());
    }

    private SenderCertificateView certificate(long issuedAt, long expiresAt) throws Exception {
        SenderCertificateView cert = new SenderCertificateView();
        cert.setUsername("alice");
        cert.setDeviceId("alice-desktop");
        cert.setIdentityKey("QUJDREVGRw==");
        cert.setIssuedAt(issuedAt);
        cert.setExpiresAt(expiresAt);
        cert.setKeyId(keyId);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(serverKeyPair.getPrivate());
        signer.update(cert.canonical().getBytes(StandardCharsets.UTF_8));
        cert.setSignature(Base64.getEncoder().encodeToString(signer.sign()));
        return cert;
    }

    @Test
    @DisplayName("服务端签发的证书可以用分发的公钥验证")
    void validCertificateAccepted() throws Exception {
        long now = System.currentTimeMillis();
        SenderCertificateView cert = certificate(now, now + 3_600_000L);
        assertTrue(SenderCertVerifier.verifyAt(cert, serverPublicKey, now));
    }

    @Test
    @DisplayName("篡改用户名、设备或身份公钥都会验签失败")
    void tamperedFieldsRejected() throws Exception {
        long now = System.currentTimeMillis();
        SenderCertificateView cert = certificate(now, now + 3_600_000L);

        cert.setUsername("bob");
        assertFalse(SenderCertVerifier.verifyAt(cert, serverPublicKey, now));

        cert.setUsername("alice");
        cert.setDeviceId("alice-laptop");
        assertFalse(SenderCertVerifier.verifyAt(cert, serverPublicKey, now));

        cert.setDeviceId("alice-desktop");
        cert.setIdentityKey("Qk9H");
        assertFalse(SenderCertVerifier.verifyAt(cert, serverPublicKey, now));
    }

    @Test
    @DisplayName("过期证书被拒绝，时钟偏差内仍接受")
    void expiryEnforced() throws Exception {
        long issuedAt = 1_700_000_000_000L;
        SenderCertificateView cert = certificate(issuedAt, issuedAt + 60_000L);
        assertFalse(SenderCertVerifier.verifyAt(cert, serverPublicKey, issuedAt + 600_000L));
        assertTrue(SenderCertVerifier.verifyAt(cert, serverPublicKey, issuedAt + 60_000L + 1_000L),
            "5 分钟偏差内不应判成过期");
        assertFalse(SenderCertVerifier.verifyAt(cert, serverPublicKey, issuedAt - 600_000L),
            "签发时间远晚于当前时间也应拒绝");
    }

    @Test
    @DisplayName("keyId 与公钥不一致、或换用别的公钥都失败")
    void keyMismatchRejected() throws Exception {
        long now = System.currentTimeMillis();
        SenderCertificateView cert = certificate(now, now + 3_600_000L);

        cert.setKeyId("ffffffffffffffffffffffffffffffff"
            + "ffffffffffffffffffffffffffffffff");
        assertFalse(SenderCertVerifier.verifyAt(cert, serverPublicKey, now),
            "keyId 必须是公钥指纹");

        SenderCertificateView valid = certificate(now, now + 3_600_000L);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String otherKey = Base64.getEncoder().encodeToString(
            generator.generateKeyPair().getPublic().getEncoded());
        assertFalse(SenderCertVerifier.verifyAt(valid, otherKey, now),
            "换公钥必须失败");
    }

    @Test
    @DisplayName("证书身份公钥必须与握手参数一致")
    void handshakeKeyBound() throws Exception {
        long now = System.currentTimeMillis();
        SenderCertificateView cert = certificate(now, now + 3_600_000L);
        assertTrue(SenderCertVerifier.matchesHandshake(cert, "QUJDREVGRw=="));
        assertFalse(SenderCertVerifier.matchesHandshake(cert, "OTHER"));
        assertFalse(SenderCertVerifier.matchesHandshake(null, "QUJDREVGRw=="));
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
