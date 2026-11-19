package com.junesoon.mim.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 撤回请求体。
 *
 * <p>撤回凭证在发送消息时生成并加密保存在本地，服务端只存哈希。
 * 没有凭证的旧消息可传 null，服务端按发信人绑定关系兼容处理。
 *
 * [INPUT]: 只依赖 Lombok
 * [OUTPUT]: 提供 recallToken 字段
 * [POS]: MessageFeign.recall 的请求体；与服务端 requests/RecallRequest 对应
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecallRequest {

    private String recallToken;

    /** 会话标签。停写盲引用后，服务端靠它定位本人参与的副本。 */
    private List<String> tags;
}
