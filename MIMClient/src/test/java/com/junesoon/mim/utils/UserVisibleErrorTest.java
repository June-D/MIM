package com.junesoon.mim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserVisibleErrorTest {

    @Test
    @DisplayName("500 和原始 JSON 收成「用户名已存在」")
    void duplicateUsernameHidesTransportDetails() {
        String shown = UserVisibleError.fromTransport(500,
            "{\"status\":40002,\"message\":\"用户名重复!\"}");
        assertEquals("用户名已存在", shown);
        assertFalse(shown.contains("500"));
        assertFalse(shown.contains("{"));
        assertFalse(shown.contains("status"));
    }

    @Test
    @DisplayName("认不出的失败也不把状态码和报文原样展示")
    void unknownFailureStaysPlain() {
        String shown = UserVisibleError.fromTransport(500, "<html>Internal Server Error</html>");
        assertEquals("请求没有成功，请稍后再试", shown);
        assertFalse(shown.contains("500"));
    }

    @Test
    @DisplayName("用户名写完且格式合法时才去检查是否占用")
    void checkOnlyAFinishedLegalName() {
        assertFalse(UserVisibleError.readyToCheckUsername("ab"));
        assertFalse(UserVisibleError.readyToCheckUsername("has space"));
        assertFalse(UserVisibleError.readyToCheckUsername(""));
        assertTrue(UserVisibleError.readyToCheckUsername("alice"));
        assertTrue(UserVisibleError.readyToCheckUsername("bob_01"));
    }
}
