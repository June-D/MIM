package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.security.KeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 「历史消息污染发送链」的回归测试。
 *
 * <p>这是本项目最难查的一个缺陷，现象是：消息能发出去，但对端全部显示"无法解密"。
 *
 * <p>根因链条：
 * <ol>
 *   <li>打开会话时加载历史消息，逐条调用 {@code decrypt}；</li>
 *   <li>此时本地还没有会话，{@code decrypt} 认为"需要建立应答会话"，于是建了一个；</li>
 *   <li>这个会话是<b>应答方</b>链，没有 X3DH 握手参数，且 sendCount 从 0 开始；</li>
 *   <li>用户随后发送消息时命中这个错误会话，于是：
 *       握手参数为空（对端建不出会话）、序号永远不递增、双方都在应答方位置。</li>
 * </ol>
 *
 * <p>打开历史时可以对带握手参数的消息建立<b>应答链</b>，但必须和我方发起链分开存。
 * 解密失败不能提交棘轮，也不能用应答链覆盖发起链。
 */
class HistoryPollutionTest {

    private static final class Env {
        final KeyPair aliceId = DhKeyUtil.generateKeyPair();
        final KeyPair bobId = DhKeyUtil.generateKeyPair();
        final KeyPair bobSpk = DhKeyUtil.generateKeyPair();
        final String aliceIdPub = DhKeyUtil.encodePublicKey(aliceId.getPublic());
        final String bobIdPub = DhKeyUtil.encodePublicKey(bobId.getPublic());
        final String bobSpkPub = DhKeyUtil.encodePublicKey(bobSpk.getPublic());
    }

    @Test
    @DisplayName("应答方会话没有握手参数——用它发消息，对端必然解不开")
    void responderSessionHasNoHandshake() throws Exception {
        RatchetService ratchet = new RatchetService();
        Env env = new Env();

        // bob 以应答方身份建会话
        RatchetService.InitiatorResult aliceInit = ratchet.initiatorSession("bob",
            env.aliceId.getPrivate(), env.aliceIdPub, env.bobIdPub, env.bobSpkPub, null);
        SessionState bob = ratchet.responderSession("alice", env.bobId.getPrivate(), env.bobIdPub,
            env.bobSpk.getPrivate(), env.bobSpkPub, null,
            aliceInit.getSenderIdentityKey(), aliceInit.getSenderEphemeralKey(), false);
        bob.setInitiator(false);

        assertTrue(!bob.hasPendingHandshake(),
            "应答方会话没有握手参数——这正是被污染后无法向对端发起新会话的原因");
        assertEquals(0, bob.getSendCount(), "应答方会话的发送计数从 0 开始");
    }

    @Test
    @DisplayName("发起方会话带握手参数，序号从 0 正常递增")
    void initiatorSessionIsHealthy() throws Exception {
        RatchetService ratchet = new RatchetService();
        Env env = new Env();

        RatchetService.InitiatorResult init = ratchet.initiatorSession("bob",
            env.aliceId.getPrivate(), env.aliceIdPub, env.bobIdPub, env.bobSpkPub, null);
        SessionState state = init.getState();

        // 设置握手参数（MessageService.createSession 会做这件事）
        state.setHandshakeSenderIdentityKey(init.getSenderIdentityKey());
        state.setHandshakeSenderEphemeralKey(init.getSenderEphemeralKey());

        assertTrue(state.hasPendingHandshake(), "发起方必须带握手参数，否则对端建不出会话");

        RatchetService.Encrypted m1 = ratchet.encrypt(state, "第一条");
        RatchetService.Encrypted m2 = ratchet.encrypt(state, "第二条");
        assertEquals(0, m1.getHeader().getMessageNumber());
        assertEquals(1, m2.getHeader().getMessageNumber(), "序号必须递增");
    }

    @Test
    @DisplayName("对比：被污染的会话 vs 正常会话，链状态完全不同")
    void pollutedVsHealthy() throws Exception {
        RatchetService ratchet = new RatchetService();
        Env env = new Env();

        // 正常：alice 作为发起方
        RatchetService.InitiatorResult init = ratchet.initiatorSession("bob",
            env.aliceId.getPrivate(), env.aliceIdPub, env.bobIdPub, env.bobSpkPub, null);
        SessionState healthy = init.getState();
        healthy.setHandshakeSenderIdentityKey(init.getSenderIdentityKey());
        healthy.setHandshakeSenderEphemeralKey(init.getSenderEphemeralKey());

        // 污染：alice 却以应答方身份建了会话（历史消息解密造成）
        SessionState polluted = ratchet.responderSession("bob", env.aliceId.getPrivate(),
            env.aliceIdPub, env.bobSpk.getPrivate(), env.bobSpkPub, null,
            init.getSenderIdentityKey(), init.getSenderEphemeralKey(), false);
        polluted.setInitiator(false);

        assertTrue(healthy.hasPendingHandshake(), "正常会话带握手参数");
        assertTrue(!polluted.hasPendingHandshake(), "被污染的会话没有握手参数");

        // 两者产出的密文对同一接收方来说只有正常那份能解
        RatchetService.Encrypted healthyMsg = ratchet.encrypt(healthy, "正常");
        SessionState bob = ratchet.responderSession("alice", env.bobId.getPrivate(),
            env.bobIdPub, env.bobSpk.getPrivate(), env.bobSpkPub, null,
            init.getSenderIdentityKey(), init.getSenderEphemeralKey(), false);

        String plain = ratchet.decrypt(bob, healthyMsg.getHeader(), healthyMsg.getIv(),
            healthyMsg.getCiphertext());
        assertEquals("正常", plain, "正常会话的消息必须能解");
    }

    @Test
    @DisplayName("会话状态可完整往返，握手参数不丢")
    void handshakeSurvivesRoundTrip(@TempDir Path dir) throws Exception {
        RatchetService ratchet = new RatchetService();
        SessionStore store = new SessionStore(dir);
        Env env = new Env();

        RatchetService.InitiatorResult init = ratchet.initiatorSession("bob",
            env.aliceId.getPrivate(), env.aliceIdPub, env.bobIdPub, env.bobSpkPub, null);
        SessionState state = init.getState();
        state.setDeviceId("bob-dev");
        state.setInitiator(true);
        state.setHandshakeSenderIdentityKey(init.getSenderIdentityKey());
        state.setHandshakeSenderEphemeralKey(init.getSenderEphemeralKey());

        store.save("alice", "bob", "bob-dev", state, "pw");
        SessionState reloaded = store.load("alice", "bob", "bob-dev", "pw");

        assertNotNull(reloaded);
        assertTrue(reloaded.hasPendingHandshake(), "重载后握手参数必须还在");
        assertTrue(reloaded.isInitiator(), "重载后角色必须还在");
        assertEquals("bob-dev", reloaded.getDeviceId(), "重载后设备标识必须与文件名一致");

        // 重载后发的消息，对端能解
        RatchetService.Encrypted msg = ratchet.encrypt(reloaded, "重载后");
        SessionState bob = ratchet.responderSession("alice", env.bobId.getPrivate(),
            env.bobIdPub, env.bobSpk.getPrivate(), env.bobSpkPub, null,
            reloaded.getHandshakeSenderIdentityKey(), reloaded.getHandshakeSenderEphemeralKey(),
            false);
        assertEquals("重载后", ratchet.decrypt(bob, msg.getHeader(), msg.getIv(),
            msg.getCiphertext()));
    }
}
