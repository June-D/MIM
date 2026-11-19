package com.junesoon.mim.feign;

import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.model.SenderTokenKeyView;
import feign.RequestLine;

/**
 * 发送者令牌接口（Phase 2c）。
 *
 * <p>签发必须带 JWT（服务端按账号计配额），但服务端看不到 nonce，
 * 因此令牌不会把「提交者是谁」写进任何记录。
 *
 * [INPUT]: 依赖 /token/key 与 /token/blind
 * [OUTPUT]: 提供 key() 与 blind()
 * [POS]: SenderTokenService 的网络层
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
public interface TokenFeign extends FeignAPI {

    @RequestLine("GET /token/key")
    ObjectRestResponse<SenderTokenKeyView> key();

    @RequestLine("POST /token/blind")
    ObjectRestResponse<String> blind(BlindSignRequest request);
}
