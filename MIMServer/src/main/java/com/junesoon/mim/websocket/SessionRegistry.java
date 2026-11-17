package com.junesoon.mim.websocket;

import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.entity.UserKey;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 本实例内的 WebSocket 会话注册表（按设备隔离）。
 *
 * <p>索引键是 {@code username + '\u0000' + deviceId}。多设备下这是必须的：
 * 一条消息是为<b>某个设备</b>加密的，只有那台设备能解密。
 * 若按用户投递，同一账号的其它设备会收到解不开的密文，
 * 既浪费流量，也会在界面上堆出一串「此消息无法解密」。
 *
 * <p>注意：这份 Map 只代表「当前 JVM」持有的连接。跨实例投递走
 * {@link com.junesoon.mim.service.PushService}（Redis Pub/Sub）。
 */
@Slf4j
@Component
public class SessionRegistry {

    private static final char SEPARATOR = '\u0000';

    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessionsByDevice =
        new ConcurrentHashMap<>();

    private static String key(String username, String deviceId) {
        return username + SEPARATOR + normalize(deviceId);
    }

    private static String normalize(String deviceId) {
        return deviceId == null || deviceId.trim().isEmpty()
            ? UserKey.DEFAULT_DEVICE_ID : deviceId.trim();
    }

    /**
     * 注册一台设备的连接。
     */
    public void register(String username, String deviceId, WebSocketSession session) {
        sessionsByDevice.compute(key(username, deviceId), (k, existing) -> {
            Set<WebSocketSession> set = existing == null
                ? Collections.newSetFromMap(new ConcurrentHashMap<>())
                : existing;
            set.add(session);
            return set;
        });
    }

    /**
     * 注销连接。
     *
     * @return 该用户在本实例上<b>所有设备</b>剩余的连接总数（用于判定是否真正离线）
     */
    public int unregister(String username, WebSocketSession session) {
        String deviceId = deviceOf(session);
        sessionsByDevice.computeIfPresent(key(username, deviceId), (k, existing) -> {
            existing.remove(session);
            return existing.isEmpty() ? null : existing;
        });
        return countUserSessions(username);
    }

    /**
     * 只投递给本实例上<b>指定设备</b>的连接。
     */
    public void sendLocalToDevice(String username, String deviceId, String payload) {
        send(sessionsByDevice.get(key(username, deviceId)), payload, username, deviceId);
    }

    /**
     * 投递给本实例上该用户<b>所有设备</b>的连接。
     */
    public void sendLocalToUser(String username, String payload) {
        for (String deviceId : localDevices(username)) {
            sendLocalToDevice(username, deviceId, payload);
        }
    }

    /**
     * 投递给该用户所有设备，但排除指定设备（多端同步时避免回推给自己）。
     */
    public void sendLocalToUserExcept(String username, String excludedDeviceId, String payload) {
        String excluded = normalize(excludedDeviceId);
        for (String deviceId : localDevices(username)) {
            if (!excluded.equals(deviceId)) {
                sendLocalToDevice(username, deviceId, payload);
            }
        }
    }

    private void send(Set<WebSocketSession> targets, String payload, String username,
        String deviceId) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        TextMessage message = new TextMessage(payload);
        for (WebSocketSession session : targets) {
            if (session == null || !session.isOpen()) {
                continue;
            }
            try {
                // 同一连接被多线程并发写会抛 IllegalStateException，这里串行化
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (IOException e) {
                log.warn("向用户 {} 设备 {} 推送失败：{}", username, deviceId, e.getMessage());
            }
        }
    }

    /**
     * 本实例上该用户有哪些设备在线。
     */
    public Set<String> localDevices(String username) {
        Set<String> devices = new HashSet<>();
        String prefix = username + SEPARATOR;
        for (String k : sessionsByDevice.keySet()) {
            if (k.startsWith(prefix)) {
                devices.add(k.substring(prefix.length()));
            }
        }
        return devices;
    }

    /**
     * 该用户的<b>某个设备</b>是否连在本实例上。
     */
    public boolean hasLocalSession(String username, String deviceId) {
        Set<WebSocketSession> targets = sessionsByDevice.get(key(username, deviceId));
        return targets != null && !targets.isEmpty();
    }

    /**
     * 该用户是否有<b>任意设备</b>连在本实例上。
     */
    public boolean hasLocalSession(String username) {
        String prefix = username + SEPARATOR;
        for (String k : sessionsByDevice.keySet()) {
            if (k.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> localUsers() {
        Set<String> users = new HashSet<>();
        for (String k : sessionsByDevice.keySet()) {
            int idx = k.indexOf(SEPARATOR);
            if (idx > 0) {
                users.add(k.substring(0, idx));
            }
        }
        return Collections.unmodifiableSet(users);
    }

    public int localConnectionCount() {
        return sessionsByDevice.values().stream().mapToInt(Set::size).sum();
    }

    /**
     * 本实例上某用户的连接总数（所有设备）。
     */
    public int countLocalSessions(String username) {
        return countUserSessions(username);
    }

    /**
     * 本实例上某用户某台设备的连接数。
     */
    public int countLocalDeviceSessions(String username, String deviceId) {
        Set<WebSocketSession> targets = sessionsByDevice.get(key(username, deviceId));
        return targets == null ? 0 : targets.size();
    }

    /**
     * 关闭某用户在本实例上的所有连接（改密时调用）。
     */
    public void closeUser(String username) {
        String prefix = username + SEPARATOR;
        for (java.util.Map.Entry<String, Set<WebSocketSession>> entry
            : sessionsByDevice.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            Set<WebSocketSession> sessions = sessionsByDevice.remove(entry.getKey());
            closeAll(sessions);
        }
    }

    /**
     * 关闭某台设备的连接（注销设备时调用）。
     */
    public void closeDevice(String username, String deviceId) {
        closeAll(sessionsByDevice.remove(key(username, deviceId)));
    }

    private void closeAll(Set<WebSocketSession> sessions) {
        if (sessions == null) {
            return;
        }
        for (WebSocketSession session : sessions) {
            if (session == null) {
                continue;
            }
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.POLICY_VIOLATION);
                }
            } catch (IOException e) {
                log.debug("关闭 WebSocket 连接失败：{}", e.getMessage());
            }
        }
    }

    private int countUserSessions(String username) {
        String prefix = username + SEPARATOR;
        int count = 0;
        for (java.util.Map.Entry<String, Set<WebSocketSession>> entry : sessionsByDevice.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                count += entry.getValue().size();
            }
        }
        return count;
    }

    /**
     * 从 session 属性里取设备标识。
     */
    public static String deviceOf(WebSocketSession session) {
        if (session == null) {
            return UserKey.DEFAULT_DEVICE_ID;
        }
        Object value = session.getAttributes().get(CommonConstants.WEB_SOCKET_DEVICE_ID);
        return value == null ? UserKey.DEFAULT_DEVICE_ID : value.toString();
    }
}
