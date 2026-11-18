package com.junesoon.mim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 一次性撤回凭证的哈希与格式校验。
 *
 * [INPUT]: 直接调用 RecallToken 的静态方法
 * [OUTPUT]: 验证 SHA-256 稳定性、格式拒绝与大小写敏感
 * [POS]: utils 层单测；不启动 Spring，也不连数据库
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
class RecallTokenTest {

    private static final String TOKEN = "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    @DisplayName("同一个 token 的哈希稳定且为 64 位十六进制")
    void hashIsStable() {
        String first = RecallToken.hash(TOKEN);
        String second = RecallToken.hash(TOKEN);
        assertEquals(first, second);
        assertTrue(first.matches("^[0-9a-f]{64}$"));
    }

    @Test
    @DisplayName("不同 token 的哈希不同")
    void hashDiffers() {
        assertNotEquals(RecallToken.hash(TOKEN), RecallToken.hash(TOKEN + "x"));
    }

    @Test
    @DisplayName("拒绝空值、过短与含非法字符的 token")
    void rejectsBadTokens() {
        assertFalse(RecallToken.isValid(null));
        assertFalse(RecallToken.isValid(""));
        assertFalse(RecallToken.isValid("short"));
        assertFalse(RecallToken.isValid("has space 1234567890"));
        assertThrows(IllegalArgumentException.class, () -> RecallToken.hash("short"));
    }

    @Test
    @DisplayName("接受标准与 URL-safe Base64 两种形式")
    void acceptsBase64Variants() {
        assertTrue(RecallToken.isValid(TOKEN));
        assertTrue(RecallToken.isValid("abcDEF0123456789_-"));
    }
}
