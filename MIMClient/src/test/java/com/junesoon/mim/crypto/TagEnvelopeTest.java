package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 轻量标签信封测试。
 *
 * <p>它保证「双方各自生成过标签」时，每条已确认会话的消息都能把标签带给对端，
 * 否则带未知标签的消息会在历史里消失。
 *
 * [INPUT]: 只依赖 TagEnvelope
 * [OUTPUT]: 验证往返、无标签直通与畸形回退
 * [POS]: 标签收敛的格式契约
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
class TagEnvelopeTest {

    @Test
    @DisplayName("标签与正文往返")
    void roundTrip() {
        String packed = TagEnvelope.pack("DpIZQRMaJ1HQzIA2VCPccLPK6PI6i1-n", "你还爱");
        TagEnvelope.Parsed parsed = TagEnvelope.unpack(packed);
        assertEquals("DpIZQRMaJ1HQzIA2VCPccLPK6PI6i1-n", parsed.getTag());
        assertEquals("你还爱", parsed.getText());
    }

    @Test
    @DisplayName("正文含换行时只按第一个换行切分")
    void multilineBody() {
        TagEnvelope.Parsed parsed = TagEnvelope.unpack(
            TagEnvelope.pack("tagAAAAAAAAAAAAAAAAAAAAAAAA1", "第一行\n第二行"));
        assertEquals("第一行\n第二行", parsed.getText());
    }

    @Test
    @DisplayName("无标签或畸形时原样返回")
    void passthrough() {
        assertEquals("正文", TagEnvelope.pack(null, "正文"));
        assertNull(TagEnvelope.unpack("正文"));
        assertNull(TagEnvelope.unpack("\u0001mimtag1\u0001没有分隔符"));
        assertNull(TagEnvelope.unpack("\u0001mimtag1\u0001\n正文"));
    }
}
