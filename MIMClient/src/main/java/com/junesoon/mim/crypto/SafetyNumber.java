package com.junesoon.mim.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;

/**
 * 双方可对照的 60 位安全码。
 *
 * <p>把双方身份公钥按字节序排序后做 SHA-256，取十进制模 10^60，因此
 * A 用「自己的公钥 + B 的设备公钥」与 B 用「自己的公钥 + A 的设备公钥」
 * 会得到同一个码。它是 TOFU 首次联系的线下核对手段，不替代指纹。
 */
public final class SafetyNumber {

    private static final int DIGITS = 60;
    private static final int GROUP = 5;

    private SafetyNumber() {
    }

    public static String of(String ownPublicKeyBase64, String peerPublicKeyBase64) {
        byte[] own = decode(ownPublicKeyBase64);
        byte[] peer = decode(peerPublicKeyBase64);
        byte[] first = own;
        byte[] second = peer;
        if (compare(own, peer) > 0) {
            first = peer;
            second = own;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(first);
            digest.update(second);
            BigInteger value = new BigInteger(1, digest.digest());
            String digits = String.format(Locale.ROOT, "%0" + DIGITS + "d",
                value.mod(BigInteger.TEN.pow(DIGITS)));
            StringBuilder result = new StringBuilder(DIGITS + DIGITS / GROUP + 2);
            for (int i = 0; i < DIGITS; i += GROUP) {
                if (i > 0) {
                    result.append(i % 30 == 0 ? '\n' : ' ');
                }
                result.append(digits, i, i + GROUP);
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算安全码", e);
        }
    }

    private static byte[] decode(String base64) {
        if (base64 == null || base64.trim().isEmpty()) {
            throw new IllegalArgumentException("公钥不能为空");
        }
        byte[] bytes = Base64.getDecoder().decode(base64.trim());
        if (bytes.length == 0) {
            throw new IllegalArgumentException("公钥不能为空");
        }
        return bytes;
    }

    private static int compare(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int a = left[i] & 0xff;
            int b = right[i] & 0xff;
            if (a != b) {
                return a - b;
            }
        }
        return left.length - right.length;
    }
}
