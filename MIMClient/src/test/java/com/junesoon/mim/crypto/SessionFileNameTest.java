package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 会话文件命名与列举的一致性测试。
 *
 * <p>锁住一个真实缺陷：旧版本用 {@code username__peer__device.session}，
 * 用户名/设备名本身允许下划线，含 {@code __} 时解析会错位。现在改用 {@code ~}
 * 分隔，同时兼容读取旧文件。
 */
class SessionFileNameTest {

    @Test
    @DisplayName("listPeers 能正确解析出 对端@设备")
    void listPeersParsesDeviceSegment(@TempDir Path dir) throws Exception {
        SessionStore store = new SessionStore(dir);
        RatchetService ratchet = new RatchetService();

        String deviceId = "HsRWnx6N02pdDuOu2Z2yXg";
        SessionState state = ratchet.initiatorSession("alice",
            DhKeyUtil.generateKeyPair().getPrivate(), "dummy",
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()),
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()), null).getState();
        store.save("bob", "alice", deviceId, state, "pw");

        java.util.List<String> peers = store.listPeers("bob");
        assertEquals(1, peers.size(), "实际：" + peers);
        assertEquals("alice@" + deviceId, peers.get(0),
            "解析结果必须是 对端@设备，设备段不能被截断或错位");
    }

    @Test
    @DisplayName("设备标识含下划线时仍能正确解析")
    void handlesDeviceIdWithUnderscore(@TempDir Path dir) throws Exception {
        SessionStore store = new SessionStore(dir);
        RatchetService ratchet = new RatchetService();

        String deviceId = "my_device_01";
        SessionState state = ratchet.initiatorSession("alice",
            DhKeyUtil.generateKeyPair().getPrivate(), "dummy",
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()),
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()), null).getState();
        store.save("bob", "alice", deviceId, state, "pw");

        java.util.List<String> peers = store.listPeers("bob");
        assertEquals("alice@" + deviceId, peers.get(0),
            "设备标识可能包含下划线，解析必须按最后一个分隔符切");
    }

    @Test
    @DisplayName("设备标识含双下划线时不会被误拆")
    void handlesDeviceIdWithDoubleUnderscore(@TempDir Path dir) throws Exception {
        SessionStore store = new SessionStore(dir);
        RatchetService ratchet = new RatchetService();

        String deviceId = "my__device_01";
        SessionState state = ratchet.initiatorSession("alice",
            DhKeyUtil.generateKeyPair().getPrivate(), "dummy",
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()),
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()), null).getState();
        store.save("bob", "alice", deviceId, state, "pw");

        java.util.List<String> peers = store.listPeers("bob");
        assertTrue(peers.contains("alice@" + deviceId),
            "新命名用 ~ 分隔，设备名里的 __ 不应造成歧义。实际：" + peers);
    }

    @Test
    @DisplayName("保存后重新加载，会话键与保存时一致")
    void reloadedKeyMatchesSavedKey(@TempDir Path dir) throws Exception {
        SessionStore store = new SessionStore(dir);
        RatchetService ratchet = new RatchetService();
        String deviceId = "lPACv3ErgrrvWOtW3nccTA";

        SessionState state = ratchet.initiatorSession("alice",
            DhKeyUtil.generateKeyPair().getPrivate(), "dummy",
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()),
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()), null).getState();
        state.setDeviceId(deviceId);
        store.save("bob", "alice", deviceId, state, "pw");

        // 模拟 loadSessions：从 listPeers 解析出 peer@device 再 load
        String entry = store.listPeers("bob").get(0);
        int at = entry.lastIndexOf('@');
        String peer = entry.substring(0, at);
        String device = entry.substring(at + 1);

        assertEquals("alice", peer);
        assertEquals(deviceId, device, "设备段必须与保存时完全一致");

        SessionState loaded = store.load("bob", peer, device, "pw");
        assertTrue(loaded != null, "按解析出的 peer/device 应能加载成功");
        assertEquals(peer, loaded.getPeer());
        assertEquals(deviceId, loaded.getDeviceId());
    }

    @Test
    @DisplayName("应答链只记下临时公钥时，重启后仍能认出同一条握手")
    void responderEphemeralSurvivesReload(@TempDir Path dir) throws Exception {
        SessionStore store = new SessionStore(dir);
        SessionState state = new SessionState();
        state.setPeer("alice");
        state.setInitiator(false);
        state.setHandshakeSenderEphemeralKey("peer-ephemeral");

        store.saveInbound("bob", "alice", "dev-1", state, "pw");
        SessionState loaded = store.loadInbound("bob", "alice", "dev-1", "pw");

        assertEquals("peer-ephemeral", loaded.getHandshakeSenderEphemeralKey());
        assertNull(loaded.getHandshakeSenderIdentityKey());
    }
}
