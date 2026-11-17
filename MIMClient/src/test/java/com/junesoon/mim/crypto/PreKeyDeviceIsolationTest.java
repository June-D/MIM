package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 预密钥按设备隔离的回归测试。
 *
 * <p>锁住一个真实缺陷：预密钥存储曾用缺省的 "default" 设备标识，
 * 导致新设备读到别的设备的文件、误判「已有签名预密钥」而跳过上传。
 * 后果是服务端没有该设备的预密钥，别人发消息时找不到它，
 * 表现为「消息发出去了，但对方（或自己）永远解不开」。
 */
class PreKeyDeviceIsolationTest {

    private static KeyPair newIdentity() {
        return DhKeyUtil.generateKeyPair();
    }

    @Test
    @DisplayName("不同设备的预密钥文件互相独立")
    void preKeysIsolatedPerDevice(@TempDir Path dir) throws Exception {
        PreKeyStore store = new PreKeyStore(dir);
        KeyPair identity = newIdentity();

        // 设备 A 发布签名预密钥
        PreKeyStore.LocalSignedPreKey signedA =
            store.generateSignedPreKey(identity.getPrivate(), 1);
        store.setSignedPreKey(signedA);
        store.save("alice", "device-a", "pw");

        // 设备 B 是全新的：文件不存在，load 返回 false
        PreKeyStore deviceB = new PreKeyStore(dir);
        assertFalse(deviceB.load("alice", "device-b", "pw"),
            "设备 B 没有预密钥文件，load 应返回 false");

        assertNull(deviceB.getSignedPreKey(),
            "新设备不能读到别的设备的签名预密钥——否则会跳过上传，服务端就缺这个设备的预密钥");
        assertEquals(0, deviceB.oneTimeKeyCount());
    }

    @Test
    @DisplayName("同一设备重新加载能读到自己的预密钥")
    void sameDeviceReloadsItsOwn(@TempDir Path dir) throws Exception {
        PreKeyStore store = new PreKeyStore(dir);
        KeyPair identity = newIdentity();

        PreKeyStore.LocalSignedPreKey signed =
            store.generateSignedPreKey(identity.getPrivate(), 7);
        store.setSignedPreKey(signed);
        store.addOneTimePreKeys(store.generateOneTimePreKeys(5));
        store.save("alice", "device-a", "pw");

        PreKeyStore reloaded = new PreKeyStore(dir);
        assertTrue(reloaded.load("alice", "device-a", "pw"));
        assertNotNull(reloaded.getSignedPreKey());
        assertEquals(7, reloaded.getSignedPreKey().getPreKeyId());
        assertEquals(5, reloaded.oneTimeKeyCount());
    }

    @Test
    @DisplayName("两个设备的预密钥文件路径不同")
    void differentFilesPerDevice(@TempDir Path dir) throws Exception {
        PreKeyStore store = new PreKeyStore(dir);
        KeyPair identity = newIdentity();

        store.setSignedPreKey(store.generateSignedPreKey(identity.getPrivate(), 1));
        store.save("alice", "device-a", "pw");
        store.save("alice", "device-b", "pw");

        long files;
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".prekeys")).count();
        }
        assertEquals(2, files, "两个设备应各有一个预密钥文件");
    }

    @Test
    @DisplayName("不同用户在同一设备标识下也不共享预密钥")
    void differentUsersIsolated(@TempDir Path dir) throws Exception {
        PreKeyStore store = new PreKeyStore(dir);
        KeyPair aliceId = newIdentity();
        KeyPair bobId = newIdentity();

        store.setSignedPreKey(store.generateSignedPreKey(aliceId.getPrivate(), 1));
        store.save("alice", "shared-device", "pw");

        PreKeyStore bobStore = new PreKeyStore(dir);
        bobStore.load("bob", "shared-device", "pw");
        assertNull(bobStore.getSignedPreKey(), "不同用户不能共用预密钥");
    }

    @Test
    @DisplayName("一次性预密钥跨设备不串号")
    void oneTimePreKeysNotShared(@TempDir Path dir) throws Exception {
        PreKeyStore store = new PreKeyStore(dir);
        KeyPair identity = newIdentity();

        store.setSignedPreKey(store.generateSignedPreKey(identity.getPrivate(), 1));
        java.util.List<PreKeyStore.LocalOneTimePreKey> keysA =
            store.generateOneTimePreKeys(3);
        store.addOneTimePreKeys(keysA);
        store.save("alice", "device-a", "pw");

        PreKeyStore deviceB = new PreKeyStore(dir);
        deviceB.load("alice", "device-b", "pw");
        // 设备 B 自己生成，编号可能从 1 开始，但与 A 的私钥不同
        java.util.List<PreKeyStore.LocalOneTimePreKey> keysB =
            deviceB.generateOneTimePreKeys(3);
        assertEquals(3, keysB.size());
        assertNotEquals(keysA.get(0).getPublicKeyBase64(),
            keysB.get(0).getPublicKeyBase64(),
            "不同设备生成的预密钥公钥必须不同");
    }
}
