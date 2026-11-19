package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.security.KeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本账号设备授权清单测试。
 *
 * <p>历史同步只会发给这里已授权的设备，因此必须保证：
 * 当前设备自动可信、陌生设备默认不可信、换钥后自动失效。
 */
class OwnDeviceTrustTest {

    private static String newPublicKey() {
        KeyPair pair = DhKeyUtil.generateKeyPair();
        return DhKeyUtil.encodePublicKey(pair.getPublic());
    }

    @Test
    @DisplayName("当前设备自动信任，陌生设备默认不可信")
    void currentDeviceTrustedByDefault(@TempDir Path dir) throws Exception {
        OwnDeviceTrust trust = new OwnDeviceTrust(dir);
        trust.load("alice", "pw");
        String key = newPublicKey();
        trust.trustCurrent("device-a", key, "pw");

        assertTrue(trust.isTrusted("device-a", key));
        assertFalse(trust.isTrusted("device-b", newPublicKey()));
        assertNull(trust.getPinned("device-b"));
    }

    @Test
    @DisplayName("设备换钥后旧记录不再匹配，必须重新确认")
    void keyChangeInvalidatesTrust(@TempDir Path dir) throws Exception {
        OwnDeviceTrust trust = new OwnDeviceTrust(dir);
        trust.load("alice", "pw");
        String oldKey = newPublicKey();
        trust.trustCurrent("device-a", oldKey, "pw");

        String newKey = newPublicKey();
        assertFalse(trust.isTrusted("device-a", newKey));
        assertNull(trust.getPinned("device-b"));
    }

    @Test
    @DisplayName("用户确认的设备在重登后仍然可信")
    void trustedDevicePersists(@TempDir Path dir) throws Exception {
        String key = newPublicKey();
        OwnDeviceTrust first = new OwnDeviceTrust(dir);
        first.load("alice", "pw");
        first.trustDevice("device-b", key, "pw");

        OwnDeviceTrust second = new OwnDeviceTrust(dir);
        second.load("alice", "pw");
        assertEquals(key, second.getPinned("device-b"));
        assertEquals(1, second.getPinnedDevices().size());
    }

    @Test
    @DisplayName("错误口令按空清单处理，不会误信任任何设备")
    void wrongPasswordFailsClosed(@TempDir Path dir) throws Exception {
        String key = newPublicKey();
        OwnDeviceTrust first = new OwnDeviceTrust(dir);
        first.load("alice", "right-pw");
        first.trustDevice("device-b", key, "right-pw");

        OwnDeviceTrust second = new OwnDeviceTrust(dir);
        second.load("alice", "wrong-pw");
        assertNull(second.getPinned("device-b"));
        assertFalse(second.isTrusted("device-b", key));
        assertNotNull(second.getPinnedDevices());
        assertTrue(second.getPinnedDevices().isEmpty());
    }
}
