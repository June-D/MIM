package com.junesoon.mim.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TOTP / Base32 测试，使用 RFC 6238 的 SHA1 测试向量。
 */
class TotpUtilTest {

    private static final String RFC_SECRET = TotpUtil.base32Encode(
        "12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    @DisplayName("RFC 6238 SHA1 测试向量（6 位）")
    void rfc6238Vectors() {
        assertEquals("287082", TotpUtil.codeAt(RFC_SECRET, 59L));
        assertEquals("081804", TotpUtil.codeAt(RFC_SECRET, 1111111109L));
        assertEquals("050471", TotpUtil.codeAt(RFC_SECRET, 1111111111L));
        assertEquals("005924", TotpUtil.codeAt(RFC_SECRET, 1234567890L));
    }

    @Test
    @DisplayName("Base32 编解码可往返")
    void base32RoundTrip() {
        byte[] raw = "hello-totp-secret".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(raw, TotpUtil.base32Decode(TotpUtil.base32Encode(raw)));
    }

    @Test
    @DisplayName("当前验证码可通过，窗口外验证码被拒绝")
    void verifyWindow() {
        String secret = TotpUtil.generateSecret();
        long now = System.currentTimeMillis() / 1000L;
        String current = TotpUtil.codeAt(secret, now);
        assertTrue(TotpUtil.verify(secret, current));
        assertFalse(TotpUtil.verify(secret, TotpUtil.codeAt(secret, now - 120L)),
            "超过一个时间步的旧验证码必须被拒绝");
        assertFalse(TotpUtil.verify(secret, "abc123"));
        assertFalse(TotpUtil.verify(secret, null));
    }

    @Test
    @DisplayName("otpauth URI 带 issuer 与算法参数")
    void otpauthUri() {
        String uri = TotpUtil.otpauthUri("alice", "ABCDEF");
        assertTrue(uri.startsWith("otpauth://totp/MIM:alice?"));
        assertTrue(uri.contains("secret=ABCDEF"));
        assertTrue(uri.contains("issuer=MIM"));
        assertTrue(uri.contains("digits=6"));
    }
}
