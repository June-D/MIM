package com.junesoon.mim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.junesoon.mim.config.KeyConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 服务端敏感字段（邮箱、TOTP 密钥）的加密存储测试。
 */
class EmailCryptoServiceTest {

    private static EmailCryptoService service() {
        KeyConfiguration config = new KeyConfiguration();
        config.setUserPriKey("test-jwt-private-key-material".getBytes());
        return new EmailCryptoService(config);
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
        assertEquals("JBSWY3DPEHPK3PXP", crypto.decryptTotp("alice", stored));
        assertNull(crypto.decryptTotp("bob", stored), "AAD 绑定用户名");
        assertNull(crypto.decrypt("alice", stored), "TOTP 密文不能当邮箱密文解");
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
