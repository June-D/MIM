package com.junesoon.mim.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.junesoon.mim.exception.UserInvalidException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 输入校验测试。这些是防注入与防越界的第一道关口。
 */
class ValidationUtilTest {

    /**
     * JDK 8 没有 String.repeat，这里用字符数组构造重复串。
     */
    private static String repeat(char ch, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, ch);
        return new String(chars);
    }

    @Test
    @DisplayName("合法用户名通过校验")
    void validUsernames() {
        assertDoesNotThrow(() -> ValidationUtil.validateUsername("alice"));
        assertDoesNotThrow(() -> ValidationUtil.validateUsername("user_01"));
        assertDoesNotThrow(() -> ValidationUtil.validateUsername("张三"));
        assertDoesNotThrow(() -> ValidationUtil.validateUsername("a.b-c"));
    }

    @Test
    @DisplayName("非法用户名被拒绝")
    void invalidUsernames() {
        assertThrows(UserInvalidException.class, () -> ValidationUtil.validateUsername(null));
        assertThrows(UserInvalidException.class, () -> ValidationUtil.validateUsername("a"));
        assertThrows(UserInvalidException.class, () -> ValidationUtil.validateUsername(repeat('a', 33)));
        assertThrows(UserInvalidException.class, () -> ValidationUtil.validateUsername("a b"));
        assertThrows(UserInvalidException.class, () -> ValidationUtil.validateUsername("a/b"));
        assertThrows(UserInvalidException.class, () -> ValidationUtil.validateUsername("a;drop"));
    }

    @Test
    @DisplayName("密码长度限制：过短与超过 BCrypt 72 字节上限都拒绝")
    void passwordBounds() {
        assertThrows(UserInvalidException.class, () -> ValidationUtil.validatePassword(null));
        assertThrows(UserInvalidException.class, () -> ValidationUtil.validatePassword("12345"));
        assertDoesNotThrow(() -> ValidationUtil.validatePassword("123456"));
        // BCrypt 只使用前 72 字节，超长必须显式拒绝而不是静默截断
        assertThrows(UserInvalidException.class, () -> ValidationUtil.validatePassword(repeat('x', 73)));
        // 中文占 3 字节，24 个字刚好 72 字节
        assertDoesNotThrow(() -> ValidationUtil.validatePassword(repeat('中', 24)));
        assertThrows(UserInvalidException.class, () -> ValidationUtil.validatePassword(repeat('中', 25)));
    }

    @Test
    @DisplayName("requireText 校验空值与超长")
    void requireText() {
        assertDoesNotThrow(() -> ValidationUtil.requireText("abc", "字段", 10));
        assertThrows(UserInvalidException.class, () -> ValidationUtil.requireText(null, "字段", 10));
        assertThrows(UserInvalidException.class, () -> ValidationUtil.requireText("  ", "字段", 10));
        assertThrows(UserInvalidException.class, () -> ValidationUtil.requireText("abcdef", "字段", 5));
    }

    @Test
    @DisplayName("用户名长度边界：2 与 32 恰好合法")
    void usernameBoundaries() {
        assertDoesNotThrow(() -> ValidationUtil.validateUsername("ab"));
        assertDoesNotThrow(() -> ValidationUtil.validateUsername(repeat('a', 32)));
        assertThrows(UserInvalidException.class, () -> ValidationUtil.validateUsername(repeat('a', 31) + "!"));
    }

    @Test
    @DisplayName("大小写与数字混合的用户名合法")
    void mixedUsername() {
        assertDoesNotThrow(() -> ValidationUtil.validateUsername("User123"));
    }
}
