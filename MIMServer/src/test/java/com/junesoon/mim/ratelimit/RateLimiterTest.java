package com.junesoon.mim.ratelimit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.constant.CommonConstants;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 限流规则测试。
 *
 * <p>只验证规则本身：计数、阈值、按主体隔离、Redis 故障降级。
 * 因为计数被抽象成 {@link RateLimitCounter}，这里用一个内存实现即可，
 * 无需启动 Redis 或 Web 容器。
 */
class RateLimiterTest {

    /**
     * 内存计数器：模拟 Redis INCR 的原子自增语义。
     */
    private static final class InMemoryCounter implements RateLimitCounter {
        final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
        boolean broken;

        @Override
        public long incrementAndGet(String key, int windowSeconds) {
            if (broken) {
                throw new IllegalStateException("Redis 不可用");
            }
            return counters.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    private InMemoryCounter counter;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        counter = new InMemoryCounter();
        limiter = new RateLimiter(counter);
    }

    @Test
    @DisplayName("未超阈值时全部放行")
    void underLimitAllowed() {
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("u:alice", "/message", 5, 60));
        }
    }

    @Test
    @DisplayName("超过阈值时抛限流异常")
    void overLimitRejected() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("u:alice", "/message", 5, 60);
        }
        RateLimitExceededException ex = assertThrows(RateLimitExceededException.class,
            () -> limiter.tryAcquire("u:alice", "/message", 5, 60));
        assertTrue(ex.getMessage().contains("60"), "提示应包含窗口秒数");
    }

    @Test
    @DisplayName("不同主体独立计数，互不影响")
    void subjectIsolation() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("u:alice", "/message", 5, 60);
        }
        assertThrows(RateLimitExceededException.class,
            () -> limiter.tryAcquire("u:alice", "/message", 5, 60));
        // bob 有独立额度
        assertDoesNotThrow(() -> limiter.tryAcquire("u:bob", "/message", 5, 60));
    }

    @Test
    @DisplayName("不同接口独立计数")
    void pathIsolation() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("u:alice", "/message", 5, 60);
        }
        assertThrows(RateLimitExceededException.class,
            () -> limiter.tryAcquire("u:alice", "/message", 5, 60));
        // 另一个接口不受影响
        assertDoesNotThrow(() -> limiter.tryAcquire("u:alice", "/key", 5, 60));
    }

    @Test
    @DisplayName("Redis 故障时 fail-open：放行且不抛异常")
    void failOpenOnCounterError() {
        counter.broken = true;
        assertDoesNotThrow(() -> limiter.tryAcquire("u:alice", "/message", 5, 60),
            "计数组件故障不应阻断业务");
        assertTrue(limiter.tryAcquire("u:alice", "/message", 5, 60));
    }

    @Test
    @DisplayName("故障恢复后计数从零开始重新判定")
    void recoversAfterError() {
        counter.broken = true;
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("u:alice", "/message", 3, 60);
        }
        counter.broken = false;
        // 故障期间没有计数，恢复后仍可正常使用完整额度
        assertTrue(limiter.tryAcquire("u:alice", "/message", 3, 60));
        assertTrue(limiter.tryAcquire("u:alice", "/message", 3, 60));
        assertTrue(limiter.tryAcquire("u:alice", "/message", 3, 60));
        assertThrows(RateLimitExceededException.class,
            () -> limiter.tryAcquire("u:alice", "/message", 3, 60));
    }

    @Test
    @DisplayName("限流异常携带 42901 业务码，便于客户端识别")
    void exceptionStatusCode() {
        for (int i = 0; i < 2; i++) {
            limiter.tryAcquire("ip:1.2.3.4", "/login/token", 2, 60);
        }
        RateLimitExceededException ex = assertThrows(RateLimitExceededException.class,
            () -> limiter.tryAcquire("ip:1.2.3.4", "/login/token", 2, 60));
        assertEquals(CommonConstants.EX_RATE_LIMIT_CODE, ex.getStatus());
    }

    @Test
    @DisplayName("计数键包含主体与路径，避免不同维度串号")
    void keyFormat() {
        assertEquals("mim:ratelimit:u:alice:/message", RateLimiter.keyOf("u:alice", "/message"));
        assertEquals("mim:ratelimit:ip:1.2.3.4:/login/token",
            RateLimiter.keyOf("ip:1.2.3.4", "/login/token"));
    }
}
