package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 改密时本地密钥必须能用新口令重新加密，旧口令必须立即失效。
 */
class PasswordRotationTest {

    private static String newPublicKey() {
        KeyPair pair = DhKeyUtil.generateKeyPair();
        return DhKeyUtil.encodePublicKey(pair.getPublic());
    }

    @Test
    @DisplayName("身份私钥换口令后公钥不变，旧口令无法再解锁")
    void identityReencrypts(@TempDir Path dir) throws Exception {
        IdentityStore store = new IdentityStore(dir);
        IdentityStore.Identity identity = store.create("alice", "dev", "old", 1);
        store.saveExisting(identity, "alice", "dev", "new");

        IdentityStore.Identity reloaded = store.load("alice", "dev", "new");
        assertEquals(identity.getPublicKeyBase64(), reloaded.getPublicKeyBase64());
        assertThrows(IdentityException.class, () -> store.load("alice", "dev", "old"));
    }

    @Test
    @DisplayName("信任库与设备授权清单换口令后内容仍可读")
    void trustStoresReencrypt(@TempDir Path dir) throws Exception {
        String key = newPublicKey();
        TrustStore trust = new TrustStore(dir.resolve("trust"));
        trust.load("alice", "old");
        trust.verifyAll("bob", Collections.singletonMap("bob-dev", key), "old");
        trust.changePassword("new");
        TrustStore reloaded = new TrustStore(dir.resolve("trust"));
        reloaded.load("alice", "new");
        assertEquals(key, reloaded.getPinned("bob", "bob-dev"));

        OwnDeviceTrust own = new OwnDeviceTrust(dir.resolve("own"));
        own.load("alice", "old");
        own.trustDevice("device-b", key, "old");
        own.changePassword("new");
        OwnDeviceTrust ownReloaded = new OwnDeviceTrust(dir.resolve("own"));
        ownReloaded.load("alice", "new");
        assertTrue(ownReloaded.isTrusted("device-b", key));
    }

    @Test
    @DisplayName("会话状态换口令后内容不变，旧口令失效")
    void sessionStateReencrypts(@TempDir Path dir) throws Exception {
        SessionStore store = new SessionStore(dir);
        SessionState state = new SessionState();
        state.setPeer("bob");
        state.setDeviceId("dev-b");
        state.setCreatedAt(1L);
        state.setRootKey(new byte[32]);
        state.setSendChainKey(new byte[32]);
        store.save("alice", "bob", "dev-b", state, "old");

        store.changePassword("alice", "old", "new");
        SessionState reloaded = store.load("alice", "bob", "dev-b", "new");
        assertNotNull(reloaded);
        assertEquals("bob", reloaded.getPeer());
        assertThrows(IdentityException.class,
            () -> store.load("alice", "bob", "dev-b", "old"));
    }

    @Test
    @DisplayName("预密钥文件换口令后可加载，旧口令失效")
    void preKeysReencrypt(@TempDir Path dir) throws Exception {
        KeyPair identity = DhKeyUtil.generateKeyPair();
        PreKeyStore store = new PreKeyStore(dir);
        store.setSignedPreKey(store.generateSignedPreKey(identity.getPrivate(), 1));
        store.save("alice", "dev", "old");

        store.changePassword("alice", "dev", "old", "new");
        PreKeyStore reloaded = new PreKeyStore(dir);
        assertTrue(reloaded.load("alice", "dev", "new"));
        assertEquals(1, reloaded.getSignedPreKey().getPreKeyId());
        assertThrows(IdentityException.class,
            () -> new PreKeyStore(dir).load("alice", "dev", "old"));
    }

    @Test
    @DisplayName("本地明文缓存换口令后内容仍可读")
    void plaintextReencrypts(@TempDir Path dir) throws Exception {
        PlaintextStore store = new PlaintextStore(dir);
        store.load("alice", "old");
        store.put("msg-1", "hello");
        store.changePassword("new");

        PlaintextStore reloaded = new PlaintextStore(dir);
        reloaded.load("alice", "new");
        assertEquals("hello", reloaded.get("msg-1"));
    }
}
