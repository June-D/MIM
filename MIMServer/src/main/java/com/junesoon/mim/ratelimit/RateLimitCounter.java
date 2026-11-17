package com.junesoon.mim.ratelimit;

/**
 * 限流计数的抽象。
 *
 * <p>把「计数」这个唯一的副作用抽成接口，好处是：
 * <ul>
 *   <li>限流规则本身（阈值判定、时间窗口）可以脱离 Redis 与 Spring 单独测试；</li>
 *   <li>切换存储（Redis → 本地令牌桶 → 集中式限流服务）不影响业务规则；</li>
 *   <li>生产实现只有 Redis 一个，测试实现是内存计数器，两者行为一致。</li>
 * </ul>
 */
public interface RateLimitCounter {

    /**
     * 对指定 key 自增计数，并在首次计数时设置过期时间。
     *
     * @param key           计数键
     * @param windowSeconds 窗口长度（秒）
     * @return 自增后的当前计数
     */
    long incrementAndGet(String key, int windowSeconds);
}
