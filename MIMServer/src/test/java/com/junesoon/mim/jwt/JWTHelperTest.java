package com.junesoon.mim.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JWT 载荷测试：设备绑定、令牌版本与 jti 必须能正确往返。
 */
class JWTHelperTest {

    @Test
    @DisplayName("设备绑定、令牌版本与 jti 写入并解析一致")
    void deviceClaimsRoundTrip() throws Exception {
        Map<String, byte[]> keys = RsaKeyHelper.generateKey();
        JWTInfo info = new JWTInfo("alice", "1", "Alice", "device-a", 3,
            "jti-123", System.currentTimeMillis());

        String token = JWTHelper.generateToken(info, keys.get("pri"), 60);
        JWTInfo parsed = JWTHelper.getInfoFromToken(token, keys.get("pub"));

        assertEquals("alice", parsed.getUsername());
        assertEquals("1", parsed.getUserId());
        assertEquals("Alice", parsed.getNickname());
        assertEquals("device-a", parsed.getDeviceId());
        assertEquals(3, parsed.getTokenVersion());
        assertEquals("jti-123", parsed.getJti());
        assertTrue(parsed.getIssuedAt() > 0);
    }

    @Test
    @DisplayName("未绑定设备的旧令牌不会凭空产生设备标识")
    void unboundTokenHasNoDevice() throws Exception {
        Map<String, byte[]> keys = RsaKeyHelper.generateKey();
        JWTInfo info = new JWTInfo("bob", "2", "Bob");

        String token = JWTHelper.generateToken(info, keys.get("pri"), 60);
        JWTInfo parsed = JWTHelper.getInfoFromToken(token, keys.get("pub"));

        assertNull(parsed.getDeviceId());
        assertEquals(0, parsed.getTokenVersion());
        assertNotNull(parsed.getJti(), "服务端签发的令牌必须带 jti");
    }
}
