package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.security.KeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SealedSessionTest {

    @Test
    @DisplayName("sessionId 与确认状态随会话加密持久化")
    void sessionIdSurvivesReload(@TempDir Path dir) throws Exception {
        RatchetService ratchet = new RatchetService();
        KeyPair alice = DhKeyUtil.generateKeyPair();
        KeyPair bob = DhKeyUtil.generateKeyPair();
        KeyPair bobSpk = DhKeyUtil.generateKeyPair();
        String alicePub = DhKeyUtil.encodePublicKey(alice.getPublic());
        String bobPub = DhKeyUtil.encodePublicKey(bob.getPublic());

        RatchetService.InitiatorResult init = ratchet.initiatorSession("bob",
            alice.getPrivate(), alicePub, bobPub,
            DhKeyUtil.encodePublicKey(bobSpk.getPublic()), null);
        SessionState state = init.getState();
        state.setSessionId("11111111-2222-3333-4444-555555555555");
        state.setSessionIdConfirmed(true);

        SessionStore store = new SessionStore(dir);
        store.save("alice", "bob", "bob-dev", state, "pw");
        SessionState reloaded = store.load("alice", "bob", "bob-dev", "pw");
        assertNotNull(reloaded);
        assertEquals(state.getSessionId(), reloaded.getSessionId());
        assertTrue(reloaded.isSessionIdConfirmed());
    }

    @Test
    @DisplayName("发送端本地明文按对端索引，重启后仍能用于 sealed 历史")
    void sentPlaintextSurvivesReload(@TempDir Path dir) throws Exception {
        PlaintextStore store = new PlaintextStore(dir);
        store.load("alice", "pw");
        store.putSent("bob", "msg-1", "你好");
        store.putSent("carol", "msg-1", "另一个会话");

        PlaintextStore reloaded = new PlaintextStore(dir);
        reloaded.load("alice", "pw");
        assertEquals("你好", reloaded.getSent("bob", "msg-1"));
        assertEquals("另一个会话", reloaded.getSent("carol", "msg-1"));
    }

    @Test
    @DisplayName("PeerCipher 记录最近成功解密的链，供 sessionId 回写")
    void peerCipherRemembersOpenedState() throws Exception {
        RatchetService ratchet = new RatchetService();
        KeyPair alice = DhKeyUtil.generateKeyPair();
        KeyPair bob = DhKeyUtil.generateKeyPair();
        KeyPair bobSpk = DhKeyUtil.generateKeyPair();
        String alicePub = DhKeyUtil.encodePublicKey(alice.getPublic());
        String bobPub = DhKeyUtil.encodePublicKey(bob.getPublic());

        RatchetService.InitiatorResult init = ratchet.initiatorSession("bob",
            alice.getPrivate(), alicePub, bobPub,
            DhKeyUtil.encodePublicKey(bobSpk.getPublic()), null);
        RatchetService.Encrypted encrypted = ratchet.encrypt(init.getState(), "hello");

        PeerCipher cipher = new PeerCipher(ratchet);
        String plaintext = cipher.open(true, init.getSenderEphemeralKey(), encrypted.getHeader(),
            encrypted.getIv(), encrypted.getCiphertext(),
            () -> ratchet.responderSession("alice", bob.getPrivate(), bobPub,
                bobSpk.getPrivate(), DhKeyUtil.encodePublicKey(bobSpk.getPublic()), null,
                init.getSenderIdentityKey(), init.getSenderEphemeralKey(), false));
        assertEquals("hello", plaintext);
        assertNotNull(cipher.getLastOpened());
        assertEquals(cipher.getInbound(), cipher.getLastOpened());
    }
}
