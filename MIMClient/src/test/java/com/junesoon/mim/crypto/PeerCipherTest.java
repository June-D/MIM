package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPair;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 双方在收到对方消息前各自发起会话。这是界面上「全部无法解密」的复现：
 * 两条 X3DH 链密钥不同，后建立的那条不能覆盖先发出去的那条。
 */
class PeerCipherTest {

    private static final class Env {
        final RatchetService ratchet = new RatchetService();
        final KeyPair aliceId = DhKeyUtil.generateKeyPair();
        final KeyPair bobId = DhKeyUtil.generateKeyPair();
        final KeyPair aliceSpk = DhKeyUtil.generateKeyPair();
        final KeyPair bobSpk = DhKeyUtil.generateKeyPair();
        final String aliceIdPub = DhKeyUtil.encodePublicKey(aliceId.getPublic());
        final String bobIdPub = DhKeyUtil.encodePublicKey(bobId.getPublic());
        final String aliceSpkPub = DhKeyUtil.encodePublicKey(aliceSpk.getPublic());
        final String bobSpkPub = DhKeyUtil.encodePublicKey(bobSpk.getPublic());
    }

    @Test
    @DisplayName("双方都已发起时，仍能解开对方的消息，且失败的解密不毁掉发送链")
    void simultaneousInitBothDirectionsSurviveBadCiphertext() throws Exception {
        Env env = new Env();
        RatchetService.InitiatorResult aliceInit = env.ratchet.initiatorSession("bob",
            env.aliceId.getPrivate(), env.aliceIdPub, env.bobIdPub, env.bobSpkPub, null);
        RatchetService.InitiatorResult bobInit = env.ratchet.initiatorSession("alice",
            env.bobId.getPrivate(), env.bobIdPub, env.aliceIdPub, env.aliceSpkPub, null);

        PeerCipher alice = new PeerCipher(env.ratchet);
        alice.setOutbound(aliceInit.getState());
        PeerCipher bob = new PeerCipher(env.ratchet);
        bob.setOutbound(bobInit.getState());

        RatchetService.Encrypted fromAlice = env.ratchet.encrypt(alice.sendingState(), "alice-1");
        byte[] bobSendRoot = bob.getOutbound().getRootKey().clone();
        String alicePlain = bob.open(true, aliceInit.getSenderEphemeralKey(), fromAlice.getHeader(),
            fromAlice.getIv(), fromAlice.getCiphertext(),
            () -> env.ratchet.responderSession("alice", env.bobId.getPrivate(), env.bobIdPub,
                env.bobSpk.getPrivate(), env.bobSpkPub, null,
                aliceInit.getSenderIdentityKey(), aliceInit.getSenderEphemeralKey(), false));
        assertEquals("alice-1", alicePlain);
        assertArrayEquals(bobSendRoot, bob.getOutbound().getRootKey(), "解开对方的发起链不能改我方发送链");

        RatchetService.Encrypted fromBob = env.ratchet.encrypt(bob.sendingState(), "bob-1");
        byte[] aliceSendRoot = alice.getOutbound().getRootKey().clone();
        assertEquals("bob-1", alice.open(true, bobInit.getSenderEphemeralKey(), fromBob.getHeader(),
            fromBob.getIv(), fromBob.getCiphertext(),
            () -> env.ratchet.responderSession("bob", env.aliceId.getPrivate(), env.aliceIdPub,
                env.aliceSpk.getPrivate(), env.aliceSpkPub, null,
                bobInit.getSenderIdentityKey(), bobInit.getSenderEphemeralKey(), false)));
        assertArrayEquals(aliceSendRoot, alice.getOutbound().getRootKey());

        RatchetService.Encrypted alice2 = env.ratchet.encrypt(alice.sendingState(), "alice-2");
        assertEquals("alice-2", bob.open(true, aliceInit.getSenderEphemeralKey(), alice2.getHeader(),
            alice2.getIv(), alice2.getCiphertext(), () -> {
                throw new IdentityException("同一条链不应重新握手");
            }));

        byte[] raw = Base64.getDecoder().decode(alice2.getCiphertext());
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThrows(IdentityException.class, () -> bob.open(true, aliceInit.getSenderEphemeralKey(),
            alice2.getHeader(), alice2.getIv(), tampered, null));

        RatchetService.Encrypted alice3 = env.ratchet.encrypt(alice.sendingState(), "alice-3");
        assertEquals("alice-3", bob.open(true, aliceInit.getSenderEphemeralKey(), alice3.getHeader(),
            alice3.getIv(), alice3.getCiphertext(), null));

        RatchetService.Encrypted bob2 = env.ratchet.encrypt(bob.sendingState(), "bob-2");
        assertEquals("bob-2", alice.open(true, bobInit.getSenderEphemeralKey(), bob2.getHeader(),
            bob2.getIv(), bob2.getCiphertext(), null));
    }

    @Test
    @DisplayName("重启后没记下临时公钥时，同一条握手链的下一条仍能解开，不再重新握手")
    void continuationWithHandshakeDoesNotNeedOneTimePreKeyAgain() throws Exception {
        Env env = new Env();
        RatchetService.InitiatorResult aliceInit = env.ratchet.initiatorSession("bob",
            env.aliceId.getPrivate(), env.aliceIdPub, env.bobIdPub, env.bobSpkPub, null);
        PeerCipher alice = new PeerCipher(env.ratchet);
        alice.setOutbound(aliceInit.getState());
        PeerCipher bob = new PeerCipher(env.ratchet);

        RatchetService.Encrypted first = env.ratchet.encrypt(alice.sendingState(), "first");
        assertEquals("first", bob.open(true, aliceInit.getSenderEphemeralKey(), first.getHeader(),
            first.getIv(), first.getCiphertext(),
            () -> env.ratchet.responderSession("alice", env.bobId.getPrivate(), env.bobIdPub,
                env.bobSpk.getPrivate(), env.bobSpkPub, null,
                aliceInit.getSenderIdentityKey(), aliceInit.getSenderEphemeralKey(), false)));

        // 旧会话文件只在「还要发出去的握手」时才保存临时公钥。应答方只有这个公钥，
        // 重启后它是空的。下一条消息仍然带着当初的握手头。
        bob.getInbound().setHandshakeSenderEphemeralKey(null);
        RatchetService.Encrypted second = env.ratchet.encrypt(alice.sendingState(), "second");
        assertEquals("second", bob.open(true, aliceInit.getSenderEphemeralKey(), second.getHeader(),
            second.getIv(), second.getCiphertext(), () -> {
                throw new IdentityException("一次性预密钥已经用过，不能再握手");
            }));
        assertEquals(aliceInit.getSenderEphemeralKey(),
            bob.getInbound().getHandshakeSenderEphemeralKey());
    }

    @Test
    @DisplayName("只有一方发起时，应答方用收到的链回复，发起方用原链接着解")
    void singleInitiatorRoundTrip() throws Exception {
        Env env = new Env();
        RatchetService.InitiatorResult aliceInit = env.ratchet.initiatorSession("bob",
            env.aliceId.getPrivate(), env.aliceIdPub, env.bobIdPub, env.bobSpkPub, null);
        PeerCipher alice = new PeerCipher(env.ratchet);
        alice.setOutbound(aliceInit.getState());
        PeerCipher bob = new PeerCipher(env.ratchet);

        RatchetService.Encrypted first = env.ratchet.encrypt(alice.sendingState(), "hello");
        assertEquals("hello", bob.open(true, aliceInit.getSenderEphemeralKey(), first.getHeader(),
            first.getIv(), first.getCiphertext(),
            () -> env.ratchet.responderSession("alice", env.bobId.getPrivate(), env.bobIdPub,
                env.bobSpk.getPrivate(), env.bobSpkPub, null,
                aliceInit.getSenderIdentityKey(), aliceInit.getSenderEphemeralKey(), false)));

        RatchetService.Encrypted reply = env.ratchet.encrypt(bob.sendingState(), "hi");
        assertEquals("hi", alice.open(false, null, reply.getHeader(), reply.getIv(),
            reply.getCiphertext(), null));
    }
}
