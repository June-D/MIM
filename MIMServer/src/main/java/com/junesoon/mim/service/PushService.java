package com.junesoon.mim.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.entity.UserKey;
import com.junesoon.mim.websocket.SessionRegistry;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * WebSocket 下行推送（支持设备级路由）。
 *
 * <p>核心问题：写路径与连接可能不在同一实例（A 发消息的 HTTP 打到 Pod1，
 * B 的 WebSocket 挂在 Pod2）。所以推送不能直接操作本机 Map，
 * 而是发布到 Redis Pub/Sub，所有实例订阅后各自投递给本机持有的会话。
 *
 * <p>信封格式：
 * <pre>
 * {
 *   "target": "bob",
 *   "device": "bob-mobile",   // 可选：为 null 时投给该用户所有设备
 *   "exclude": "bob-desktop", // 可选：投给所有设备但排除这台
 *   "payload": "&lt;BaseNotify JSON&gt;"
 * }
 * </pre>
 *
 * <p>设备维度是必需的：消息为<b>某个设备</b>加密，只有它能解密。
 * 若按用户广播，其它设备会收到解不开的密文。
 */
@Slf4j
@Service
public class PushService implements MessageListener {

    private static final String FIELD_TARGET = "target";
    private static final String FIELD_DEVICE = "device";
    private static final String FIELD_EXCLUDE = "exclude";
    private static final String FIELD_PAYLOAD = "payload";

    private final StringRedisTemplate redisTemplate;
    private final SessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    public PushService(StringRedisTemplate redisTemplate, SessionRegistry sessionRegistry,
        ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 推送给指定用户的<b>指定设备</b>。
     */
    public void pushToDevice(String target, String deviceId, Object notify) {
        dispatch(target, deviceId, null, notify);
    }

    /**
     * 推送给指定用户的<b>所有设备</b>。
     */
    public void pushToUser(String target, Object notify) {
        dispatch(target, null, null, notify);
    }

    /**
     * 推送给该用户所有设备，但排除指定设备（多端同步时避免回推给发起方）。
     */
    public void pushToUserExcept(String target, String excludedDeviceId, Object notify) {
        dispatch(target, null, excludedDeviceId, notify);
    }

    /**
     * 向后兼容：等价于 {@link #pushToUser(String, Object)}。
     */
    public void push(String target, Object notify) {
        pushToUser(target, notify);
    }

    /**
     * 推送给一批用户的所有设备。
     */
    public void pushAll(Iterable<String> targets, Object notify) {
        if (targets == null) {
            return;
        }
        for (String target : targets) {
            pushToUser(target, notify);
        }
    }

    private void dispatch(String target, String deviceId, String excludeDeviceId, Object notify) {
        if (target == null) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(notify);
        } catch (Exception e) {
            log.error("序列化推送内容失败", e);
            return;
        }

        Map<String, String> envelope = new LinkedHashMap<>(4);
        envelope.put(FIELD_TARGET, target);
        if (deviceId != null && !deviceId.isEmpty()) {
            envelope.put(FIELD_DEVICE, deviceId);
        }
        if (excludeDeviceId != null && !excludeDeviceId.isEmpty()) {
            envelope.put(FIELD_EXCLUDE, excludeDeviceId);
        }
        envelope.put(FIELD_PAYLOAD, payload);

        try {
            redisTemplate.convertAndSend(CommonConstants.REDIS_PUSH_CHANNEL,
                objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            // Redis 故障时至少保证本实例能收到；跨实例丢失由客户端
            // 重连时调 /message/undelivered 补齐
            log.warn("Redis Pub/Sub 不可用，仅尝试本实例投递：{}", e.getMessage());
            deliverLocally(target, deviceId, excludeDeviceId, payload);
        }
    }

    /**
     * 所有实例都订阅同一频道；本实例收到后只投给本机连接，未持有则丢弃。
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            Map<?, ?> envelope = objectMapper.readValue(body, Map.class);
            Object target = envelope.get(FIELD_TARGET);
            Object payload = envelope.get(FIELD_PAYLOAD);
            if (target == null || payload == null) {
                return;
            }
            Object device = envelope.get(FIELD_DEVICE);
            Object exclude = envelope.get(FIELD_EXCLUDE);
            deliverLocally(target.toString(),
                device == null ? null : device.toString(),
                exclude == null ? null : exclude.toString(),
                payload.toString());
        } catch (Exception e) {
            log.warn("处理跨实例推送失败：{}", e.getMessage());
        }
    }

    /**
     * 按信封里的范围在本实例投递。
     */
    private void deliverLocally(String target, String deviceId, String excludeDeviceId,
        String payload) {
        if (deviceId != null && !deviceId.isEmpty()) {
            sessionRegistry.sendLocalToDevice(target, deviceId, payload);
        } else if (excludeDeviceId != null && !excludeDeviceId.isEmpty()) {
            sessionRegistry.sendLocalToUserExcept(target, excludeDeviceId, payload);
        } else {
            sessionRegistry.sendLocalToUser(target, payload);
        }
    }

    /**
     * 目标设备标识的规范化，供调用方统一缺省值。
     */
    public static String normalizeDevice(String deviceId) {
        return deviceId == null || deviceId.trim().isEmpty()
            ? UserKey.DEFAULT_DEVICE_ID : deviceId.trim();
    }
}
