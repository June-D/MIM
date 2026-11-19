package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 匿名发送开关的默认值与显式开启。
 *
 * <p>匿名提交依赖服务端同时开启令牌与匿名入口；客户端必须默认关闭，
 * 否则每发一条消息都会多一次令牌申请与被拒的往返。
 *
 * [INPUT]: 只依赖系统属性
 * [OUTPUT]: 验证默认关闭、属性开启、属性关闭
 * [POS]: MessageService 匿名路径的入口条件
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
class AnonymousSendGateTest {

    private static final String PROPERTY = "mim.anonymousSend";

    @AfterEach
    void tearDown() {
        System.clearProperty(PROPERTY);
    }

    @Test
    @DisplayName("默认关闭：不设置属性时不走匿名提交")
    void defaultOff() {
        System.clearProperty(PROPERTY);
        assertFalse(MessageService.anonymousEnabled());
    }

    @Test
    @DisplayName("显式设置 -Dmim.anonymousSend=true 才开启")
    void enabledByProperty() {
        System.setProperty(PROPERTY, "true");
        assertTrue(MessageService.anonymousEnabled());

        System.setProperty(PROPERTY, "false");
        assertFalse(MessageService.anonymousEnabled());
    }
}
