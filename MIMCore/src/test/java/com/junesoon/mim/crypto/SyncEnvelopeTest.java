package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 同步副本撤回凭证信封的格式测试。
 *
 * <p>信封决定「另一台设备能否撤回同一条消息」，同时必须保证普通正文不被误判。
 *
 * [INPUT]: 只依赖 SyncEnvelope
 * [OUTPUT]: 验证打包/拆包、无凭证直通与畸形输入的安全回退
 * [POS]: HistorySync 打包与 MessageService 拆包两侧共用的格式契约
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
class SyncEnvelopeTest {

    private static final String TOKEN = "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    @DisplayName("打包后能还原凭证与正文")
    void roundTrip() {
        String payload = SyncEnvelope.pack(TOKEN, "晚上八点见");
        assertTrue(SyncEnvelope.isEnvelope(payload));
        SyncEnvelope.Parsed parsed = SyncEnvelope.unpack(payload);
        assertNotNull(parsed);
        assertEquals(TOKEN, parsed.getRecallToken());
        assertEquals("晚上八点见", parsed.getText());
    }

    @Test
    @DisplayName("正文里的换行与信封魔数都不影响拆包")
    void multilineText() {
        String text = "第一行\n" + SyncEnvelope.pack(TOKEN, "嵌套") + "\n第三行";
        SyncEnvelope.Parsed parsed = SyncEnvelope.unpack(SyncEnvelope.pack(TOKEN, text));
        assertNotNull(parsed);
        assertEquals(text, parsed.getText(), "只按第一个分隔符切分，正文原样保留");
    }

    @Test
    @DisplayName("没有凭证时不加信封，正文原样返回")
    void noTokenPassthrough() {
        assertEquals("普通消息", SyncEnvelope.pack(null, "普通消息"));
        assertEquals("普通消息", SyncEnvelope.pack("", "普通消息"));
        assertFalse(SyncEnvelope.isEnvelope("普通消息"));
        assertNull(SyncEnvelope.unpack("普通消息"));
    }

    @Test
    @DisplayName("畸形信封回退为普通正文，绝不抛异常")
    void malformedPayloadFallsBack() {
        assertNull(SyncEnvelope.unpack("\u0001mimsync1\u0001没有分隔符"));
        assertNull(SyncEnvelope.unpack("\u0001mimsync1\u0001短\ntext"), "凭证长度不足");
        assertNull(SyncEnvelope.unpack("\u0001mimsync1\u0001含空格 的凭证\n文本"));
        assertNull(SyncEnvelope.unpack(null));
    }

    @Test
    @DisplayName("正文以控制字符开头也不会被当成信封")
    void textStartingWithControlChar() {
        String text = "\u0001不是信封";
        assertEquals(text, SyncEnvelope.pack(null, text));
        assertNull(SyncEnvelope.unpack(text));
    }
}
