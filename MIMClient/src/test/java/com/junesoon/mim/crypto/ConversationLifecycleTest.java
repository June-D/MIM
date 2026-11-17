package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 完整会话生命周期的回归测试：模拟「同一台机器上两个账号互相发消息」。
 *
 * <p>这个场景正是用户实际使用的场景（单机模拟双人对话），
 * 也是之前反复出现「无法解密」的地方。测试覆盖：
 * <ol>
 *   <li>多轮双向收发，链密钥必须逐条前进（messageNumber 递增）；</li>
 *   <li>会话落盘后重新加载，能接着之前的链继续收发；</li>
 *   <li>重新加载后接收方能解密（这是"切账号后看不到历史消息"的根因）；</li>
 *   <li>多设备场景下，同一用户的两台设备各自独立链。</li>
 * </ol>
 */
class ConversationLifecycleTest {

    /**
     * 一个参与方：身份 + 会话存储。
     */
    private static final class Party {
        final String name;
        final String deviceId;
        final KeyPair identity;
        final String publicKey;
        final SessionStore store;
        final RatchetService ratchet = new RatchetService();
        SessionState state;

        Party(String name, String deviceId, Path dir) {
            this.name = name;
            this.deviceId = deviceId;
            this.identity = DhKeyUtil.generateKeyPair();
            this.publicKey = DhKeyUtil.encodePublicKey(identity.getPublic());
            this.store = new SessionStore(dir);
        }

        void save(String password) throws Exception {
            store.save(name, state.getPeer(), state.getDeviceId(), state, password);
        }

        void reload(String password) throws Exception {
            state = store.load(name, state.getPeer(), state.getDeviceId(), password);
        }
    }

    @Test
    @DisplayName("多轮双向收发：messageNumber 必须递增")
    void messageNumberIncrements() throws Exception {
        RatchetService ratchet = new RatchetService();
        KeyPair aliceId = DhKeyUtil.generateKeyPair();
        KeyPair bobId = DhKeyUtil.generateKeyPair();
        KeyPair bobSpk = DhKeyUtil.generateKeyPair();
        KeyPair bobOpk = DhKeyUtil.generateKeyPair();
        String bobSpkPub = DhKeyUtil.encodePublicKey(bobSpk.getPublic());

        RatchetService.InitiatorResult init = ratchet.initiatorSession("bob",
            aliceId.getPrivate(), DhKeyUtil.encodePublicKey(aliceId.getPublic()),
            DhKeyUtil.encodePublicKey(bobId.getPublic()), bobSpkPub,
            DhKeyUtil.encodePublicKey(bobOpk.getPublic()));

        SessionState alice = init.getState();
        SessionState bob = ratchet.responderSession("alice", bobId.getPrivate(),
            DhKeyUtil.encodePublicKey(bobId.getPublic()), bobSpk.getPrivate(), bobSpkPub,
            bobOpk.getPrivate(), init.getSenderIdentityKey(), init.getSenderEphemeralKey(),
            init.isUsedOneTimePreKey());

        // alice 连发 3 条：序号必须 0,1,2
        RatchetService.Encrypted m1 = ratchet.encrypt(alice, "A1");
        RatchetService.Encrypted m2 = ratchet.encrypt(alice, "A2");
        RatchetService.Encrypted m3 = ratchet.encrypt(alice, "A3");

        assertEquals(0, m1.getHeader().getMessageNumber(), "首条序号应为 0");
        assertEquals(1, m2.getHeader().getMessageNumber(), "第二条应为 1，说明链在前进");
        assertEquals(2, m3.getHeader().getMessageNumber(), "第三条应为 2");

        assertEquals("A1", ratchet.decrypt(bob, m1.getHeader(), m1.getIv(), m1.getCiphertext()));
        assertEquals("A2", ratchet.decrypt(bob, m2.getHeader(), m2.getIv(), m2.getCiphertext()));
        assertEquals("A3", ratchet.decrypt(bob, m3.getHeader(), m3.getIv(), m3.getCiphertext()));
    }

    @Test
    @DisplayName("落盘后重载，能接着原链继续发，且对端能解密")
    void reloadContinuesChain(@TempDir Path dir) throws Exception {
        RatchetService ratchet = new RatchetService();
        KeyPair aliceId = DhKeyUtil.generateKeyPair();
        KeyPair bobId = DhKeyUtil.generateKeyPair();
        KeyPair bobSpk = DhKeyUtil.generateKeyPair();
        KeyPair bobOpk = DhKeyUtil.generateKeyPair();
        String bobSpkPub = DhKeyUtil.encodePublicKey(bobSpk.getPublic());
        String bobIdPub = DhKeyUtil.encodePublicKey(bobId.getPublic());

        RatchetService.InitiatorResult init = ratchet.initiatorSession("bob",
            aliceId.getPrivate(), DhKeyUtil.encodePublicKey(aliceId.getPublic()), bobIdPub,
            bobSpkPub, DhKeyUtil.encodePublicKey(bobOpk.getPublic()));

        SessionState bob = ratchet.responderSession("alice", bobId.getPrivate(), bobIdPub,
            bobSpk.getPrivate(), bobSpkPub, bobOpk.getPrivate(), init.getSenderIdentityKey(),
            init.getSenderEphemeralKey(), init.isUsedOneTimePreKey());

        SessionStore store = new SessionStore(dir);
        SessionState alice = init.getState();
        alice.setDeviceId("bob-dev");
        store.save("alice", "bob", "bob-dev", alice, "pw");

        // 发一条并让 bob 解，然后落盘
        RatchetService.Encrypted first = ratchet.encrypt(alice, "第一条");
        assertEquals("第一条",
            ratchet.decrypt(bob, first.getHeader(), first.getIv(), first.getCiphertext()));
        store.save("alice", "bob", "bob-dev", alice, "pw");

        // 模拟"切账号再切回来"：重新加载
        SessionState reloaded = store.load("alice", "bob", "bob-dev", "pw");
        assertNotNull(reloaded);
        assertEquals(1, reloaded.getSendCount(), "重载后发送计数应保留");

        // 用重载的会话继续发，bob 必须能解密
        RatchetService.Encrypted second = ratchet.encrypt(reloaded, "切账号后的消息");
        assertEquals(1, second.getHeader().getMessageNumber(),
            "重载后序号应接着 1，而不是回到 0");
        assertEquals("切账号后的消息",
            ratchet.decrypt(bob, second.getHeader(), second.getIv(), second.getCiphertext()),
            "重载后继续发的消息，对端必须能解密——这是切账号场景的核心");
    }

    @Test
    @DisplayName("接收方落盘后重载，能解密对方后续消息")
    void receiverReloadKeepsChain(@TempDir Path dir) throws Exception {
        RatchetService ratchet = new RatchetService();
        KeyPair aliceId = DhKeyUtil.generateKeyPair();
        KeyPair bobId = DhKeyUtil.generateKeyPair();
        KeyPair bobSpk = DhKeyUtil.generateKeyPair();
        KeyPair bobOpk = DhKeyUtil.generateKeyPair();
        String bobSpkPub = DhKeyUtil.encodePublicKey(bobSpk.getPublic());
        String bobIdPub = DhKeyUtil.encodePublicKey(bobId.getPublic());

        RatchetService.InitiatorResult init = ratchet.initiatorSession("bob",
            aliceId.getPrivate(), DhKeyUtil.encodePublicKey(aliceId.getPublic()), bobIdPub,
            bobSpkPub, DhKeyUtil.encodePublicKey(bobOpk.getPublic()));

        SessionState alice = init.getState();
        SessionState bob = ratchet.responderSession("alice", bobId.getPrivate(), bobIdPub,
            bobSpk.getPrivate(), bobSpkPub, bobOpk.getPrivate(), init.getSenderIdentityKey(),
            init.getSenderEphemeralKey(), init.isUsedOneTimePreKey());

        SessionStore bobStore = new SessionStore(dir);
        bob.setDeviceId("alice-dev");
        bobStore.save("bob", "alice", "alice-dev", bob, "pw");

        // 第一条：bob 收到并解密
        RatchetService.Encrypted m1 = ratchet.encrypt(alice, "B1");
        assertEquals("B1", ratchet.decrypt(bob, m1.getHeader(), m1.getIv(), m1.getCiphertext()));
        bobStore.save("bob", "alice", "alice-dev", bob, "pw");

        // bob 切账号离开再回来：重载接收状态
        SessionState bobReloaded = bobStore.load("bob", "alice", "alice-dev", "pw");
        assertNotNull(bobReloaded);
        assertEquals(1, bobReloaded.getRecvCount(), "接收计数应保留");

        // alice 继续发，重载后的 bob 必须能解
        RatchetService.Encrypted m2 = ratchet.encrypt(alice, "B2");
        assertEquals("B2",
            ratchet.decrypt(bobReloaded, m2.getHeader(), m2.getIv(), m2.getCiphertext()),
            "接收方重载后必须能解密后续消息——切账号后收不到消息就是这里坏了");
    }

    @Test
    @DisplayName("同一用户两台设备各自独立链，互不干扰")
    void twoDevicesIndependentChains(@TempDir Path dir) throws Exception {
        RatchetService ratchet = new RatchetService();
        KeyPair aliceId = DhKeyUtil.generateKeyPair();
        KeyPair bobPhoneId = DhKeyUtil.generateKeyPair();
        KeyPair bobPadId = DhKeyUtil.generateKeyPair();

        // 分别与两台设备建会话
        KeyPair phoneSpk = DhKeyUtil.generateKeyPair();
        KeyPair padSpk = DhKeyUtil.generateKeyPair();
        String phoneSpkPub = DhKeyUtil.encodePublicKey(phoneSpk.getPublic());
        String padSpkPub = DhKeyUtil.encodePublicKey(padSpk.getPublic());

        RatchetService.InitiatorResult phoneInit = ratchet.initiatorSession("bob",
            aliceId.getPrivate(), DhKeyUtil.encodePublicKey(aliceId.getPublic()),
            DhKeyUtil.encodePublicKey(bobPhoneId.getPublic()), phoneSpkPub, null);
        RatchetService.InitiatorResult padInit = ratchet.initiatorSession("bob",
            aliceId.getPrivate(), DhKeyUtil.encodePublicKey(aliceId.getPublic()),
            DhKeyUtil.encodePublicKey(bobPadId.getPublic()), padSpkPub, null);

        SessionState phoneState = phoneInit.getState();
        SessionState padState = padInit.getState();

        RatchetService.Encrypted phoneMsg = ratchet.encrypt(phoneState, "给手机");
        assertEquals(0, phoneMsg.getHeader().getMessageNumber());
        // pad 的链不受影响，仍从 0 起
        RatchetService.Encrypted padMsg = ratchet.encrypt(padState, "给平板");
        assertEquals(0, padMsg.getHeader().getMessageNumber(), "两条链互相独立");

        // 两条链的密文不同（密钥不同）
        assertTrue(!phoneMsg.getCiphertext().equals(padMsg.getCiphertext()));

        // 各自解密
        SessionState phone = ratchet.responderSession("alice", bobPhoneId.getPrivate(),
            DhKeyUtil.encodePublicKey(bobPhoneId.getPublic()), phoneSpk.getPrivate(), phoneSpkPub,
            null, phoneInit.getSenderIdentityKey(), phoneInit.getSenderEphemeralKey(), false);
        assertEquals("给手机", ratchet.decrypt(phone, phoneMsg.getHeader(), phoneMsg.getIv(),
            phoneMsg.getCiphertext()));
    }
}
