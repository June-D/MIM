package com.junesoon.mim.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SecurityStartupCheckerTest {

    private static SecurityStartupChecker checker(String host, String password, boolean ssl,
        boolean required) {
        SecurityStartupChecker checker = new SecurityStartupChecker();
        ReflectionTestUtils.setField(checker, "redisHost", host);
        ReflectionTestUtils.setField(checker, "redisPassword", password);
        ReflectionTestUtils.setField(checker, "redisSsl", ssl);
        ReflectionTestUtils.setField(checker, "requireSecureRedis", required);
        ReflectionTestUtils.setField(checker, "tlsEnabled", true);
        ReflectionTestUtils.setField(checker, "blockPlaintext", true);
        ReflectionTestUtils.setField(checker, "emailKey", "configured");
        ReflectionTestUtils.setField(checker, "requireDeviceBinding", true);
        return checker;
    }

    @Test
    @DisplayName("本机 Redis 不强制密码/TLS")
    void loopbackAllowed() {
        assertTrue(SecurityStartupChecker.isLoopback("127.0.0.1"));
        assertTrue(SecurityStartupChecker.isLoopback("localhost"));
        assertTrue(SecurityStartupChecker.isLoopback("::1"));
        assertFalse(SecurityStartupChecker.isLoopback("10.0.0.5"));

        assertDoesNotThrow(() -> checker("127.0.0.1", "", false, true).run());
    }

    @Test
    @DisplayName("非本机 Redis 缺密码或 TLS 时，require-secure 拒绝启动")
    void remoteRedisMustBeSecure() {
        assertThrows(IllegalStateException.class,
            () -> checker("10.0.0.5", "", true, true).run());
        assertThrows(IllegalStateException.class,
            () -> checker("10.0.0.5", "secret", false, true).run());
        assertDoesNotThrow(() -> checker("10.0.0.5", "secret", true, true).run());
    }
}
