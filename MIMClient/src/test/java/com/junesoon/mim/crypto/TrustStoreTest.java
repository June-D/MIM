package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * TOFU 信任库测试。
 *
 * <p>这是防中间人的核心组件，必须验证：
 * <ul>
 *   <li>首次见到公钥即固定；</li>
 *   <li>公钥变化被识别为 CHANGED 并阻断发送；</li>
 *   <li>用户确认后能更新固定值；</li>
 *   <li>信任库本身加密落盘、错误口令打不开。</li>
 * </ul>
 */
class TrustStoreTest {

    private static String newPublicKey() {
        KeyPair pair = DhKeyUtil.generateKeyPair();
        return DhKeyUtil.encodePublicKey(pair.getPublic());
    }

    @Test
    @DisplayName("首次见到公钥即固定，重复使用返回 TRUSTED")
    void firstUsePinsAndThenTrusts(@TempDir Path dir) throws Exception {
        TrustStore store = new TrustStore(dir);
        store.load("alice", "pw");
        String bobKey = newPublicKey();

        TrustStore.VerificationResult first = store.verify("bob", bobKey, "pw");
        assertEquals(TrustStore.TrustStatus.NEW, first.getStatus());
        assertTrue(first.isSendable());
        assertNotNull(first.getCurrentFingerprint());

        TrustStore.VerificationResult second = store.verify("bob", bobKey, "pw");
        assertEquals(TrustStore.TrustStatus.TRUSTED, second.getStatus());
        assertTrue(second.isSendable());
    }

    @Test
    @DisplayName("公钥变更被识别为 CHANGED 并阻断发送")
    void keyChangeDetected(@TempDir Path dir) throws Exception {
        TrustStore store = new TrustStore(dir);
        store.load("alice", "pw");
        String original = newPublicKey();
        store.verify("bob", original, "pw");

        String attackerKey = newPublicKey();
        TrustStore.VerificationResult changed = store.verify("bob", attackerKey, "pw");
        assertEquals(TrustStore.TrustStatus.CHANGED, changed.getStatus());
        assertFalse(changed.isSendable(), "公钥变更必须阻断发送，否则中间人可以无感替换");
        assertNotNull(changed.getPreviousFingerprint());
        assertTrue(changed.describe().contains("公钥已变更"));
    }

    @Test
    @DisplayName("用户确认后可以更新固定值")
    void acceptChangeOverwrites(@TempDir Path dir) throws Exception {
        TrustStore store = new TrustStore(dir);
        store.load("alice", "pw");
        store.verify("bob", newPublicKey(), "pw");

        String newKey = newPublicKey();
        assertEquals(TrustStore.TrustStatus.CHANGED, store.verify("bob", newKey, "pw").getStatus());

        store.acceptChange("bob", newKey, "pw");
        assertEquals(TrustStore.TrustStatus.TRUSTED, store.verify("bob", newKey, "pw").getStatus());
    }

    @Test
    @DisplayName("信任库加密落盘：重新加载后固定值仍生效")
    void persistedAcrossReload(@TempDir Path dir) throws Exception {
        String bobKey = newPublicKey();
        TrustStore first = new TrustStore(dir);
        first.load("alice", "pw");
        first.verify("bob", bobKey, "pw");

        TrustStore second = new TrustStore(dir);
        second.load("alice", "pw");
        assertEquals(bobKey, second.getPinned("bob"));
        assertEquals(TrustStore.TrustStatus.TRUSTED, second.verify("bob", bobKey, "pw").getStatus());
    }

    @Test
    @DisplayName("信任库文件不含明文公钥，错误口令无法打开")
    void encryptedAtRest(@TempDir Path dir) throws Exception {
        String bobKey = newPublicKey();
        TrustStore store = new TrustStore(dir);
        store.load("alice", "right-pw");
        store.verify("bob", bobKey, "right-pw");

        Path file = dir.resolve("alice.trust");
        assertTrue(Files.exists(file));
        String content = new String(Files.readAllBytes(file), "UTF-8");
        assertFalse(content.contains(bobKey), "信任库文件不应包含明文公钥");

        TrustStore wrong = new TrustStore(dir);
        assertThrows(IdentityException.class, () -> wrong.load("alice", "wrong-pw"));
    }

    @Test
    @DisplayName("未固定的用户返回 null，不会误判为可信")
    void unknownPeerNotPinned(@TempDir Path dir) throws Exception {
        TrustStore store = new TrustStore(dir);
        store.load("alice", "pw");
        assertNull(store.getPinned("carol"));
        assertFalse(store.isPinned("carol"));
    }

    @Test
    @DisplayName("短指纹按 4 位分组，便于人工比对")
    void shortFingerprintFormat() {
        String formatted = TrustStore.shortFingerprint(
            "7F3A9C21B4E8D6507F3A9C21B4E8D6507F3A9C21B4E8D650");
        assertTrue(formatted.startsWith("7F3A 9C21 B4E8 D650"), "实际：" + formatted);
    }
}
