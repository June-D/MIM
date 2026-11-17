package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.security.KeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 「双方各自发起会话」冲突的回归测试。
 *
 * <p>这是最隐蔽也最致命的一类缺陷：A 和 B 互相发消息时，两人各自调用了
 * {@code initiatorSession()}，于是产生<b>两条互不相关的棘轮链</b>。
 * 表面上看会话都建好了、消息都发出去了，但双方永远解不开对方的密文。
 *
 * <p>正确的处理：收到对方带 X3DH 握手参数的消息时，说明对方是这条会话的发起方，
 * 我方必须以应答方身份用对方参数重建接收链，而不是沿用自己那条发起方链。
 */
class BothInitiatorConflictTest {

    private static final class Env {
        final KeyPair aliceId = DhKeyUtil.generateKeyPair();
        final KeyPair bobId = DhKeyUtil.generateKeyPair();
        final KeyPair bobSpk = DhKeyUtil.generateKeyPair();
        final KeyPair bobOpk = DhKeyUtil.generateKeyPair();
        final KeyPair aliceSpk = DhKeyUtil.generateKeyPair();
        final KeyPair aliceOpk = DhKeyUtil.generateKeyPair();

        final String aliceIdPub = DhKeyUtil.encodePublicKey(aliceId.getPublic());
        final String bobIdPub = DhKeyUtil.encodePublicKey(bobId.getPublic());
    }

    @Test
    @DisplayName("双方各自发起时，沿用自己那条链会解密失败（说明确实需要重建）")
    void ownInitiatorChainCannotDecryptPeerMessage() throws Exception {
        RatchetService ratchet = new RatchetService();
        Env env = new Env();

        // bob 发起（给 alice）
        RatchetService.InitiatorResult bobInit = ratchet.initiatorSession("alice",
            env.bobId.getPrivate(), env.bobIdPub, env.aliceIdPub,
            DhKeyUtil.encodePublicKey(env.aliceSpk.getPublic()), null);
        SessionState bobState = bobInit.getState();
        bobState.setInitiator(true);

        // alice 也发起（给 bob）—— 双方都成了发起方
        RatchetService.InitiatorResult aliceInit = ratchet.initiatorSession("bob",
            env.aliceId.getPrivate(), env.aliceIdPub, env.bobIdPub,
            DhKeyUtil.encodePublicKey(env.bobSpk.getPublic()), null);
        RatchetService.Encrypted aliceMsg = ratchet.encrypt(aliceInit.getState(), "alice 的消息");

        // 用 bob 自己那条发起方链去解 alice 的密文：必然失败
        boolean failed = false;
        try {
            ratchet.decrypt(bobState, aliceMsg.getHeader(), aliceMsg.getIv(),
                aliceMsg.getCiphertext());
        } catch (Exception e) {
            failed = true;
        }
        assertTrue(failed, "各自的发起方链互不兼容，直接解密必然失败——这就是 bug 的表现");
    }

    @Test
    @DisplayName("收到对方带握手参数时以应答方身份重建，即可解密")
    void rebuildingAsResponderWorks() throws Exception {
        RatchetService ratchet = new RatchetService();
        Env env = new Env();

        // bob 先自己发起过（本地残留一条发起方链）
        RatchetService.InitiatorResult bobOwn = ratchet.initiatorSession("alice",
            env.bobId.getPrivate(), env.bobIdPub, env.aliceIdPub,
            DhKeyUtil.encodePublicKey(env.aliceSpk.getPublic()), null);
        SessionState staleBobState = bobOwn.getState();
        staleBobState.setInitiator(true);

        // alice 发来一条带握手参数的消息
        RatchetService.InitiatorResult aliceInit = ratchet.initiatorSession("bob",
            env.aliceId.getPrivate(), env.aliceIdPub, env.bobIdPub,
            DhKeyUtil.encodePublicKey(env.bobSpk.getPublic()), null);
        RatchetService.Encrypted aliceMsg = ratchet.encrypt(aliceInit.getState(), "alice 的消息");

        // 正确做法：以应答方身份，用 alice 传来的参数重建
        SessionState responder = ratchet.responderSession("alice", env.bobId.getPrivate(),
            env.bobIdPub, env.bobSpk.getPrivate(),
            DhKeyUtil.encodePublicKey(env.bobSpk.getPublic()), null,
            aliceInit.getSenderIdentityKey(), aliceInit.getSenderEphemeralKey(), false);
        responder.setInitiator(false);

        String plain = ratchet.decrypt(responder, aliceMsg.getHeader(), aliceMsg.getIv(),
            aliceMsg.getCiphertext());
        assertEquals("alice 的消息", plain, "以应答方身份重建后必须能解密");
        assertFalse(responder.isInitiator(), "重建后角色应变为应答方");
    }

    @Test
    @DisplayName("重建后继续双向收发正常")
    void bidirectionalAfterRebuild() throws Exception {
        RatchetService ratchet = new RatchetService();
        Env env = new Env();

        // alice 发起
        RatchetService.InitiatorResult aliceInit = ratchet.initiatorSession("bob",
            env.aliceId.getPrivate(), env.aliceIdPub, env.bobIdPub,
            DhKeyUtil.encodePublicKey(env.bobSpk.getPublic()), null);
        SessionState alice = aliceInit.getState();
        RatchetService.Encrypted a1 = ratchet.encrypt(alice, "A1");

        // bob 以应答方重建
        SessionState bob = ratchet.responderSession("alice", env.bobId.getPrivate(),
            env.bobIdPub, env.bobSpk.getPrivate(),
            DhKeyUtil.encodePublicKey(env.bobSpk.getPublic()), null,
            aliceInit.getSenderIdentityKey(), aliceInit.getSenderEphemeralKey(), false);
        bob.setInitiator(false);

        assertEquals("A1", ratchet.decrypt(bob, a1.getHeader(), a1.getIv(), a1.getCiphertext()));

        // bob 回复，alice 收到
        RatchetService.Encrypted b1 = ratchet.encrypt(bob, "B1");
        assertEquals("B1", ratchet.decrypt(alice, b1.getHeader(), b1.getIv(), b1.getCiphertext()));

        // alice 再发
        RatchetService.Encrypted a2 = ratchet.encrypt(alice, "A2");
        assertEquals("A2", ratchet.decrypt(bob, a2.getHeader(), a2.getIv(), a2.getCiphertext()));
    }

    @Test
    @DisplayName("会话角色可以持久化，重载后仍知道自己是发起方还是应答方")
    void rolePersistedAcrossReload(@TempDir Path dir) throws Exception {
        RatchetService ratchet = new RatchetService();
        Env env = new Env();
        SessionStore store = new SessionStore(dir);

        RatchetService.InitiatorResult init = ratchet.initiatorSession("bob",
            env.aliceId.getPrivate(), env.aliceIdPub, env.bobIdPub,
            DhKeyUtil.encodePublicKey(env.bobSpk.getPublic()), null);
        SessionState state = init.getState();
        state.setInitiator(true);
        state.setDeviceId("bob-dev");
        store.save("alice", "bob", "bob-dev", state, "pw");

        SessionState reloaded = store.load("alice", "bob", "bob-dev", "pw");
        assertNotNull(reloaded);
        assertTrue(reloaded.isInitiator(), "重载后应保留发起方角色");

        // 应答方角色同样要保留
        SessionState responder = ratchet.responderSession("alice", env.bobId.getPrivate(),
            env.bobIdPub, env.bobSpk.getPrivate(),
            DhKeyUtil.encodePublicKey(env.bobSpk.getPublic()), null,
            init.getSenderIdentityKey(), init.getSenderEphemeralKey(), false);
        responder.setInitiator(false);
        responder.setDeviceId("alice-dev");
        store.save("bob", "alice", "alice-dev", responder, "pw");

        SessionState reloadedResponder = store.load("bob", "alice", "alice-dev", "pw");
        assertFalse(reloadedResponder.isInitiator(), "重载后应保留应答方角色");
    }
}
