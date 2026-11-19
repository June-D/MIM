package com.junesoon.mim.model;

import lombok.Data;

/**
 * 盲签名令牌公钥（客户端视角）。
 *
 * [INPUT]: 只依赖 Lombok
 * [OUTPUT]: keyId / publicKey / algorithm
 * [POS]: TokenFeign.key() 的响应；BlindRsaToken 用它致盲与自检
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
@Data
public class SenderTokenKeyView {

    private String keyId;

    private String publicKey;

    private String algorithm;
}
