package com.junesoon.mim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MasterKeyCryptoTest {

    private static final String KEY =
        Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private static MasterKeyCrypto crypto(String key) {
        return crypto(key, null);
    }

    private static MasterKeyCrypto crypto(String key, String previous) {
        MasterKeyCrypto crypto = new MasterKeyCrypto();
        ReflectionTestUtils.setField(crypto, "configuredKey", key);
        ReflectionTestUtils.setField(crypto, "previousKey", previous);
        return crypto;
    }

    @Test
    @DisplayName("配置主密钥后加密存储，并可解回原值")
    void roundTrip() {
        MasterKeyCrypto crypto = crypto(KEY);
        String stored = crypto.encrypt("MIM:AUTH:JWT:PRI", "secret");
        assertTrue(MasterKeyCrypto.isEncrypted(stored));
        assertNotEquals("secret", stored);
        assertEquals("secret", crypto.decrypt("MIM:AUTH:JWT:PRI", stored));
    }

    @Test
    @DisplayName("AAD 绑定键名，跨键搬运或错误主密钥解密失败")
    void crossKeyAndWrongMasterFail() {
        MasterKeyCrypto crypto = crypto(KEY);
        String stored = crypto.encrypt("MIM:AUTH:JWT:PRI", "secret");
        assertThrows(IllegalStateException.class,
            () -> crypto.decrypt("MIM:AUTH:JWT:PUB", stored));

        MasterKeyCrypto wrong = crypto(
            Base64.getEncoder().encodeToString(
                "abcdef0123456789abcdef0123456789".getBytes()));
        assertThrows(IllegalStateException.class,
            () -> wrong.decrypt("MIM:AUTH:JWT:PRI", stored));
    }

    @Test
    @DisplayName("轮换主密钥时旧密钥可解密，并自动用新密钥重加密")
    void rotateMasterKey() {
        String next = Base64.getEncoder().encodeToString(
            "fedcba9876543210fedcba9876543210".getBytes());
        MasterKeyCrypto old = crypto(KEY);
        String stored = old.encrypt("MIM:AUTH:JWT:PRI", "secret");

        MasterKeyCrypto rotated = crypto(next, KEY);
        assertEquals("secret", rotated.decrypt("MIM:AUTH:JWT:PRI", stored));
        String reencrypted = rotated.reencryptIfNeeded("MIM:AUTH:JWT:PRI", stored);
        assertNotEquals(stored, reencrypted);
        assertEquals("secret", rotated.decrypt("MIM:AUTH:JWT:PRI", reencrypted));

        MasterKeyCrypto nextOnly = crypto(next);
        assertEquals("secret", nextOnly.decrypt("MIM:AUTH:JWT:PRI", reencrypted));
        assertThrows(IllegalStateException.class,
            () -> nextOnly.decrypt("MIM:AUTH:JWT:PRI", stored));
    }

    @Test
    @DisplayName("未配置主密钥时保持明文兼容，但拒绝解密已加密数据")
    void disabledMode() {
        MasterKeyCrypto disabled = crypto("");
        assertFalse(disabled.isEnabled());
        assertEquals("plain", disabled.encrypt("k", "plain"));
        assertEquals("plain", disabled.decrypt("k", "plain"));

        MasterKeyCrypto enabled = crypto(KEY);
        String stored = enabled.encrypt("k", "secret");
        assertThrows(IllegalStateException.class, () -> disabled.decrypt("k", stored));
    }
}
