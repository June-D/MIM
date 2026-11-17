package com.junesoon.mim.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HtmlUtilTest {

    @Test
    @DisplayName("自己刚发出的消息带可点击的撤回")
    void ownRecentMessageShowsRecall() {
        String html = HtmlUtil.of("msg-1", "我", System.currentTimeMillis(), "你好", false, false, true);
        assertTrue(html.contains("recall-btn"));
        assertTrue(html.contains("撤回"));
        assertTrue(html.contains("data-id='msg-1'"));
    }

    @Test
    @DisplayName("对方的消息不出现撤回")
    void incomingMessageHasNoRecall() {
        String html = HtmlUtil.of("msg-2", "bob", System.currentTimeMillis(), "你好", true, false, true);
        assertFalse(html.contains("recall-btn"));
        assertFalse(html.contains("read-state"));
    }

    @Test
    @DisplayName("自己的消息在对方阅读前显示未读，阅读后显示已读")
    void ownMessageShowsReadState() {
        String unread = HtmlUtil.of("msg-3", "我", System.currentTimeMillis(), "在吗",
            false, false, false, false, 15L);
        assertTrue(unread.contains("未读"));
        assertTrue(unread.contains("data-server-id='15'"));
        assertFalse(unread.contains("read-state read"));

        String read = HtmlUtil.of("msg-3", "我", System.currentTimeMillis(), "在吗",
            false, false, false, true, 15L);
        assertTrue(read.contains("已读"));
        assertTrue(read.contains("read-state read"));
    }
}
