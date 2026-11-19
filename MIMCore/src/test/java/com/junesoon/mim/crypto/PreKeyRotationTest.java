package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.security.KeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 签名预密钥轮换测试：旧私钥必须保留，否则轮换前发出的握手会永久解不开。
 */
class PreKeyRotationTest {

    @Test
    @DisplayName("轮换后新旧签名预密钥都能找到，当前指向最新一把")
    void retainsOldSignedPreKeys(@TempDir Path dir) throws Exception {
        KeyPair identity = DhKeyUtil.generateKeyPair();
        PreKeyStore store = new PreKeyStore(dir);

        PreKeyStore.LocalSignedPreKey first =
            store.generateSignedPreKey(identity.getPrivate(), 1);
        store.setSignedPreKey(first);
        assertTrue(first.getCreatedAt() > 0, "新生成的 SPK 必须带创建时间");

        PreKeyStore.LocalSignedPreKey second =
            store.generateSignedPreKey(identity.getPrivate(), 2);
        store.setSignedPreKey(second);
        store.save("alice", "device-a", "pw");

        PreKeyStore reloaded = new PreKeyStore(dir);
        assertTrue(reloaded.load("alice", "device-a", "pw"));
        assertEquals(2, reloaded.getSignedPreKey().getPreKeyId());
        assertNotNull(reloaded.findSignedPreKey(1), "轮换前的 SPK 私钥必须保留");
        assertNotNull(reloaded.findSignedPreKey(2));
        assertNull(reloaded.findSignedPreKey(99));
    }

    @Test
    @DisplayName("最多保留三把签名预密钥")
    void prunesToConfiguredLimit(@TempDir Path dir) {
        KeyPair identity = DhKeyUtil.generateKeyPair();
        PreKeyStore store = new PreKeyStore(dir);
        for (int id = 1; id <= 5; id++) {
            store.setSignedPreKey(store.generateSignedPreKey(identity.getPrivate(), id));
        }
        assertNotNull(store.findSignedPreKey(3));
        assertNotNull(store.findSignedPreKey(4));
        assertNotNull(store.findSignedPreKey(5));
        assertNull(store.findSignedPreKey(1), "最旧的 SPK 应被清理");
        assertNull(store.findSignedPreKey(2));
    }
}
