package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TOFU 信任库测试（按设备）。
 *
 * <p>锁住三个关键性质：
 * <ul>
 *   <li>首次接触一次性固定该用户当前所有设备，不会因为多设备互相冲突；</li>
 *   <li>已固定用户新增设备必须显式确认，不能静默固定；</li>
 *   <li>旧版本按用户名保存的固定值能迁移到设备维度。</li>
 * </ul>
 */
class TrustStoreTest {

    private static String newPublicKey() {
        KeyPair pair = DhKeyUtil.generateKeyPair();
        return DhKeyUtil.encodePublicKey(pair.getPublic());
    }

    private static Map<String, String> devices(String... deviceAndKey) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < deviceAndKey.length; i += 2) {
            map.put(deviceAndKey[i], deviceAndKey[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("首次接触一次性固定该用户的全部设备，多设备不会互相冲突")
    void firstContactPinsAllDevices(@TempDir Path dir) throws Exception {
        TrustStore store = new TrustStore(dir);
        store.load("alice", "pw");

        List<TrustStore.VerificationResult> first = store.verifyAll("bob",
            devices("bob-desktop", newPublicKey(), "bob-mobile", newPublicKey()), "pw");
        assertEquals(2, first.size());
        assertTrue(first.get(0).isSendable());
        assertTrue(first.get(1).isSendable());
        assertEquals(TrustStore.TrustStatus.NEW, first.get(0).getStatus());
        assertEquals(2, store.getPinnedDevices("bob").size());
    }

    @Test
    @DisplayName("已固定用户新增设备必须确认，且不会被自动写入信任库")
    void newDeviceRequiresConfirmation(@TempDir Path dir) throws Exception {
        TrustStore store = new TrustStore(dir);
        store.load("alice", "pw");
        String desktopKey = newPublicKey();
        store.verifyAll("bob", devices("bob-desktop", desktopKey), "pw");

        String mobileKey = newPublicKey();
        List<TrustStore.VerificationResult> results = store.verifyAll("bob",
            devices("bob-desktop", desktopKey, "bob-mobile", mobileKey), "pw");
        assertEquals(TrustStore.TrustStatus.TRUSTED, results.get(0).getStatus());
        assertEquals(TrustStore.TrustStatus.NEW_DEVICE, results.get(1).getStatus());
        assertFalse(results.get(1).isSendable());
        assertNull(store.getPinned("bob", "bob-mobile"),
            "未确认的新设备不能被自动固定");
        assertNotNull(store.getPinned("bob", "bob-desktop"));
    }

    @Test
    @DisplayName("已固定设备换钥被判为 CHANGED 并阻断")
    void keyChangeDetected(@TempDir Path dir) throws Exception {
        TrustStore store = new TrustStore(dir);
        store.load("alice", "pw");
        String original = newPublicKey();
        store.verifyAll("bob", devices("bob-desktop", original), "pw");

        String attackerKey = newPublicKey();
        TrustStore.VerificationResult changed =
            store.verify("bob", "bob-desktop", attackerKey, "pw");
        assertEquals(TrustStore.TrustStatus.CHANGED, changed.getStatus());
        assertFalse(changed.isSendable());
        assertNotNull(changed.getPreviousFingerprint());
        assertTrue(changed.describe().contains("公钥已变更"));
    }

    @Test
    @DisplayName("用户确认后可以接受新设备与换钥")
    void acceptDevicesOverwrites(@TempDir Path dir) throws Exception {
        TrustStore store = new TrustStore(dir);
        store.load("alice", "pw");
        store.verifyAll("bob", devices("bob-desktop", newPublicKey()), "pw");

        String newDesktopKey = newPublicKey();
        String mobileKey = newPublicKey();
        Map<String, String> updates =
            devices("bob-desktop", newDesktopKey, "bob-mobile", mobileKey);
        store.acceptDevices("bob", updates, "pw");

        assertEquals(TrustStore.TrustStatus.TRUSTED,
            store.verify("bob", "bob-desktop", newDesktopKey, "pw").getStatus());
        assertEquals(TrustStore.TrustStatus.TRUSTED,
            store.verify("bob", "bob-mobile", mobileKey, "pw").getStatus());
    }

    @Test
    @DisplayName("信任库加密落盘：重新加载后按设备固定值仍生效")
    void persistedAcrossReload(@TempDir Path dir) throws Exception {
        String desktopKey = newPublicKey();
        String mobileKey = newPublicKey();
        TrustStore first = new TrustStore(dir);
        first.load("alice", "pw");
        first.verifyAll("bob", devices("bob-desktop", desktopKey, "bob-mobile", mobileKey), "pw");

        TrustStore second = new TrustStore(dir);
        second.load("alice", "pw");
        assertEquals(desktopKey, second.getPinned("bob", "bob-desktop"));
        assertEquals(mobileKey, second.getPinned("bob", "bob-mobile"));
        assertEquals(TrustStore.TrustStatus.TRUSTED,
            second.verify("bob", "bob-mobile", mobileKey, "pw").getStatus());
    }

    @Test
    @DisplayName("旧版按用户名保存的固定值会迁移到具体设备")
    void legacyRecordMigrates(@TempDir Path dir) throws Exception {
        String bobKey = newPublicKey();
        SecretBox.Sealed sealed = SecretBox.seal("pw", "alice|trust",
            ("bob=" + bobKey + "\n").getBytes(StandardCharsets.UTF_8));
        StringBuilder file = new StringBuilder();
        file.append("salt=").append(java.util.Base64.getEncoder()
            .encodeToString(sealed.getSalt())).append('\n');
        file.append("nonce=").append(java.util.Base64.getEncoder()
            .encodeToString(sealed.getNonce())).append('\n');
        file.append("data=").append(java.util.Base64.getEncoder()
            .encodeToString(sealed.getCiphertext())).append('\n');
        Files.write(dir.resolve("alice.trust"), file.toString().getBytes(StandardCharsets.UTF_8));

        TrustStore store = new TrustStore(dir);
        store.load("alice", "pw");
        TrustStore.VerificationResult migrated =
            store.verify("bob", "bob-desktop", bobKey, "pw");
        assertEquals(TrustStore.TrustStatus.TRUSTED, migrated.getStatus(),
            "与旧固定值一致时应直接迁移，不能重置成 NEW");
        assertEquals(bobKey, store.getPinned("bob", "bob-desktop"));

        // 旧记录匹配另一台设备时必须要求确认，而不是静默信任
        TrustStore.VerificationResult other =
            store.verify("bob", "bob-mobile", newPublicKey(), "pw");
        assertEquals(TrustStore.TrustStatus.NEW_DEVICE, other.getStatus());
        assertFalse(other.isSendable());
    }

    @Test
    @DisplayName("信任库文件不含明文公钥，错误口令无法打开")
    void encryptedAtRest(@TempDir Path dir) throws Exception {
        String bobKey = newPublicKey();
        TrustStore store = new TrustStore(dir);
        store.load("alice", "right-pw");
        store.verifyAll("bob", devices("bob-desktop", bobKey), "right-pw");

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
        assertNull(store.getPinned("carol", "carol-device"));
        assertFalse(store.isPinned("carol", "carol-device"));
        assertFalse(store.hasPins("carol"));
    }

    @Test
    @DisplayName("短指纹按 4 位分组，便于人工比对")
    void shortFingerprintFormat() {
        String formatted = TrustStore.shortFingerprint(
            "7F3A9C21B4E8D6507F3A9C21B4E8D6507F3A9C21B4E8D650");
        assertTrue(formatted.startsWith("7F3A 9C21 B4E8 D650"), "实际：" + formatted);
    }
}
