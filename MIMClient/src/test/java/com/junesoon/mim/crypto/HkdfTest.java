package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HKDF 与 SecretBox 的单元测试。
 */
class HkdfTest {

    @Test
    @DisplayName("HKDF 相同输入产生相同输出")
    void deterministic() {
        byte[] ikm = "shared-secret".getBytes(StandardCharsets.UTF_8);
        byte[] a = Hkdf.derive("salt".getBytes(StandardCharsets.UTF_8), ikm, "info", 32);
        byte[] b = Hkdf.derive("salt".getBytes(StandardCharsets.UTF_8), ikm, "info", 32);
        assertArrayEquals(a, b);
        assertEquals(32, a.length);
    }

    @Test
    @DisplayName("HKDF 不同 info 派生出互不相关的密钥（方向隔离的基础）")
    void differentInfoYieldsDifferentKeys() {
        byte[] ikm = "shared-secret".getBytes(StandardCharsets.UTF_8);
        byte[] aliceToBob = Hkdf.derive("salt".getBytes(StandardCharsets.UTF_8), ikm, "alice->bob", 32);
        byte[] bobToAlice = Hkdf.derive("salt".getBytes(StandardCharsets.UTF_8), ikm, "bob->alice", 32);
        assertFalse(Arrays.equals(aliceToBob, bobToAlice));
    }

    @Test
    @DisplayName("HKDF 不同 salt 派生出不同密钥")
    void differentSaltYieldsDifferentKeys() {
        byte[] ikm = "shared".getBytes(StandardCharsets.UTF_8);
        byte[] a = Hkdf.derive("salt-a".getBytes(StandardCharsets.UTF_8), ikm, "info", 32);
        byte[] b = Hkdf.derive("salt-b".getBytes(StandardCharsets.UTF_8), ikm, "info", 32);
        assertNotEquals(Arrays.hashCode(a), Arrays.hashCode(b));
    }

    @Test
    @DisplayName("HKDF 输出长度非法时抛异常")
    void invalidLengthRejected() {
        byte[] prk = Hkdf.extract(null, "ikm".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> Hkdf.expand(prk, "info", 0));
        assertThrows(IllegalArgumentException.class,
            () -> Hkdf.expand(prk, "info", 255 * 32 + 1));
    }

    @Test
    @DisplayName("HKDF 支持多段扩展输出")
    void longOutput() {
        byte[] ikm = "ikm".getBytes(StandardCharsets.UTF_8);
        byte[] out = Hkdf.derive(null, ikm, "info", 100);
        assertEquals(100, out.length);
    }

    @Test
    @DisplayName("wipe 会清零密钥字节")
    void wipeClearsBytes() {
        byte[] secret = "secret".getBytes(StandardCharsets.UTF_8);
        Hkdf.wipe(secret);
        for (byte b : secret) {
            assertEquals(0, b);
        }
    }

    @Test
    @DisplayName("SHA-256 十六进制摘要稳定且长度正确")
    void sha256Hex() {
        String hex = Hkdf.sha256Hex("abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(64, hex.length());
        assertEquals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", hex);
    }

    // ==================== SecretBox ====================

    @Test
    @DisplayName("SecretBox 加解密往返一致")
    void secretBoxRoundTrip() throws Exception {
        byte[] plain = "机密数据".getBytes(StandardCharsets.UTF_8);
        SecretBox.Sealed sealed = SecretBox.seal("pw", "aad", plain);
        byte[] opened = SecretBox.open("pw", "aad", sealed.getSalt(), sealed.getNonce(),
            sealed.getCiphertext());
        assertArrayEquals(plain, opened);
    }

    @Test
    @DisplayName("SecretBox 错误口令抛异常")
    void secretBoxWrongPassword() {
        SecretBox.Sealed sealed = SecretBox.seal("right", "aad", "x".getBytes(StandardCharsets.UTF_8));
        assertThrows(IdentityException.class, () -> SecretBox.open("wrong", "aad",
            sealed.getSalt(), sealed.getNonce(), sealed.getCiphertext()));
    }

    @Test
    @DisplayName("SecretBox AAD 不匹配时解密失败（防止密文跨对象搬运）")
    void secretBoxAadMismatch() {
        SecretBox.Sealed sealed = SecretBox.seal("pw", "alice|bob",
            "x".getBytes(StandardCharsets.UTF_8));
        assertThrows(IdentityException.class, () -> SecretBox.open("pw", "alice|carol",
            sealed.getSalt(), sealed.getNonce(), sealed.getCiphertext()));
    }

    @Test
    @DisplayName("SecretBox 每次加密使用不同 salt 与 nonce")
    void secretBoxRandomized() {
        byte[] plain = "same".getBytes(StandardCharsets.UTF_8);
        SecretBox.Sealed a = SecretBox.seal("pw", "aad", plain);
        SecretBox.Sealed b = SecretBox.seal("pw", "aad", plain);
        assertFalse(Arrays.equals(a.getSalt(), b.getSalt()));
        assertFalse(Arrays.equals(a.getNonce(), b.getNonce()));
        assertFalse(Arrays.equals(a.getCiphertext(), b.getCiphertext()));
    }

    @Test
    @DisplayName("SecretBox 密文被篡改时解密失败")
    void secretBoxTampered() {
        SecretBox.Sealed sealed = SecretBox.seal("pw", "aad", "x".getBytes(StandardCharsets.UTF_8));
        byte[] tampered = sealed.getCiphertext().clone();
        tampered[0] ^= 0x01;
        assertThrows(IdentityException.class, () -> SecretBox.open("pw", "aad",
            sealed.getSalt(), sealed.getNonce(), tampered));
    }
}
