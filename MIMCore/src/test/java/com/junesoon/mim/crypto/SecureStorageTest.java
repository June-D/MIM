package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地密钥与状态存储测试。
 *
 * <p>共同要求：私钥类数据一律加密落盘、错误口令打不开、
 * 会话状态必须无损往返（否则棘轮不同步会导致永久失联）。
 */
class SecureStorageTest {

    // ==================== 身份密钥库 ====================

    @Test
    @DisplayName("身份私钥加密落盘，不含明文 PKCS8")
    void identityEncryptedAtRest(@TempDir Path dir) throws Exception {
        IdentityStore store = new IdentityStore(dir);
        IdentityStore.Identity identity = store.create("alice", "correct-horse", 1);

        Path file = dir.resolve("alice__default.key");
        assertTrue(Files.exists(file));
        String content = new String(Files.readAllBytes(file), "UTF-8");
        assertFalse(content.contains("PRIVATE KEY"));
        assertTrue(content.contains("salt=") && content.contains("nonce="));

        IdentityStore.Identity reloaded = store.load("alice", "correct-horse");
        assertNotNull(reloaded);
        assertEquals(identity.getPublicKeyBase64(), reloaded.getPublicKeyBase64());
        assertEquals(identity.getFingerprint(), reloaded.getFingerprint());
    }

    @Test
    @DisplayName("错误口令无法解锁身份私钥")
    void identityWrongPasswordFails(@TempDir Path dir) throws Exception {
        IdentityStore store = new IdentityStore(dir);
        store.create("alice", "right", 1);
        assertThrows(IdentityException.class, () -> store.load("alice", "wrong"));
    }

    @Test
    @DisplayName("指纹与公钥内容自洽")
    void identityFingerprintMatches(@TempDir Path dir) throws Exception {
        IdentityStore store = new IdentityStore(dir);
        IdentityStore.Identity identity = store.create("alice", "pw", 1);
        String expected = Hkdf.sha256Hex(java.util.Base64.getDecoder().decode(
            identity.getPublicKeyBase64()));
        assertEquals(expected, identity.getFingerprint());
    }

    // ==================== 会话状态 ====================

    @Test
    @DisplayName("会话状态加密往返，重载后可继续收发")
    void sessionStateRoundTrip(@TempDir Path dir) throws Exception {
        SessionStore store = new SessionStore(dir);
        RatchetService ratchet = new RatchetService();

        KeyPair aliceId = DhKeyUtil.generateKeyPair();
        KeyPair bobId = DhKeyUtil.generateKeyPair();
        KeyPair bobSpk = DhKeyUtil.generateKeyPair();
        KeyPair bobOpk = DhKeyUtil.generateKeyPair();

        RatchetService.InitiatorResult init = ratchet.initiatorSession("bob",
            aliceId.getPrivate(), DhKeyUtil.encodePublicKey(aliceId.getPublic()),
            DhKeyUtil.encodePublicKey(bobId.getPublic()),
            DhKeyUtil.encodePublicKey(bobSpk.getPublic()),
            DhKeyUtil.encodePublicKey(bobOpk.getPublic()));

        SessionState bobState = ratchet.responderSession("alice", bobId.getPrivate(),
            DhKeyUtil.encodePublicKey(bobId.getPublic()), bobSpk.getPrivate(),
            DhKeyUtil.encodePublicKey(bobSpk.getPublic()), bobOpk.getPrivate(),
            init.getSenderIdentityKey(), init.getSenderEphemeralKey(), true);

        RatchetService.Encrypted first = ratchet.encrypt(init.getState(), "A1");
        ratchet.decrypt(bobState, first.getHeader(), first.getIv(), first.getCiphertext());

        store.save("alice", "bob", "device-b", init.getState(), "pw");
        SessionState reloaded = store.load("alice", "bob", "device-b", "pw");
        assertNotNull(reloaded);
        assertEquals(init.getState().getSendCount(), reloaded.getSendCount());
        assertTrue(java.util.Arrays.equals(init.getState().getSendChainKey(),
            reloaded.getSendChainKey()));

        // 用重载状态继续加密，对方仍能解密 —— 证明状态无损
        RatchetService.Encrypted afterReload = ratchet.encrypt(reloaded, "重载后");
        String plain = ratchet.decrypt(bobState, afterReload.getHeader(), afterReload.getIv(),
            afterReload.getCiphertext());
        assertEquals("重载后", plain);
    }

    @Test
    @DisplayName("会话文件不含明文链密钥，错误口令无法打开")
    void sessionFileEncrypted(@TempDir Path dir) throws Exception {
        SessionStore store = new SessionStore(dir);
        RatchetService ratchet = new RatchetService();
        SessionState state = ratchet.initiatorSession("bob",
            DhKeyUtil.generateKeyPair().getPrivate(), "dummy",
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()),
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()), null).getState();
        store.save("alice", "bob", "device-b", state, "pw");

        Path file = dir.resolve("alice~bob~device-b.session");
        String content = new String(Files.readAllBytes(file), "UTF-8");
        String encodedChainKey = java.util.Base64.getEncoder().encodeToString(
            state.getSendChainKey());
        assertFalse(content.contains(encodedChainKey), "会话文件不应包含明文链密钥");

        assertThrows(IdentityException.class, () -> store.load("alice", "bob", "device-b", "wrong"));
    }

    @Test
    @DisplayName("会话文件列表能反映本地已有会话（含设备维度）")
    void listPeers(@TempDir Path dir) throws Exception {
        SessionStore store = new SessionStore(dir);
        RatchetService ratchet = new RatchetService();
        for (String peer : new String[] {"bob", "carol"}) {
            SessionState state = ratchet.initiatorSession(peer,
                DhKeyUtil.generateKeyPair().getPrivate(), "dummy",
                DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()),
                DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()), null).getState();
            store.save("alice", peer, "device-1", state, "pw");
        }
        java.util.List<String> peers = store.listPeers("alice");
        assertEquals(2, peers.size(), "实际：" + peers);
        assertTrue(peers.contains("bob@device-1") && peers.contains("carol@device-1"),
            "会话标识应带设备维度，实际：" + peers);
    }

    @Test
    @DisplayName("同一对端的不同设备是彼此独立的会话文件")
    void samePeerDifferentDevicesIsolated(@TempDir Path dir) throws Exception {
        SessionStore store = new SessionStore(dir);
        RatchetService ratchet = new RatchetService();

        SessionState desktop = ratchet.initiatorSession("bob",
            DhKeyUtil.generateKeyPair().getPrivate(), "dummy",
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()),
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()), null).getState();
        SessionState mobile = ratchet.initiatorSession("bob",
            DhKeyUtil.generateKeyPair().getPrivate(), "dummy",
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()),
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()), null).getState();

        store.save("alice", "bob", "desktop", desktop, "pw");
        store.save("alice", "bob", "mobile", mobile, "pw");

        SessionState loadedDesktop = store.load("alice", "bob", "desktop", "pw");
        SessionState loadedMobile = store.load("alice", "bob", "mobile", "pw");
        assertNotNull(loadedDesktop);
        assertNotNull(loadedMobile);
        assertEquals("desktop", loadedDesktop.getDeviceId());
        assertEquals("mobile", loadedMobile.getDeviceId());
        // 两条独立的棘轮链，链密钥必须不同
        assertFalse(java.util.Arrays.equals(loadedDesktop.getSendChainKey(),
            loadedMobile.getSendChainKey()), "不同设备应是独立棘轮链");

        java.util.List<String> peers = store.listPeers("alice");
        assertEquals(2, peers.size(), "同一对端的两个设备应是两条会话，实际：" + peers);
    }

    @Test
    @DisplayName("会话文件不能跨设备搬运（AAD 绑定设备）")
    void sessionFileCannotBeMovedAcrossDevices(@TempDir Path dir) throws Exception {
        SessionStore store = new SessionStore(dir);
        RatchetService ratchet = new RatchetService();
        SessionState state = ratchet.initiatorSession("bob",
            DhKeyUtil.generateKeyPair().getPrivate(), "dummy",
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()),
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()), null).getState();
        store.save("alice", "bob", "desktop", state, "pw");

        // 把 desktop 的文件改名成 mobile，AAD 不一致应当解密失败
        Path desktopFile = dir.resolve("alice~bob~desktop.session");
        Path mobileFile = dir.resolve("alice~bob~mobile.session");
        Files.copy(desktopFile, mobileFile);
        assertThrows(IdentityException.class, () -> store.load("alice", "bob", "mobile", "pw"));
    }

    // ==================== 预密钥库 ====================

    @Test
    @DisplayName("预密钥私钥加密落盘并可用；一次性预密钥取出后即消失")
    void preKeyStoreRoundTrip(@TempDir Path dir) throws Exception {
        PreKeyStore store = new PreKeyStore(dir);
        KeyPair identity = DhKeyUtil.generateKeyPair();

        PreKeyStore.LocalSignedPreKey signed =
            store.generateSignedPreKey(identity.getPrivate(), 3);
        store.setSignedPreKey(signed);
        java.util.List<PreKeyStore.LocalOneTimePreKey> oneTime = store.generateOneTimePreKeys(5);
        store.addOneTimePreKeys(oneTime);
        store.save("alice", "pw");

        PreKeyStore reloaded = new PreKeyStore(dir);
        assertTrue(reloaded.load("alice", "pw"));
        assertNotNull(reloaded.getSignedPreKey());
        assertEquals(3, reloaded.getSignedPreKey().getPreKeyId());
        assertEquals(5, reloaded.oneTimeKeyCount());

        int consumedId = oneTime.get(0).getPreKeyId();
        assertNotNull(reloaded.consumeOneTimePreKey(consumedId));
        assertNull(reloaded.consumeOneTimePreKey(consumedId), "OPK 只能被消费一次");
        assertFalse(reloaded.hasOneTimePreKey(consumedId));
    }

    @Test
    @DisplayName("预密钥文件不含明文私钥")
    void preKeyFileEncrypted(@TempDir Path dir) throws Exception {
        PreKeyStore store = new PreKeyStore(dir);
        KeyPair identity = DhKeyUtil.generateKeyPair();
        store.setSignedPreKey(store.generateSignedPreKey(identity.getPrivate(), 1));
        store.addOneTimePreKeys(store.generateOneTimePreKeys(2));
        store.save("alice", "pw");

        String content = new String(Files.readAllBytes(dir.resolve("alice__default.prekeys")), "UTF-8");
        assertFalse(content.contains("PRIVATE KEY"));
        assertFalse(content.contains("spkPrivate="));

        PreKeyStore wrong = new PreKeyStore(dir);
        assertThrows(IdentityException.class, () -> wrong.load("alice", "wrong"));
    }
}
