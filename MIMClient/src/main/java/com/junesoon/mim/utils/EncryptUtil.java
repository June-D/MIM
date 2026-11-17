package com.junesoon.mim.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 通用摘要/编码工具。
 *
 * <p>原先这里用 {@code com.sun.org.apache.xerces.internal.impl.dv.util.Base64}
 * 这个 JDK 内部类，Java 9+ 会直接不可用；同时提供了 AES/ECB 与 XOR 等
 * 「看起来像加密」的方法，容易诱导误用。现在只保留确实需要的单向摘要与编码，
 * 真正的内容加密统一走 {@code com.junesoon.mim.crypto.E2eeService}。
 */
public final class EncryptUtil {

    private EncryptUtil() {
    }

    public static String sha256Base64(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static String hmacSha256Base64(String text, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }

    /**
     * 标准 Base64 编码，用于把口令做一次传输编码（不是加密，也不是哈希；
     * 真正的口令保护依赖服务端 BCrypt 与传输层 TLS）。
     */
    public static String base64Encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String base64Decode(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    /**
     * 二进制转十六进制大写。
     */
    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
