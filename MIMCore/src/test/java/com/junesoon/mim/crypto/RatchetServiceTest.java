package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * X3DH + 双棘轮的核心算法测试。
 *
 * <p>覆盖点与对应的安全性质：
 * <ul>
 *   <li>会话建立一致性 —— X3DH 双方算出的根密钥相同</li>
 *   <li>多轮棘轮 —— 前向安全性与后向恢复性得以持续</li>
 *   <li>乱序 / 重放 —— 消息密钥缓存与单次消费</li>
 *   <li>OPK 省略 —— 对方一次性预密钥耗尽时仍能建会话</li>
 *   <li>参数错误 —— 用错 OPK 私钥必须解不开</li>
 * </ul>
 */
class RatchetServiceTest {

    /**
     * 一对参与方。alice 发起，bob 应答。
     */
    private static final class Pair {
        final RatchetService ratchet = new RatchetService();
        final KeyPair aliceIdentity = DhKeyUtil.generateKeyPair();
        final KeyPair bobIdentity = DhKeyUtil.generateKeyPair();
        final KeyPair bobSignedPreKey = DhKeyUtil.generateKeyPair();
        final KeyPair bobOneTimePreKey = DhKeyUtil.generateKeyPair();

        final String aliceIdentityPub = DhKeyUtil.encodePublicKey(aliceIdentity.getPublic());
        final String bobIdentityPub = DhKeyUtil.encodePublicKey(bobIdentity.getPublic());
        final String bobSignedPub = DhKeyUtil.encodePublicKey(bobSignedPreKey.getPublic());
        final String bobOneTimePub = DhKeyUtil.encodePublicKey(bobOneTimePreKey.getPublic());

        RatchetService.InitiatorResult initiator;

        SessionState alice;
        SessionState bob;

        Pair(boolean useOneTimePreKey) {
            initiator = ratchet.initiatorSession("bob", aliceIdentity.getPrivate(), aliceIdentityPub,
                bobIdentityPub, bobSignedPub, useOneTimePreKey ? bobOneTimePub : null);
            alice = initiator.getState();
        }

        /**
         * bob 收到首条消息后建会话。
         */
        void bobJoin(boolean usedOneTime) {
            bob = ratchet.responderSession("alice", bobIdentity.getPrivate(), bobIdentityPub,
                bobSignedPreKey.getPrivate(), bobSignedPub,
                usedOneTime ? bobOneTimePreKey.getPrivate() : null,
                initiator.getSenderIdentityKey(), initiator.getSenderEphemeralKey(), usedOneTime);
        }

        RatchetService.Encrypted aliceSend(String text) throws IdentityException {
            return ratchet.encrypt(alice, text);
        }

        String bobRecv(RatchetService.Encrypted msg) throws IdentityException {
            return ratchet.decrypt(bob, msg.getHeader(), msg.getIv(), msg.getCiphertext());
        }

        RatchetService.Encrypted bobSend(String text) throws IdentityException {
            return ratchet.encrypt(bob, text);
        }

        String aliceRecv(RatchetService.Encrypted msg) throws IdentityException {
            return ratchet.decrypt(alice, msg.getHeader(), msg.getIv(), msg.getCiphertext());
        }
    }

    // ==================== X3DH 会话建立 ====================

    @Test
    @DisplayName("X3DH：带一次性预密钥时双方根密钥一致，首条消息可解")
    void x3dhWithOneTimePreKey() throws Exception {
        Pair pair = new Pair(true);
        RatchetService.Encrypted first = pair.aliceSend("hello bob");
        pair.bobJoin(true);
        assertEquals("hello bob", pair.bobRecv(first));
        assertTrue(pair.initiator.isUsedOneTimePreKey());
    }

    @Test
    @DisplayName("X3DH：一次性预密钥耗尽时仍能建会话（省略 DH4）")
    void x3dhWithoutOneTimePreKey() throws Exception {
        Pair pair = new Pair(false);
        RatchetService.Encrypted first = pair.aliceSend("no opk");
        pair.bobJoin(false);
        assertEquals("no opk", pair.bobRecv(first));
        assertFalse(pair.initiator.isUsedOneTimePreKey());
    }

    @Test
    @DisplayName("X3DH：签名预密钥签名可被身份公钥验证，篡改后验证失败")
    void signedPreKeySignature() {
        KeyPair identity = DhKeyUtil.generateKeyPair();
        KeyPair signed = DhKeyUtil.generateKeyPair();
        String identityPub = DhKeyUtil.encodePublicKey(identity.getPublic());
        String signedPub = DhKeyUtil.encodePublicKey(signed.getPublic());
        int preKeyId = 7;

        byte[] payload = DhKeyUtil.signedPreKeyPayload(preKeyId,
            Base64.getDecoder().decode(signedPub));
        String signature = DhKeyUtil.sign(identity.getPrivate(), payload);

        assertTrue(DhKeyUtil.verifySignedPreKey(identityPub, preKeyId, signedPub, signature),
            "正确签名应验证通过");
        assertFalse(DhKeyUtil.verifySignedPreKey(identityPub, preKeyId + 1, signedPub, signature),
            "改编号后签名应失效，防止预密钥被挂到别的 id 上");
        assertFalse(DhKeyUtil.verifySignedPreKey(identityPub, preKeyId,
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()), signature),
            "换公钥后签名应失效");
    }

    @Test
    @DisplayName("X3DH：用错的一次性预密钥私钥无法建立一致会话")
    void x3dhWrongOneTimePreKeyFails() throws Exception {
        Pair pair = new Pair(true);
        RatchetService.Encrypted first = pair.aliceSend("secret");

        // bob 用另一把无关的 OPK 私钥应答，根密钥必然不同
        KeyPair wrongOneTime = DhKeyUtil.generateKeyPair();
        SessionState bobWrong = pair.ratchet.responderSession("alice", pair.bobIdentity.getPrivate(),
            pair.bobIdentityPub, pair.bobSignedPreKey.getPrivate(), pair.bobSignedPub,
            wrongOneTime.getPrivate(), pair.initiator.getSenderIdentityKey(),
            pair.initiator.getSenderEphemeralKey(), true);

        assertThrows(IdentityException.class, () -> pair.ratchet.decrypt(bobWrong,
            first.getHeader(), first.getIv(), first.getCiphertext()),
            "错误的 OPK 私钥不应能解密");
    }

    // ==================== 双棘轮 ====================

    @Test
    @DisplayName("双棘轮：多轮往返后密钥持续更新且能正常解密")
    void alternatingConversation() throws Exception {
        Pair pair = new Pair(true);
        RatchetService.Encrypted first = pair.aliceSend("A1");
        pair.bobJoin(true);
        assertEquals("A1", pair.bobRecv(first));

        assertEquals("B1", pair.aliceRecv(pair.bobSend("B1")));
        assertEquals("A2", pair.bobRecv(pair.aliceSend("A2")));
        assertEquals("B2", pair.aliceRecv(pair.bobSend("B2")));
        assertEquals("A3", pair.bobRecv(pair.aliceSend("A3")));
    }

    @Test
    @DisplayName("双棘轮：相同明文产生不同密文，发送序号递增")
    void chainAdvances() throws Exception {
        Pair pair = new Pair(true);
        RatchetService.Encrypted first = pair.aliceSend("x");
        pair.bobJoin(true);
        pair.bobRecv(first);

        RatchetService.Encrypted m1 = pair.aliceSend("same");
        RatchetService.Encrypted m2 = pair.aliceSend("same");
        assertNotEquals(m1.getCiphertext(), m2.getCiphertext(), "相同明文密文必须不同");
        assertEquals(m1.getHeader().getMessageNumber() + 1, m2.getHeader().getMessageNumber());
    }

    @Test
    @DisplayName("双棘轮：乱序到达的消息依靠缓存密钥解密")
    void outOfOrderDelivery() throws Exception {
        Pair pair = new Pair(true);
        RatchetService.Encrypted first = pair.aliceSend("A1");
        pair.bobJoin(true);
        pair.bobRecv(first);

        RatchetService.Encrypted a2 = pair.aliceSend("A2");
        RatchetService.Encrypted a3 = pair.aliceSend("A3");
        RatchetService.Encrypted a4 = pair.aliceSend("A4");

        assertEquals("A4", pair.bobRecv(a4));
        assertEquals("A2", pair.bobRecv(a2));
        assertEquals("A3", pair.bobRecv(a3));
    }

    @Test
    @DisplayName("双棘轮：重放已解密消息被拒绝")
    void replayRejected() throws Exception {
        Pair pair = new Pair(true);
        RatchetService.Encrypted first = pair.aliceSend("A1");
        pair.bobJoin(true);
        pair.bobRecv(first);

        RatchetService.Encrypted msg = pair.aliceSend("once");
        pair.bobRecv(msg);
        assertThrows(IdentityException.class, () -> pair.bobRecv(msg), "重放应失败");
    }

    @Test
    @DisplayName("双棘轮：跳过过多消息被拒绝（防内存攻击）")
    void tooManySkippedRejected() throws Exception {
        Pair pair = new Pair(true);
        RatchetService.Encrypted first = pair.aliceSend("A1");
        pair.bobJoin(true);
        pair.bobRecv(first);

        RatchetService.Encrypted last = null;
        for (int i = 0; i < 250; i++) {
            last = pair.aliceSend("flood-" + i);
        }
        final RatchetService.Encrypted target = last;
        assertThrows(IdentityException.class, () -> pair.bobRecv(target));
    }

    @Test
    @DisplayName("双棘轮：篡改消息头序号导致解密失败（AAD 生效）")
    void tamperedHeaderRejected() throws Exception {
        Pair pair = new Pair(true);
        RatchetService.Encrypted first = pair.aliceSend("A1");
        pair.bobJoin(true);
        pair.bobRecv(first);

        RatchetService.Encrypted msg = pair.aliceSend("content");
        RatchetService.Header forged = new RatchetService.Header(msg.getHeader().getDhPublic(),
            msg.getHeader().getPreviousChainLength(), msg.getHeader().getMessageNumber() + 1);
        assertThrows(IdentityException.class,
            () -> pair.ratchet.decrypt(pair.bob, forged, msg.getIv(), msg.getCiphertext()));

        RatchetService.Header forgedPn = new RatchetService.Header(msg.getHeader().getDhPublic(),
            msg.getHeader().getPreviousChainLength() + 1, msg.getHeader().getMessageNumber());
        assertThrows(IdentityException.class,
            () -> pair.ratchet.decrypt(pair.bob, forgedPn, msg.getIv(), msg.getCiphertext()));
    }

    @Test
    @DisplayName("解密失败不得推进棘轮，随后的真消息仍能解开")
    void failedDecryptLeavesSessionUsable() throws Exception {
        Pair pair = new Pair(true);
        RatchetService.Encrypted good = pair.aliceSend("hello");
        pair.bobJoin(true);

        byte[] rootBefore = pair.bob.getRootKey().clone();
        String remoteBefore = pair.bob.getDhRemotePublic();
        int recvBefore = pair.bob.getRecvCount();

        // 用真消息头配一份被改过的密文。旧实现会先推进 DH 棘轮再验 GCM，
        // 失败后会话已经废了，紧接着的真消息也解不开——界面上就是「无法解密」。
        byte[] raw = Base64.getDecoder().decode(good.getCiphertext());
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThrows(IdentityException.class, () -> pair.ratchet.decrypt(pair.bob,
            good.getHeader(), good.getIv(), tampered));

        assertArrayEquals(rootBefore, pair.bob.getRootKey(), "失败的解密不能改根密钥");
        assertEquals(remoteBefore, pair.bob.getDhRemotePublic(), "失败的解密不能换对端棘轮公钥");
        assertEquals(recvBefore, pair.bob.getRecvCount(), "失败的解密不能推进接收计数");
        assertEquals("hello", pair.bobRecv(good), "真消息必须仍能解密");
    }

    @Test
    @DisplayName("双棘轮：密文不含明文")
    void ciphertextHidesPlaintext() throws Exception {
        Pair pair = new Pair(true);
        RatchetService.Encrypted msg = pair.aliceSend("绝密内容");
        assertFalse(msg.getCiphertext().contains("绝密"));
        assertFalse(Base64.getDecoder().decode(msg.getCiphertext()).length < 17);
    }
}
