package com.junesoon.mim.utils;

import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * TOTP 验证码校验 + 防重放。
 *
 * <p>同一个时间步的验证码只能使用一次。用 Redis 的 SETNX 做原子占位，
 * 多实例、并发登录下都只有一个请求能成功。占位 TTL 覆盖前后一分钟，
 * 足够覆盖时钟偏差。
 *
 * <p>Redis 故障时放行（fail-open）：登录还要经过口令校验与 IP 限流，
 * 不能因为缓存故障让所有人无法登录；此时防重放暂时失效。
 */
@Slf4j
@Component
public class TotpVerifier {

    private static final String KEY_PREFIX = "mim:totp:used:";
    private static final long TTL_SECONDS = 90L;

    private final StringRedisTemplate redisTemplate;

    public TotpVerifier(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 校验验证码并占用对应时间步。已使用过或验证码错误返回 false。
     */
    public boolean verifyAndConsume(String username, String secretBase32, String code) {
        if (!StringUtils.hasText(username)) {
            return false;
        }
        long step = TotpUtil.matchedStep(secretBase32, code);
        if (step < 0) {
            return false;
        }
        try {
            Boolean fresh = redisTemplate.opsForValue().setIfAbsent(
                KEY_PREFIX + username + ":" + step, "1", TTL_SECONDS, TimeUnit.SECONDS);
            return fresh == null || fresh.booleanValue();
        } catch (Exception e) {
            log.warn("TOTP 防重放检查失败，已放行：{}", e.getMessage());
            return true;
        }
    }
}
