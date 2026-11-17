package com.junesoon.mim.crypto;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.KeyAgreement;

/**
 * P-256 (secp256r1) 密钥工具：生成、编解码、ECDH、ECDSA 签名/验签。
 *
 * <p>双棘轮需要频繁生成临时密钥对与做 DH 运算，集中在这里避免各处重复实现。
 */
public final class DhKeyUtil {

    private static final String CURVE = "secp256r1";
    private static final String KEY_ALGORITHM = "EC";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

    private static final SecureRandom RANDOM = new SecureRandom();

    private DhKeyUtil() {
    }

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            generator.initialize(new ECGenParameterSpec(CURVE), RANDOM);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("生成 P-256 密钥对失败", e);
        }
    }

    public static PublicKey decodePublicKey(String base64) {
        try {
            return KeyFactory.getInstance(KEY_ALGORITHM)
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (Exception e) {
            throw new IllegalArgumentException("公钥解析失败", e);
        }
    }

    public static PrivateKey decodePrivateKey(byte[] pkcs8) {
        try {
            return KeyFactory.getInstance(KEY_ALGORITHM)
                .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception e) {
            throw new IllegalArgumentException("私钥解析失败", e);
        }
    }

    public static String encodePublicKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /**
     * ECDH：用自己的私钥与对方公钥算出共享点。
     */
    public static byte[] agree(PrivateKey localPrivate, PublicKey remotePublic) {
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(localPrivate);
            agreement.doPhase(remotePublic, true);
            return agreement.generateSecret();
        } catch (Exception e) {
            throw new IllegalStateException("ECDH 协商失败", e);
        }
    }

    public static byte[] agree(PrivateKey localPrivate, String remotePublicBase64) {
        return agree(localPrivate, decodePublicKey(remotePublicBase64));
    }

    public static String sign(PrivateKey privateKey, byte[] data) {
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(data);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("签名失败", e);
        }
    }

    public static boolean verify(PublicKey publicKey, byte[] data, String signatureBase64) {
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(data);
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 生成指定长度的随机字节。
     */
    public static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * 构造签名预密钥的签名内容：{@code preKeyId || publicKeyBytes}。
     *
     * <p>把 id 一起签进去，防止攻击者把同一把预密钥挂到不同 id 上做重放/混淆。
     */
    public static byte[] signedPreKeyPayload(int preKeyId, byte[] publicKeyBytes) {
        byte[] idBytes = String.valueOf(preKeyId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] payload = new byte[idBytes.length + 1 + publicKeyBytes.length];
        System.arraycopy(idBytes, 0, payload, 0, idBytes.length);
        payload[idBytes.length] = '|';
        System.arraycopy(publicKeyBytes, 0, payload, idBytes.length + 1, publicKeyBytes.length);
        return payload;
    }

    /**
     * 验证预密钥签名。这是抗中间人的核心检查：
     * 接收方用<b>已固定的身份公钥</b>验证签名，服务端无法伪造。
     */
    public static boolean verifySignedPreKey(String identityPublicKeyBase64, int preKeyId,
        String signedPreKeyPublicBase64, String signatureBase64) {
        try {
            PublicKey identity = decodePublicKey(identityPublicKeyBase64);
            byte[] preKeyBytes = Base64.getDecoder().decode(signedPreKeyPublicBase64);
            byte[] payload = signedPreKeyPayload(preKeyId, preKeyBytes);
            return verify(identity, payload, signatureBase64);
        } catch (Exception e) {
            return false;
        }
    }
}
