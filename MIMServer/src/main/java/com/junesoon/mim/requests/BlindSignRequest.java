package com.junesoon.mim.requests;

import lombok.Data;

/**
 * 致盲签名请求。
 *
 * <p>客户端把 FDH(nonce) 乘上随机 blinding factor 后交给服务端，
 * 服务端看不到 nonce，因此无法把令牌与账号对应。
 *
 * [INPUT]: 只依赖 Lombok
 * [OUTPUT]: blinded（Base64URL 大整数）
 * [POS]: /token/blind 的请求体
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
@Data
public class BlindSignRequest {

    private String blinded;
}
