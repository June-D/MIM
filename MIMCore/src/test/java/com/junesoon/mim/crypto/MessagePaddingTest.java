package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessagePaddingTest {

    @Test
    @DisplayName("填充到固定桶后仍能还原原文")
    void roundTrip() {
        String text = "hello 填充";
        String padded = MessagePadding.pad(text);
        assertEquals(0, padded.length() % 256);
        assertEquals(text, MessagePadding.strip(padded));
        assertEquals("", MessagePadding.strip(MessagePadding.pad("")));
    }

    @Test
    @DisplayName("原文本身像填充前缀也不会被误解")
    void magicContent() {
        String text = "MIMPAD1:1:x";
        String padded = MessagePadding.pad(text);
        assertEquals(text, MessagePadding.strip(padded));
    }

    @Test
    @DisplayName("未填充或损坏的数据原样返回")
    void passthrough() {
        assertEquals("plain", MessagePadding.strip("plain"));
        assertEquals("MIMPAD1:bad", MessagePadding.strip("MIMPAD1:bad"));
        assertNull(MessagePadding.pad(null));
    }
}
