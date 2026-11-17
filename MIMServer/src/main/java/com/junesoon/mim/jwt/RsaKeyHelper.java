package com.junesoon.mim.jwt;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * RSA 密钥工具。只使用 JDK 标准 API，避免 sun.misc / com.sun.* 内部类。
 */
public final class RsaKeyHelper {

    public static final int KEY_SIZE = 2048;

    private static final String KEY_ALGORITHM = "RSA";

    private RsaKeyHelper() {
    }

    public static PublicKey getPublicKey(byte[] publicKey) throws Exception {
        X509EncodedKeySpec spec = new X509EncodedKeySpec(publicKey);
        KeyFactory kf = KeyFactory.getInstance(KEY_ALGORITHM);
        return kf.generatePublic(spec);
    }

    public static PrivateKey getPrivateKey(byte[] privateKey) throws Exception {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(privateKey);
        KeyFactory kf = KeyFactory.getInstance(KEY_ALGORITHM);
        return kf.generatePrivate(spec);
    }

    /**
     * 生成 RSA 2048 密钥对。
     */
    public static Map<String, byte[]> generateKey() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        keyPairGenerator.initialize(KEY_SIZE, new SecureRandom());
        KeyPair keyPair = keyPairGenerator.genKeyPair();
        Map<String, byte[]> map = new HashMap<>(2);
        map.put("pub", keyPair.getPublic().getEncoded());
        map.put("pri", keyPair.getPrivate().getEncoded());
        return map;
    }

    /**
     * 返回公钥的模长（bit）。用于识别历史遗留的 1024 位弱密钥。
     */
    public static int keySize(byte[] publicKey) {
        try {
            PublicKey key = getPublicKey(publicKey);
            if (key instanceof RSAPublicKey) {
                return ((RSAPublicKey) key).getModulus().bitLength();
            }
        } catch (Exception ignored) {
            // 无法解析时按 0 处理，调用方会重新生成
        }
        return 0;
    }

    public static String encode(byte[] key) {
        return Base64.getEncoder().encodeToString(key);
    }

    public static byte[] decode(String key) {
        if (key == null) {
            return null;
        }
        // 兼容历史数据：SQL 中曾经带换行/空白字符
        return Base64.getMimeDecoder().decode(key);
    }
}
