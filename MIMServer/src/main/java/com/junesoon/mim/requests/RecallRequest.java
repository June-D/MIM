package com.junesoon.mim.requests;

import lombok.Data;

/**
 * 撤回请求。
 *
 * <p>新客户端发送消息时生成一次性 recallToken，撤回时必须原样带回；
 * 服务端只保存 SHA-256，比对通过才执行撤回。旧客户端不带此字段，
 * 服务端对没有凭证哈希的历史消息仍按发信人引用兼容处理。
 *
 * [INPUT]: 只依赖 Lombok，不引用 service 层
 * [OUTPUT]: 提供 recallToken 字段
 * [POS]: MessageController.recall 的请求体；与 CipherMessage.recallToken 同义
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
@Data
public class RecallRequest {

    /**
     * 发送时生成的一次性撤回凭证（Base64，16~128 字符）。
     */
    private String recallToken;
}
