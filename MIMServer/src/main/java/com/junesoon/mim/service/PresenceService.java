package com.junesoon.mim.service;

import com.junesoon.mim.websocket.SessionRegistry;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 在线状态。
 *
 * <p>多实例部署下"在线"必须是全局视图，因此以 Redis 为准：
 * <ul>
 *   <li>{@code mim:presence:<username>} 是一个 Set，记录该用户仍有连接的实例标识；</li>
 *   <li>集合带 TTL，客户端心跳周期续期，进程崩溃后靠 TTL 自动摘除，不会永久"在线"。</li>
 * </ul>
 * Redis 不可用时降级为「只看本实例」，并打告警日志，不阻断主流程。
 */
@Slf4j
@Service
public class PresenceService {

    private static final String KEY_PREFIX = "mim:presence:";
    private static final long TTL_SECONDS = 90;

    private final StringRedisTemplate redisTemplate;
    private final SessionRegistry sessionRegistry;

    /**
     * 实例标识。同一 Pod 内固定，用于在 Redis 集合里区分不同实例。
     */
    private final String instanceId;

    public PresenceService(StringRedisTemplate redisTemplate, SessionRegistry sessionRegistry,
        @Value("${mim.instance-id:}") String configuredInstanceId) {
        this.redisTemplate = redisTemplate;
        this.sessionRegistry = sessionRegistry;
        this.instanceId = (configuredInstanceId == null || configuredInstanceId.trim().isEmpty())
            ? UUID.randomUUID().toString().substring(0, 8)
            : configuredInstanceId.trim();
    }

    public String instanceId() {
        return instanceId;
    }

    /**
     * 标记用户在本实例上线，并续期 TTL。
     */
    public void markOnline(String username) {
        try {
            String key = key(username);
            redisTemplate.opsForSet().add(key, instanceId);
            redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis 不可用，在线状态降级为单实例视图：{}", e.getMessage());
        }
    }

    /**
     * 心跳续期，与 {@link #markOnline(String)} 等价（语义区分）。
     */
    public void touch(String username) {
        markOnline(username);
    }

    /**
     * 摘除本实例的在线标记。
     *
     * @return true 表示该用户已全局离线
     */
    public boolean markOffline(String username) {
        try {
            String key = key(username);
            redisTemplate.opsForSet().remove(key, instanceId);
            Long size = redisTemplate.opsForSet().size(key);
            if (size == null || size == 0L) {
                redisTemplate.delete(key);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("Redis 不可用，离线判定退化为本实例：{}", e.getMessage());
            return !sessionRegistry.hasLocalSession(username);
        }
    }

    public boolean isOnline(String username) {
        if (sessionRegistry.hasLocalSession(username)) {
            return true;
        }
        try {
            Long size = redisTemplate.opsForSet().size(key(username));
            return size != null && size > 0L;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 批量过滤出在线用户。
     */
    public Set<String> filterOnline(Collection<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> online = new HashSet<>();
        for (String username : usernames) {
            if (sessionRegistry.hasLocalSession(username)) {
                online.add(username);
            }
        }
        // 联系人数量在几十的量级，逐个判断可接受；Redis 故障时退化为本实例视图
        for (String username : usernames) {
            if (online.contains(username)) {
                continue;
            }
            if (isOnline(username)) {
                online.add(username);
            }
        }
        return online;
    }

    private String key(String username) {
        return KEY_PREFIX + username;
    }
}
