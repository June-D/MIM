package com.junesoon.mim.ratelimit;

import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的计数实现。
 *
 * <p>为什么必须是 Redis 而不是 JVM 内存：服务是分布式多实例部署，同一用户的连续请求
 * 会被负载均衡打到不同 Pod，进程内计数只能限到「单实例的 1/N」，起不到保护作用。
 *
 * <p>固定窗口（INCR + EXPIRE）而非令牌桶：实现简单，对「防刷」足够。
 * 代价是窗口边界可能瞬时放过 2 倍流量，IM 场景可接受。要更精确可换 Lua 滑动窗口。
 */
@Slf4j
@Component
public class RedisRateLimitCounter implements RateLimitCounter {

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimitCounter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public long incrementAndGet(String key, int windowSeconds) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            throw new IllegalStateException("Redis INCR 返回空值");
        }
        // 只在首次计数时设过期，避免每次请求都刷新 TTL 导致窗口永不过期
        if (count == 1L) {
            redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
        }
        return count;
    }
}
