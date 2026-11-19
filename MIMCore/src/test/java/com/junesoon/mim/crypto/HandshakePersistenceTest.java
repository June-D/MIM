package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 握手参数持久化的回归测试。
 *
 * <p>这里锁住一个真实踩过的严重缺陷：X3DH 握手参数原先只存在内存 Map 里，
 * 进程重启后新发的消息不再携带它们。如果接收方此时还没建立会话
 * （例如刚换设备、本地状态丢失），就永远算不出根密钥，
 * 表现是「消息发出去了，对方全部显示无法解密」。
 */
class HandshakePersistenceTest {

    /**
     * 一对参与方的最小环境。
     */
    private static final class Party {
        final String name;
        final String deviceId;
        final KeyPair identity;
        final String publicKey;

        Party(String name, String deviceId) {
            this.name = name;
            this.deviceId = deviceId;
            this.identity = DhKeyUtil.generateKeyPair();
            this.publicKey = DhKeyUtil.encodePublicKey(identity.getPublic());
        }
    }

    @Test
    @DisplayName("握手参数随会话持久化：重载后仍能读到")
    void handshakeSurvivesSessionReload(@TempDir Path dir) throws Exception {
        SessionStore store = new SessionStore(dir);
        RatchetService ratchet = new RatchetService();

        Party alice = new Party("alice", "alice-dev");
        Party bob = new Party("bob", "bob-dev");
        KeyPair bobSpk = DhKeyUtil.generateKeyPair();
        KeyPair bobOpk = DhKeyUtil.generateKeyPair();

        RatchetService.InitiatorResult init = ratchet.initiatorSession("bob",
            alice.identity.getPrivate(), alice.publicKey, bob.publicKey,
            DhKeyUtil.encodePublicKey(bobSpk.getPublic()),
            DhKeyUtil.encodePublicKey(bobOpk.getPublic()));

        // 模拟 MessageService 的做法：把握手参数写进状态
        SessionState state = init.getState();
        state.setHandshakeSenderIdentityKey(init.getSenderIdentityKey());
        state.setHandshakeSenderEphemeralKey(init.getSenderEphemeralKey());
        state.setHandshakeUsedOneTimePreKey(init.isUsedOneTimePreKey());
        state.setHandshakeOneTimePreKeyId(3);

        assertTrue(state.hasPendingHandshake());
        store.save("alice", "bob", "bob-dev", state, "pw");

        // 关键断言：重载后握手参数仍在
        SessionState reloaded = store.load("alice", "bob", "bob-dev", "pw");
        assertNotNull(reloaded);
        assertTrue(reloaded.hasPendingHandshake(), "重载后握手参数不应丢失");
        assertEquals(init.getSenderIdentityKey(), reloaded.getHandshakeSenderIdentityKey());
        assertEquals(init.getSenderEphemeralKey(), reloaded.getHandshakeSenderEphemeralKey());
        assertTrue(reloaded.isHandshakeUsedOneTimePreKey());
        assertEquals(3, reloaded.getHandshakeOneTimePreKeyId());
    }

    @Test
    @DisplayName("用重载后的握手参数，接收方仍能建出会话并解密")
    void receiverCanStillEstablishAfterReload(@TempDir Path dir) throws Exception {
        SessionStore store = new SessionStore(dir);
        RatchetService ratchet = new RatchetService();

        Party alice = new Party("alice", "alice-dev");
        Party bob = new Party("bob", "bob-dev");
        KeyPair bobSpk = DhKeyUtil.generateKeyPair();
        KeyPair bobOpk = DhKeyUtil.generateKeyPair();
        String bobSpkPub = DhKeyUtil.encodePublicKey(bobSpk.getPublic());
        String bobOpkPub = DhKeyUtil.encodePublicKey(bobOpk.getPublic());

        RatchetService.InitiatorResult init = ratchet.initiatorSession("bob",
            alice.identity.getPrivate(), alice.publicKey, bob.publicKey, bobSpkPub, bobOpkPub);
        SessionState state = init.getState();
        state.setHandshakeSenderIdentityKey(init.getSenderIdentityKey());
        state.setHandshakeSenderEphemeralKey(init.getSenderEphemeralKey());
        state.setHandshakeUsedOneTimePreKey(init.isUsedOneTimePreKey());
        state.setHandshakeOneTimePreKeyId(5);
        store.save("alice", "bob", "bob-dev", state, "pw");

        // 模拟"alice 重启后用重载的会话发消息"
        SessionState reloaded = store.load("alice", "bob", "bob-dev", "pw");
        RatchetService.Encrypted msg = ratchet.encrypt(reloaded, "重启后的消息");

        // bob 用重载出来的握手参数建会话
        SessionState bobState = ratchet.responderSession("alice", bob.identity.getPrivate(),
            bob.publicKey, bobSpk.getPrivate(), bobSpkPub, bobOpk.getPrivate(),
            reloaded.getHandshakeSenderIdentityKey(), reloaded.getHandshakeSenderEphemeralKey(),
            reloaded.isHandshakeUsedOneTimePreKey());

        String plain = ratchet.decrypt(bobState, msg.getHeader(), msg.getIv(),
            msg.getCiphertext());
        assertEquals("重启后的消息", plain, "重载握手参数后应能正常解密");
    }

    @Test
    @DisplayName("没有握手参数的会话，接收方建不出会话（旧缺陷的表现）")
    void missingHandshakeBlocksReceiver() throws Exception {
        RatchetService ratchet = new RatchetService();

        Party alice = new Party("alice", "alice-dev");
        Party bob = new Party("bob", "bob-dev");
        KeyPair bobSpk = DhKeyUtil.generateKeyPair();
        KeyPair bobOpk = DhKeyUtil.generateKeyPair();

        RatchetService.InitiatorResult init = ratchet.initiatorSession("bob",
            alice.identity.getPrivate(), alice.publicKey, bob.publicKey,
            DhKeyUtil.encodePublicKey(bobSpk.getPublic()),
            DhKeyUtil.encodePublicKey(bobOpk.getPublic()));
        RatchetService.Encrypted msg = ratchet.encrypt(init.getState(), "无参数");

        // 传 null 握手参数：模拟旧缺陷下"参数丢了"的情形。
        // 关键是不能静默产生错误密钥——必须明确失败，否则现象是
        // "消息发出去了但对方永远解不开"，极难排查。
        assertThrows(RuntimeException.class, () -> ratchet.responderSession("alice",
            bob.identity.getPrivate(), bob.publicKey, bobSpk.getPrivate(),
            DhKeyUtil.encodePublicKey(bobSpk.getPublic()), null, null, null, false),
            "缺少握手参数时建会话必须失败，而不是静默产生错误密钥");
    }

    @Test
    @DisplayName("接收方会话不带握手参数（它不在发起方位置）")
    void responderStateHasNoPendingHandshake() throws Exception {
        RatchetService ratchet = new RatchetService();
        Party alice = new Party("alice", "alice-dev");
        Party bob = new Party("bob", "bob-dev");
        KeyPair bobSpk = DhKeyUtil.generateKeyPair();
        KeyPair bobOpk = DhKeyUtil.generateKeyPair();
        String bobSpkPub = DhKeyUtil.encodePublicKey(bobSpk.getPublic());

        RatchetService.InitiatorResult init = ratchet.initiatorSession("bob",
            alice.identity.getPrivate(), alice.publicKey, bob.publicKey, bobSpkPub,
            DhKeyUtil.encodePublicKey(bobOpk.getPublic()));

        SessionState bobState = ratchet.responderSession("alice", bob.identity.getPrivate(),
            bob.publicKey, bobSpk.getPrivate(), bobSpkPub, bobOpk.getPrivate(),
            init.getSenderIdentityKey(), init.getSenderEphemeralKey(),
            init.isUsedOneTimePreKey());

        assertTrue(!bobState.hasPendingHandshake(),
            "接收方不需要携带握手参数，它只负责应答");
        assertNull(bobState.getHandshakeSenderIdentityKey());
    }
}
