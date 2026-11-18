package com.junesoon.mim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.config.KeyConfiguration;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 服务端敏感字段（邮箱、TOTP 密钥）的加密存储测试。
 */
class EmailCryptoServiceTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString(
        "0123456789abcdef0123456789abcdef".getBytes());
    private static final String KEY_B = Base64.getEncoder().encodeToString(
        "fedcba9876543210fedcba9876543210".getBytes());

    private static EmailCryptoService service() {
        KeyConfiguration config = new KeyConfiguration();
        config.setUserPriKey("test-jwt-private-key-material".getBytes());
        return new EmailCryptoService(config);
    }

    private static EmailCryptoService crypto(String current, String previous) {
        KeyConfiguration config = new KeyConfiguration();
        config.setUserPriKey("test-jwt-private-key-material".getBytes());
        EmailCryptoService crypto = new EmailCryptoService(config);
        ReflectionTestUtils.setField(crypto, "configuredKey", current);
        ReflectionTestUtils.setField(crypto, "previousKey", previous);
        return crypto;
    }

    @Test
    @DisplayName("加密后可解出原邮箱，密文不含明文")
    void roundTrip() {
        EmailCryptoService crypto = service();
        String stored = crypto.encrypt("alice", "alice@example.com");
        assertNotEquals(null, stored);
        assertNotEquals("alice@example.com", stored);
        assertEquals("alice@example.com", crypto.decrypt("alice", stored));
    }

    @Test
    @DisplayName("密文被跨账号搬运或篡改时解密失败")
    void tamperAndCrossUserFail() {
        EmailCryptoService crypto = service();
        String stored = crypto.encrypt("alice", "alice@example.com");
        assertNull(crypto.decrypt("bob", stored), "AAD 绑定用户名，跨账号必须失败");

        String tampered = stored.substring(0, stored.length() - 1)
            + (stored.endsWith("A") ? "B" : "A");
        assertNull(crypto.decrypt("alice", tampered));
    }

    @Test
    @DisplayName("TOTP 密钥加密存储，且与邮箱域隔离")
    void totpRoundTripAndDomainSeparation() {
        EmailCryptoService crypto = service();
        String stored = crypto.encryptTotp("alice", "JBSWY3DPEHPK3PXP");
        assertNotEquals("JBSWY3DPEHPK3PXP", stored);
        assertTrue(stored.length() <= 255, "TOTP 密文必须放得进 varchar(255) 列");
        assertEquals("JBSWY3DPEHPK3PXP", crypto.decryptTotp("alice", stored));
        assertNull(crypto.decryptTotp("bob", stored), "AAD 绑定用户名");
        assertNull(crypto.decrypt("alice", stored), "TOTP 密文不能当邮箱密文解");
    }

    @Test
    @DisplayName("邮箱/TOTP 密钥轮换：新密钥 + 旧密钥可解密，并重加密为当前密钥")
    void rotateEmailKey() {
        EmailCryptoService old = crypto(KEY_A, null);
        String email = old.encrypt("alice", "alice@example.com");
        String totp = old.encryptTotp("alice", "JBSWY3DPEHPK3PXP");

        EmailCryptoService rotated = crypto(KEY_B, KEY_A);
        assertTrue(rotated.rotationConfigured());
        assertEquals("alice@example.com", rotated.decrypt("alice", email));
        assertEquals("JBSWY3DPEHPK3PXP", rotated.decryptTotp("alice", totp));

        String newEmail = rotated.reencryptEmail("alice", email);
        String newTotp = rotated.reencryptTotp("alice", totp);
        assertNotEquals(email, newEmail);
        assertNotEquals(totp, newTotp);

        EmailCryptoService nextOnly = crypto(KEY_B, null);
        assertEquals("alice@example.com", nextOnly.decrypt("alice", newEmail));
        assertEquals("JBSWY3DPEHPK3PXP", nextOnly.decryptTotp("alice", newTotp));
    }

    @Test
    @DisplayName("从 JWT 私钥派生密钥迁移到显式 MIM_EMAIL_KEY")
    void migrateLegacyDerivedKey() {
        EmailCryptoService legacy = service();
        String email = legacy.encrypt("alice", "alice@example.com");
        String totp = legacy.encryptTotp("alice", "JBSWY3DPEHPK3PXP");

        EmailCryptoService explicit = crypto(KEY_B, null);
        assertEquals("alice@example.com", explicit.decrypt("alice", email));
        String newEmail = explicit.reencryptEmail("alice", email);
        String newTotp = explicit.reencryptTotp("alice", totp);
        assertEquals("alice@example.com", explicit.decrypt("alice", newEmail));
        assertEquals("JBSWY3DPEHPK3PXP", explicit.decryptTotp("alice", newTotp));
        assertNotEquals(email, newEmail);
    }

    @Test
    @DisplayName("旧版明文 TOTP 密钥仍可读取，便于平滑升级")
    void legacyPlaintextTotp() {
        EmailCryptoService crypto = service();
        assertEquals("JBSWY3DPEHPK3PXP",
            crypto.decryptTotp("alice", "JBSWY3DPEHPK3PXP"));
        assertNull(crypto.decryptTotp("alice", ""));
    }

    @Test
    @DisplayName("空邮箱不产生密文")
    void emptyEmail() {
        EmailCryptoService crypto = service();
        assertNull(crypto.encrypt("alice", ""));
        assertNull(crypto.encrypt("alice", null));
        assertNull(crypto.decrypt("alice", null));
    }
}
