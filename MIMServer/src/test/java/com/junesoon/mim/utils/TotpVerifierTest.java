package com.junesoon.mim.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * TOTP 防重放测试：同一个时间步只能被成功占用一次。
 */
class TotpVerifierTest {

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("同一验证码第二次使用被拒绝")
    void secondUseRejected() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);

        String secret = TotpUtil.generateSecret();
        String code = TotpUtil.codeAt(secret, System.currentTimeMillis() / 1000L);
        TotpVerifier verifier = new TotpVerifier(redis);

        when(operations.setIfAbsent(anyString(), eq("1"), eq(90L), eq(TimeUnit.SECONDS)))
            .thenReturn(true);
        assertTrue(verifier.verifyAndConsume("alice", secret, code));

        when(operations.setIfAbsent(anyString(), eq("1"), eq(90L), eq(TimeUnit.SECONDS)))
            .thenReturn(false);
        assertFalse(verifier.verifyAndConsume("alice", secret, code));
    }

    @Test
    @DisplayName("错误格式的验证码不占用 Redis 计数")
    void invalidCodeRejected() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        TotpVerifier verifier = new TotpVerifier(redis);
        assertFalse(verifier.verifyAndConsume("alice", TotpUtil.generateSecret(), "abc"));
    }
}
