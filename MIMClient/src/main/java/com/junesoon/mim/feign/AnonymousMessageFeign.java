package com.junesoon.mim.feign;

import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.model.CipherMessage;
import feign.Headers;
import feign.Param;
import feign.RequestLine;
import java.util.List;

/**
 * 匿名提交接口（Phase 2c）。
 *
 * <p>只带 {@code X-Sender-Token}，<b>不带 Authorization 与 X-Device-Id</b>——
 * 任何身份头都会让服务端知道提交者，匿名就失效了。调用方必须用
 * {@code Request.anonymousConnector} 创建连接器。
 *
 * [INPUT]: 依赖服务端 /message/anonymous
 * [OUTPUT]: send(token, messages)
 * [POS]: MessageService 匿名发送路径；失败时回退普通 /message
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
public interface AnonymousMessageFeign extends FeignAPI {

    @RequestLine("POST /message/anonymous")
    @Headers("X-Sender-Token: {token}")
    ObjectRestResponse<List<CipherMessage>> send(@Param("token") String token,
        List<CipherMessage> messages);
}
