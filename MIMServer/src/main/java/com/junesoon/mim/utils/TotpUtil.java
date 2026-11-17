package com.junesoon.mim.utils;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP（RFC 6238，SHA1 / 6 位 / 30 秒）与 Base32 编解码。
 *
 * <p>不引入额外依赖：只依赖 JDK 的 HMAC-SHA1，Base32 按 RFC 4648 手写。
 * 允许前后各一个时间步的时钟偏差；验证码比较使用常量时间比较。
 */
public final class TotpUtil {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int SECRET_BYTES = 20;
    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int WINDOW = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpUtil() {
    }

    /**
     * 生成 160 位随机共享密钥（Base32，无填充）。
     */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /**
     * 生成可导入身份验证器的 otpauth URI。
     */
    public static String otpauthUri(String username, String secret) {
        String label = "MIM:" + username;
        try {
            label = URLEncoder.encode(label, "UTF-8")
                .replace("+", "%20")
                .replace("%3A", ":");
        } catch (Exception ignored) {
            // UTF-8 必然存在
        }
        return "otpauth://totp/" + label + "?secret=" + secret
            + "&issuer=MIM&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /**
     * 校验验证码，允许前后各一个时间步的偏差。
     */
    public static boolean verify(String secretBase32, String code) {
        if (secretBase32 == null || code == null) {
            return false;
        }
        String normalized = code.trim();
        if (!normalized.matches("\\d{" + DIGITS + "}")) {
            return false;
        }
        try {
            byte[] key = base32Decode(secretBase32);
            long counter = System.currentTimeMillis() / 1000L / STEP_SECONDS;
            byte[] expectedBytes = normalized.getBytes(StandardCharsets.US_ASCII);
            for (long offset = -WINDOW; offset <= WINDOW; offset++) {
                String expected = code(key, counter + offset);
                if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    expectedBytes)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 按时间戳生成验证码，供测试与排查使用。
     */
    public static String codeAt(String secretBase32, long epochSeconds) {
        return code(base32Decode(secretBase32), epochSeconds / STEP_SECONDS);
    }

    static String code(byte[] key, long counter) {
        try {
            byte[] data = new byte[8];
            long value = counter;
            for (int i = 7; i >= 0; i--) {
                data[i] = (byte) (value & 0xff);
                value >>>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
            int otp = binary % 1_000_000;
            return String.format(Locale.ROOT, "%06d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP 计算失败", e);
        }
    }

    public static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return sb.toString();
    }

    public static byte[] base32Decode(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Base32 为空");
        }
        String normalized = value.trim().replace("=", "").replace(" ", "")
            .toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream(normalized.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < normalized.length(); i++) {
            int index = ALPHABET.indexOf(normalized.charAt(i));
            if (index < 0) {
                throw new IllegalArgumentException("非法的 Base32 字符");
            }
            buffer = (buffer << 5) | index;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
