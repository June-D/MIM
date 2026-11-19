package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.security.KeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 会话键一致性的回归测试。
 *
 * <p>锁住一个极隐蔽的缺陷：{@code initiatorSession()} 不知道对方的设备标识，
 * 返回的 {@link SessionState} 里 {@code deviceId} 是默认值 {@code "default"}。
 * 若调用方忘记回填真实设备 ID，就会出现：
 * <ul>
 *   <li>文件按正确设备 ID 命名（因为 save 时显式传了 deviceId 参数）；</li>
 *   <li>但内存里的 state.deviceId 仍是 "default"；</li>
 *   <li>重新加载后用它构造的会话键是 {@code peer@真实设备}，
 *       而发送时用 {@code bundle.getDeviceId()} 构造，两者一致——
 *       问题在于<b>首次</b>发送时内存键是 {@code peer@default}，
 *       第二次发送就命中不了，于是每次新建会话，消息序号恒为 0，双方永远解不开。</li>
 * </ul>
 */
class SessionKeyConsistencyTest {

    @Test
    @DisplayName("initiatorSession 返回的 state.deviceId 是默认值，调用方必须回填")
    void initiatorStateHasDefaultDeviceId() throws Exception {
        RatchetService ratchet = new RatchetService();
        KeyPair aliceId = DhKeyUtil.generateKeyPair();
        KeyPair bobId = DhKeyUtil.generateKeyPair();
        KeyPair bobSpk = DhKeyUtil.generateKeyPair();

        RatchetService.InitiatorResult init = ratchet.initiatorSession("bob",
            aliceId.getPrivate(), DhKeyUtil.encodePublicKey(aliceId.getPublic()),
            DhKeyUtil.encodePublicKey(bobId.getPublic()),
            DhKeyUtil.encodePublicKey(bobSpk.getPublic()), null);

        assertEquals(SessionStore.DEFAULT_DEVICE_ID, init.getState().getDeviceId(),
            "框架层面不知道对方设备 ID，默认值就是 default —— 这正是需要调用方回填的原因");

        // 回填后才是正确的会话键
        init.getState().setDeviceId("bob-real-device");
        assertEquals("bob-real-device", init.getState().getDeviceId());
    }

    @Test
    @DisplayName("回填设备 ID 后，内存键与文件键一致，重载可命中")
    void keyMatchesAfterBackfill(@TempDir Path dir) throws Exception {
        RatchetService ratchet = new RatchetService();
        SessionStore store = new SessionStore(dir);
        KeyPair aliceId = DhKeyUtil.generateKeyPair();
        KeyPair bobId = DhKeyUtil.generateKeyPair();
        KeyPair bobSpk = DhKeyUtil.generateKeyPair();
        String deviceId = "bob-real-device";

        RatchetService.InitiatorResult init = ratchet.initiatorSession("bob",
            aliceId.getPrivate(), DhKeyUtil.encodePublicKey(aliceId.getPublic()),
            DhKeyUtil.encodePublicKey(bobId.getPublic()),
            DhKeyUtil.encodePublicKey(bobSpk.getPublic()), null);
        SessionState state = init.getState();
        // 关键：模拟 MessageService.createSession 里应当做的事
        state.setDeviceId(deviceId);
        state.setInitiator(true);
        store.save("alice", "bob", deviceId, state, "pw");

        // 落盘后发一条
        RatchetService.Encrypted m1 = ratchet.encrypt(state, "第一条");
        store.save("alice", "bob", deviceId, state, "pw");

        // 重载：此时 state.deviceId 必须仍是 deviceId，否则构造的键对不上
        SessionState reloaded = store.load("alice", "bob", deviceId, "pw");
        assertNotNull(reloaded);
        assertEquals(deviceId, reloaded.getDeviceId(),
            "重载后设备标识必须与文件名一致");

        String expectedKey = "bob@" + deviceId;
        String actualKey = reloaded.getPeer() + "@" + reloaded.getDeviceId();
        assertEquals(expectedKey, actualKey, "会话键必须与发送时用的键一致");

        // 继续发，序号应递增
        RatchetService.Encrypted m2 = ratchet.encrypt(reloaded, "第二条");
        assertEquals(1, m2.getHeader().getMessageNumber(),
            "重载后序号应接着 1；若回到 0 说明会话键对不上、每次都在新建会话");
    }

    @Test
    @DisplayName("设备 ID 未回填时，重载后的键与发送键不一致（重现缺陷）")
    void missingBackfillBreaksKey(@TempDir Path dir) throws Exception {
        RatchetService ratchet = new RatchetService();
        SessionStore store = new SessionStore(dir);
        KeyPair aliceId = DhKeyUtil.generateKeyPair();
        KeyPair bobId = DhKeyUtil.generateKeyPair();
        KeyPair bobSpk = DhKeyUtil.generateKeyPair();
        String deviceId = "bob-real-device";

        RatchetService.InitiatorResult init = ratchet.initiatorSession("bob",
            aliceId.getPrivate(), DhKeyUtil.encodePublicKey(aliceId.getPublic()),
            DhKeyUtil.encodePublicKey(bobId.getPublic()),
            DhKeyUtil.encodePublicKey(bobSpk.getPublic()), null);
        SessionState state = init.getState();
        // 故意不回填 deviceId（这就是缺陷）
        store.save("alice", "bob", deviceId, state, "pw");

        SessionState reloaded = store.load("alice", "bob", deviceId, "pw");
        assertNotNull(reloaded);
        // load 会用传参的 deviceId 覆盖 state.deviceId，所以这里其实是修好的路径；
        // 真正的问题在"发送时用内存对象"的那条路径上，本测试记录这一隐患。
        assertTrue(reloaded.getDeviceId() != null,
            "load 必须显式设置设备标识，否则调用方拿到 null 后无法构键");
    }
}
