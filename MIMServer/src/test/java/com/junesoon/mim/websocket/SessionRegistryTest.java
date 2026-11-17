package com.junesoon.mim.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.constant.CommonConstants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 会话注册表的设备级路由测试。
 *
 * <p>这是多设备可用性的关键：消息为某个设备加密，只有它能解密。
 * 若按用户广播，同账号其它设备会收到解不开的密文。
 */
class SessionRegistryTest {

    /**
     * 只记录收到的消息的最小 WebSocketSession 实现。
     */
    private static final class FakeSession implements WebSocketSession {
        private final String id;
        private final Map<String, Object> attributes = new HashMap<>();
        final List<String> received = new ArrayList<>();
        boolean open = true;

        FakeSession(String id) {
            this.id = id;
        }

        FakeSession withDevice(String deviceId) {
            attributes.put(CommonConstants.WEB_SOCKET_DEVICE_ID, deviceId);
            return this;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public java.net.URI getUri() {
            return java.net.URI.create("ws://localhost/websocket/test");
        }

        @Override
        public org.springframework.http.HttpHeaders getHandshakeHeaders() {
            return new org.springframework.http.HttpHeaders();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public java.security.Principal getPrincipal() {
            return null;
        }

        @Override
        public java.net.InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public java.net.InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 8192;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 8192;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return new ArrayList<>();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            if (!open) {
                throw new IllegalStateException("session closed");
            }
            received.add(message.getPayload().toString());
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public void close(CloseStatus status) {
            open = false;
        }
    }

    private SessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SessionRegistry();
    }

    @Test
    @DisplayName("按设备投递：只有目标设备收到")
    void sendToDeviceOnlyReachesThatDevice() {
        FakeSession desktop = new FakeSession("s1").withDevice("desktop");
        FakeSession mobile = new FakeSession("s2").withDevice("mobile");
        registry.register("bob", "desktop", desktop);
        registry.register("bob", "mobile", mobile);

        registry.sendLocalToDevice("bob", "desktop", "for-desktop");

        assertEquals(1, desktop.received.size(), "目标设备应收到");
        assertEquals(0, mobile.received.size(), "非目标设备不应收到");
    }

    @Test
    @DisplayName("按用户投递：所有设备都收到")
    void sendToUserReachesAllDevices() {
        FakeSession desktop = new FakeSession("s1").withDevice("desktop");
        FakeSession mobile = new FakeSession("s2").withDevice("mobile");
        registry.register("bob", "desktop", desktop);
        registry.register("bob", "mobile", mobile);

        registry.sendLocalToUser("bob", "broadcast");

        assertEquals(1, desktop.received.size());
        assertEquals(1, mobile.received.size());
    }

    @Test
    @DisplayName("排除指定设备：用于多端同步，避免回推给发起方")
    void sendToUserExceptExcludesDevice() {
        FakeSession desktop = new FakeSession("s1").withDevice("desktop");
        FakeSession mobile = new FakeSession("s2").withDevice("mobile");
        registry.register("bob", "desktop", desktop);
        registry.register("bob", "mobile", mobile);

        registry.sendLocalToUserExcept("bob", "desktop", "sync");

        assertEquals(0, desktop.received.size(), "发起设备不应收到自己的回推");
        assertEquals(1, mobile.received.size(), "其它设备应收到同步");
    }

    @Test
    @DisplayName("不同用户的设备互不干扰")
    void usersIsolated() {
        FakeSession bobDesktop = new FakeSession("s1").withDevice("desktop");
        FakeSession aliceDesktop = new FakeSession("s2").withDevice("desktop");
        registry.register("bob", "desktop", bobDesktop);
        registry.register("alice", "desktop", aliceDesktop);

        registry.sendLocalToDevice("bob", "desktop", "for-bob");

        assertEquals(1, bobDesktop.received.size());
        assertEquals(0, aliceDesktop.received.size());
    }

    @Test
    @DisplayName("同一设备的多个连接（重连未断开）都能收到")
    void multipleConnectionsPerDevice() {
        FakeSession first = new FakeSession("s1").withDevice("desktop");
        FakeSession second = new FakeSession("s2").withDevice("desktop");
        registry.register("bob", "desktop", first);
        registry.register("bob", "desktop", second);

        registry.sendLocalToDevice("bob", "desktop", "hi");

        assertEquals(1, first.received.size());
        assertEquals(1, second.received.size());
    }

    @Test
    @DisplayName("已关闭的连接被跳过，不抛异常")
    void closedSessionSkipped() {
        FakeSession closed = new FakeSession("s1").withDevice("desktop");
        closed.open = false;
        registry.register("bob", "desktop", closed);

        registry.sendLocalToDevice("bob", "desktop", "hi");

        assertEquals(0, closed.received.size());
    }

    @Test
    @DisplayName("localDevices 只返回本实例在线的设备")
    void localDevices() {
        registry.register("bob", "desktop", new FakeSession("s1").withDevice("desktop"));
        registry.register("bob", "mobile", new FakeSession("s2").withDevice("mobile"));

        Set<String> devices = registry.localDevices("bob");
        assertEquals(2, devices.size());
        assertTrue(devices.contains("desktop") && devices.contains("mobile"));
        assertTrue(registry.localDevices("alice").isEmpty());
    }

    @Test
    @DisplayName("注销后设备不再收到消息；返回剩余连接数用于判定离线")
    void unregister() {
        FakeSession desktop = new FakeSession("s1").withDevice("desktop");
        FakeSession mobile = new FakeSession("s2").withDevice("mobile");
        registry.register("bob", "desktop", desktop);
        registry.register("bob", "mobile", mobile);

        int remaining = registry.unregister("bob", desktop);
        assertEquals(1, remaining, "还有一台设备连着，不应判定离线");

        registry.sendLocalToDevice("bob", "desktop", "after-close");
        assertEquals(0, desktop.received.size());
        assertFalse(registry.hasLocalSession("bob", "desktop"));
        assertTrue(registry.hasLocalSession("bob", "mobile"));

        int remainingAfterAll = registry.unregister("bob", mobile);
        assertEquals(0, remainingAfterAll, "所有设备都断开后才判定离线");
        assertFalse(registry.hasLocalSession("bob"));
    }

    @Test
    @DisplayName("缺省设备标识归一化为 default")
    void defaultDeviceNormalized() {
        FakeSession noHeader = new FakeSession("s1");
        registry.register("bob", null, noHeader);

        assertTrue(registry.hasLocalSession("bob", "default"));
        registry.sendLocalToDevice("bob", "default", "hi");
        assertEquals(1, noHeader.received.size());
    }

    @Test
    @DisplayName("localUsers 去重返回用户列表")
    void localUsers() {
        registry.register("bob", "desktop", new FakeSession("s1").withDevice("desktop"));
        registry.register("bob", "mobile", new FakeSession("s2").withDevice("mobile"));
        registry.register("alice", "desktop", new FakeSession("s3").withDevice("desktop"));

        Set<String> users = new HashSet<>(registry.localUsers());
        assertEquals(2, users.size());
        assertTrue(users.contains("bob") && users.contains("alice"));
        assertEquals(3, registry.localConnectionCount());
    }

    @Test
    @DisplayName("向不存在的设备投递是安全的空操作")
    void sendToUnknownDeviceIsNoop() {
        registry.register("bob", "desktop", new FakeSession("s1").withDevice("desktop"));
        // 不应抛异常
        registry.sendLocalToDevice("bob", "ghost", "hi");
        registry.sendLocalToUser("nobody", "hi");
        registry.sendLocalToUserExcept("nobody", "x", "hi");
    }

    @Test
    @DisplayName("JWT 过期的连接会被关闭并从注册表移除")
    void closeExpiredSessions() {
        FakeSession expired = new FakeSession("s1").withDevice("desktop");
        expired.getAttributes().put(CommonConstants.WEB_SOCKET_TOKEN_EXPIRES_AT, 1000L);
        FakeSession valid = new FakeSession("s2").withDevice("mobile");
        valid.getAttributes().put(CommonConstants.WEB_SOCKET_TOKEN_EXPIRES_AT, 9000L);
        registry.register("bob", "desktop", expired);
        registry.register("bob", "mobile", valid);

        int closed = registry.closeExpired(5000L);
        assertEquals(1, closed);
        assertFalse(expired.isOpen());
        assertFalse(registry.hasLocalSession("bob", "desktop"));
        assertTrue(registry.hasLocalSession("bob", "mobile"));
    }

    @Test
    @DisplayName("向同一 session 并发投递不会因并发写抛异常")
    void concurrentSendToSameSession() throws Exception {
        FakeSession desktop = new FakeSession("s1").withDevice("desktop");
        registry.register("bob", "desktop", desktop);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int index = i;
            Thread thread = new Thread(() ->
                registry.sendLocalToDevice("bob", "desktop", "msg-" + index));
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(8, desktop.received.size(), "并发投递应全部成功");
    }
}
