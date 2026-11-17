package com.junesoon.mim.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 限流规则执行器：只做判定，不关心请求从哪来。
 *
 * <p>与 {@link RateLimitAspect} 的分工：
 * <ul>
 *   <li>Aspect 负责从 HTTP 请求/AOP 上下文里提取「限流主体」（用户名或 IP）；</li>
 *   <li>本类负责「计数 + 阈值判定 + 失败降级」，是纯逻辑，可以脱离 Web 容器测试。</li>
 * </ul>
 */
@Slf4j
@Component
public class RateLimiter {

    private static final String KEY_PREFIX = "mim:ratelimit:";

    private final RateLimitCounter counter;

    public RateLimiter(RateLimitCounter counter) {
        this.counter = counter;
    }

    /**
     * 校验一次请求是否允许通过。
     *
     * @param subject 限流主体，形如 {@code u:alice} 或 {@code ip:1.2.3.4}
     * @param path    请求路径，用于区分不同接口的额度
     * @return true 表示允许通过
     * @throws RateLimitExceededException 超限时抛出
     */
    public boolean tryAcquire(String subject, String path, int limit, int windowSeconds) {
        String key = KEY_PREFIX + subject + ":" + path;
        try {
            long count = counter.incrementAndGet(key, windowSeconds);
            if (count > limit) {
                log.warn("触发限流 subject={} path={} count={} limit={}", subject, path, count, limit);
                throw new RateLimitExceededException(
                    "请求过于频繁，请 " + windowSeconds + " 秒后重试");
            }
            return true;
        } catch (RateLimitExceededException e) {
            throw e;
        } catch (Exception e) {
            // fail-open：限流是保护措施而非业务正确性，
            // 让限流组件故障阻断正常聊天是更糟的选择。
            log.warn("限流检查失败，已放行：{}", e.getMessage());
            return true;
        }
    }

    /**
     * 构造计数键，暴露给测试与排查使用。
     */
    public static String keyOf(String subject, String path) {
        return KEY_PREFIX + subject + ":" + path;
    }
}
