package com.junesoon.mim.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.model.SenderCertificateView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 首条消息证书信封的格式测试。
 *
 * <p>信封决定「这条消息属于谁」。畸形输入必须返回 null，由调用方按不可解密处理，
 * 不能退化成「猜一个联系人」。
 *
 * [INPUT]: 只依赖 SenderCertEnvelope 与证书模型
 * [OUTPUT]: 验证打包/拆包、正文保留与畸形回退
 * [POS]: 发送端打包、收信端首条消息拆包的格式契约
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
class SenderCertEnvelopeTest {

    private static SenderCertificateView certificate() {
        SenderCertificateView cert = new SenderCertificateView();
        cert.setUsername("alice");
        cert.setDeviceId("alice-desktop");
        cert.setIdentityKey("QUJDREVGRw==");
        cert.setIssuedAt(1_700_000_000_000L);
        cert.setExpiresAt(1_700_086_400_000L);
        cert.setKeyId("0123456789abcdef0123456789abcdef"
            + "0123456789abcdef0123456789abcdef");
        cert.setSignature("c2lnbmF0dXJl");
        return cert;
    }

    @Test
    @DisplayName("打包后能还原证书与正文")
    void roundTrip() {
        String packed = SenderCertEnvelope.pack(certificate(), "晚上八点见");
        assertTrue(SenderCertEnvelope.isEnvelope(packed));

        SenderCertEnvelope.Parsed parsed = SenderCertEnvelope.unpack(packed);
        assertNotNull(parsed);
        assertEquals("alice", parsed.getCertificate().getUsername());
        assertEquals("alice-desktop", parsed.getCertificate().getDeviceId());
        assertEquals("QUJDREVGRw==", parsed.getCertificate().getIdentityKey());
        assertEquals(Long.valueOf(1_700_000_000_000L), parsed.getCertificate().getIssuedAt());
        assertEquals(Long.valueOf(1_700_086_400_000L), parsed.getCertificate().getExpiresAt());
        assertEquals("晚上八点见", parsed.getText());
    }

    @Test
    @DisplayName("会话标签随信封送达，解包后可读")
    void conversationTagRoundTrip() {
        String packed = SenderCertEnvelope.pack(certificate(),
            "tagAAAAAAAAAAAAAAAAAAAAAAAA1", "hi");
        SenderCertEnvelope.Parsed parsed = SenderCertEnvelope.unpack(packed);
        assertNotNull(parsed);
        assertEquals("tagAAAAAAAAAAAAAAAAAAAAAAAA1", parsed.getConversationTag());
        assertEquals("hi", parsed.getText());
    }

    @Test
    @DisplayName("不带标签时保持 7 段格式，解包仍可用")
    void legacyEnvelopeWithoutTag() {
        SenderCertEnvelope.Parsed parsed =
            SenderCertEnvelope.unpack(SenderCertEnvelope.pack(certificate(), "hi"));
        assertNotNull(parsed);
        assertNull(parsed.getConversationTag());
        assertEquals("hi", parsed.getText());
    }

    @Test
    @DisplayName("正文含换行或控制字符时仍只按第一个换行切分")
    void payloadPreserved() {
        String payload = "第一行\n第二行\u0001不是分隔符";
        SenderCertEnvelope.Parsed parsed =
            SenderCertEnvelope.unpack(SenderCertEnvelope.pack(certificate(), payload));
        assertNotNull(parsed);
        assertEquals(payload, parsed.getText());
    }

    @Test
    @DisplayName("畸形信封一律返回 null，绝不猜测发送者")
    void malformedRejected() {
        String packed = SenderCertEnvelope.pack(certificate(), "hi");
        assertNull(SenderCertEnvelope.unpack(packed.substring(0, packed.indexOf('\n'))),
            "缺正文分隔符");
        assertNull(SenderCertEnvelope.unpack("\u0001mimcert1\u0001只有一段\n正文"));
        assertNull(SenderCertEnvelope.unpack("普通消息"));
        assertNull(SenderCertEnvelope.unpack(null));

        // 时间字段非法
        String bad = packed.replace("1700000000000", "not-a-number");
        assertNull(SenderCertEnvelope.unpack(bad));

        // 过期时间必须晚于签发时间
        String inverted = packed.replace("1700086400000", "1699999999999");
        assertNull(SenderCertEnvelope.unpack(inverted));
    }

    @Test
    @DisplayName("没有证书时拒绝打包，避免发出无法归属的消息")
    void packRequiresCertificate() {
        assertNull(SenderCertEnvelope.pack(null, "正文"));
        assertNull(SenderCertEnvelope.pack(certificate(), null));
        assertFalse(SenderCertEnvelope.isEnvelope(""));
    }
}
