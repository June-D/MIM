package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 设备间同步来源签名测试。
 */
class SyncPayloadTest {

    private static final KeyPair KEY = DhKeyUtil.generateKeyPair();

    private static String canonical(String text) {
        return SyncPayload.canonical("bob", "alice", "bob-1", "msg-1",
            1_700_000_000_000L, "device-a", text);
    }

    @Test
    @DisplayName("签名可被对应身份公钥验证，篡改内容或归属会失败")
    void signatureBindsContentAndAttribution() {
        String canonical = canonical("hello");
        String signature = SyncPayload.sign(KEY.getPrivate(), canonical);

        assertTrue(SyncPayload.verify(
            DhKeyUtil.encodePublicKey(KEY.getPublic()), canonical, signature));

        assertFalse(SyncPayload.verify(DhKeyUtil.encodePublicKey(KEY.getPublic()),
            canonical("hello!"), signature), "内容被篡改必须验签失败");
        assertFalse(SyncPayload.verify(DhKeyUtil.encodePublicKey(KEY.getPublic()),
            SyncPayload.canonical("carol", "alice", "bob-1", "msg-1",
                1_700_000_000_000L, "device-a", "hello"), signature),
            "发送者被篡改必须验签失败");
        assertFalse(SyncPayload.verify(
            DhKeyUtil.encodePublicKey(DhKeyUtil.generateKeyPair().getPublic()),
            canonical, signature), "换一把公钥必须验签失败");
    }

    @Test
    @DisplayName("字段采用长度前缀，分隔符不会产生歧义")
    void lengthPrefixAvoidsAmbiguity() {
        String left = SyncPayload.canonical("ab", "c", "dev", "id", 1L, "t", "x");
        String right = SyncPayload.canonical("a", "bc", "dev", "id", 1L, "t", "x");
        assertNotEquals(left, right);
    }

    @Test
    @DisplayName("规范字符串对相同输入稳定")
    void canonicalIsStable() {
        assertEquals(canonical("hello"), SyncPayload.canonical("bob", "alice", "bob-1",
            "msg-1", 1_700_000_000_000L, "device-a", "hello"));
    }
}
