package com.junesoon.mim.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.junesoon.mim.entity.Device;
import com.junesoon.mim.exception.UserTokenException;
import com.junesoon.mim.jwt.JWTInfo;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.utils.UserAuthUtil;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 令牌设备绑定与版本校验测试。
 */
class TokenServiceTest {

    private static final class StubAuthUtil extends UserAuthUtil {
        private final JWTInfo info;

        StubAuthUtil(JWTInfo info) {
            this.info = info;
        }

        @Override
        public JWTInfo getInfoFromToken(String token) {
            if (info == null) {
                throw new IllegalArgumentException("bad token");
            }
            return info;
        }
    }

    private static final class StubDeviceMapper implements DeviceMapper {
        private final Map<String, Device> devices = new HashMap<>();

        void put(Device device) {
            devices.put(device.getUsername() + "@" + device.getDeviceId(), device);
        }

        @Override
        public int upsert(Device device) {
            return 0;
        }

        @Override
        public Device selectOne(String username, String deviceId) {
            return devices.get(username + "@" + deviceId);
        }

        @Override
        public List<Device> selectActiveDevices(String username) {
            return Collections.emptyList();
        }

        @Override
        public int touch(String username, String deviceId, long lastSeen) {
            return 0;
        }

        @Override
        public int revoke(String username, String deviceId) {
            return 0;
        }

        @Override
        public int bumpTokenVersion(String username, String deviceId) {
            return 0;
        }

        @Override
        public int bumpAllTokenVersions(String username) {
            return 0;
        }

        @Override
        public int countActiveDevices(String username) {
            return 0;
        }
    }

    private static JWTInfo boundInfo(int version) {
        return new JWTInfo("alice", "1", "Alice", "device-a", version, "jti", 0L);
    }

    @Test
    @DisplayName("设备令牌版本一致时放行")
    void matchingVersionAccepted() throws Exception {
        StubDeviceMapper devices = new StubDeviceMapper();
        Device device = new Device();
        device.setUsername("alice");
        device.setDeviceId("device-a");
        device.setRevoked(false);
        device.setTokenVersion(2);
        devices.put(device);

        JWTInfo info = boundInfo(2);
        TokenService service = new TokenService(new StubAuthUtil(info), devices);

        assertSame(info, service.resolve("token"));
    }

    @Test
    @DisplayName("登出/注销后版本不一致，旧令牌立即失效")
    void versionMismatchRejected() {
        StubDeviceMapper devices = new StubDeviceMapper();
        Device device = new Device();
        device.setUsername("alice");
        device.setDeviceId("device-a");
        device.setRevoked(false);
        device.setTokenVersion(3);
        devices.put(device);

        TokenService service = new TokenService(new StubAuthUtil(boundInfo(2)), devices);

        assertThrows(UserTokenException.class, () -> service.resolve("token"));
    }

    @Test
    @DisplayName("已注销设备上的未过期令牌被拒绝")
    void revokedDeviceRejected() {
        StubDeviceMapper devices = new StubDeviceMapper();
        Device device = new Device();
        device.setUsername("alice");
        device.setDeviceId("device-a");
        device.setRevoked(true);
        device.setTokenVersion(3);
        devices.put(device);

        TokenService service = new TokenService(new StubAuthUtil(boundInfo(3)), devices);

        assertThrows(UserTokenException.class, () -> service.resolve("token"));
    }

    @Test
    @DisplayName("未绑定设备的旧令牌保持兼容，不查设备表")
    void unboundTokenStillAccepted() throws Exception {
        TokenService service = new TokenService(
            new StubAuthUtil(new JWTInfo("alice", "1", "Alice")), new StubDeviceMapper());

        assertEquals("alice", service.resolve("token").getUsername());
    }

    @Test
    @DisplayName("解析失败时 deviceIdOf 返回 null，不抛异常")
    void deviceIdOfInvalidTokenIsNull() {
        TokenService service = new TokenService(new StubAuthUtil(null), new StubDeviceMapper());
        assertNull(service.deviceIdOf("Bearer bad"));
    }
}
