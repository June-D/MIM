package com.junesoon.mim.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF（RFC 5869），基于 HMAC-SHA256。
 *
 * <p>JDK 8 没有内置 HKDF，这里手写实现。用途是从 ECDH 共享密钥派生出
 * 独立的会话密钥与 AAD 绑定材料，避免把裸的共享密钥直接当 AES 密钥用。
 */
public final class Hkdf {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private Hkdf() {
    }

    /**
     * 提取阶段：把输入密钥材料压成固定长度伪随机密钥。
     */
    public static byte[] extract(byte[] salt, byte[] ikm) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            // salt 为空时按 RFC 5869 用全零
            byte[] actualSalt = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
            mac.init(new SecretKeySpec(actualSalt, HMAC_ALGORITHM));
            return mac.doFinal(ikm);
        } catch (Exception e) {
            throw new IllegalStateException("HKDF-Extract 失败", e);
        }
    }

    /**
     * 扩展阶段：从伪随机密钥派生出指定长度的输出。
     *
     * @param prk  伪随机密钥（extract 的结果）
     * @param info 上下文绑定信息；不同 info 派生出互不相关的密钥
     * @param len  需要的字节数，上限 255 * 32
     */
    public static byte[] expand(byte[] prk, String info, int len) {
        if (len <= 0 || len > 255 * HASH_LEN) {
            throw new IllegalArgumentException("HKDF 输出长度不合法: " + len);
        }
        byte[] infoBytes = info == null ? new byte[0] : info.getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(prk, HMAC_ALGORITHM));
            byte[] output = new byte[len];
            byte[] previous = new byte[0];
            int offset = 0;
            byte counter = 1;
            while (offset < len) {
                mac.reset();
                mac.update(previous);
                mac.update(infoBytes);
                mac.update(counter);
                previous = mac.doFinal();
                int copyLen = Math.min(previous.length, len - offset);
                System.arraycopy(previous, 0, output, offset, copyLen);
                offset += copyLen;
                counter++;
            }
            return output;
        } catch (Exception e) {
            throw new IllegalStateException("HKDF-Expand 失败", e);
        }
    }

    /**
     * 完整 HKDF：extract 后立即 expand。
     */
    public static byte[] derive(byte[] salt, byte[] ikm, String info, int len) {
        return expand(extract(salt, ikm), info, len);
    }

    /**
     * 清零敏感字节数组，尽量缩短密钥在堆上的生命周期。
     */
    public static void wipe(byte[] secret) {
        if (secret != null) {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /**
     * 计算任意字节的 SHA-256 十六进制摘要，用于公钥指纹。
     */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
