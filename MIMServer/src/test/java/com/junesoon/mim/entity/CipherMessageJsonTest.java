package com.junesoon.mim.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 消息模型的 JSON 边界。
 *
 * <p>盲引用和撤回凭证哈希是服务端索引与授权材料，任何一个回传给客户端都会
 * 泄露发送者线索；盲引用字段还会让未升级的旧客户端在反序列化时报未知字段。
 *
 * [INPUT]: 只依赖 Jackson 与 CipherMessage
 * [OUTPUT]: 验证响应不含 fromRef/fromDeviceRef/recallToken/recallTokenHash
 * [POS]: entity 层的序列化契约测试；不启动 Spring，也不连数据库
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
class CipherMessageJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("序列化不回传盲引用与撤回凭证")
    void responseHidesServerOnlyFields() throws Exception {
        CipherMessage message = new CipherMessage();
        message.setClientMsgId("client-msg-0001");
        message.setToUsername("bob");
        message.setSealed(true);
        message.setRecallToken("3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        message.setRecallTokenHash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        message.setConversationTag("tagAAAAAAAAAAAAAAAAAAAAAAAA1");

        String json = mapper.writeValueAsString(message);
        assertFalse(json.contains("fromRef"), "服务端不再保存/下发任何盲引用");
        assertFalse(json.contains("fromUsername"), "消息不含发信人字段");
        assertFalse(json.contains("conversationTag"), "会话标签不能下发给旧客户端");
        assertFalse(json.contains("recallToken"), "撤回凭证和哈希都不能下发给客户端");
        assertTrue(json.contains("\"sealed\":true"));
    }

    @Test
    @DisplayName("请求体里的会话标签可反序列化")
    void requestAcceptsConversationTag() throws Exception {
        CipherMessage message = mapper.readValue(
            "{\"clientMsgId\":\"client-msg-0001\","
                + "\"conversationTag\":\"tagAAAAAAAAAAAAAAAAAAAAAAAA1\"}",
            CipherMessage.class);
        assertEquals("tagAAAAAAAAAAAAAAAAAAAAAAAA1", message.getConversationTag());
    }

    @Test
    @DisplayName("请求体里的 recallToken 仍可反序列化")
    void requestAcceptsRecallToken() throws Exception {
        CipherMessage message = mapper.readValue(
            "{\"clientMsgId\":\"client-msg-0001\",\"recallToken\":\"abc123\"}",
            CipherMessage.class);
        assertEquals("abc123", message.getRecallToken());
        assertNull(message.getRecallTokenHash(), "客户端不能自行提交哈希");
    }
}
