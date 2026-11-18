package com.junesoon.mim.model;

import lombok.Data;

/**
 * 发送者证书验签公钥（客户端视角）。
 *
 * [INPUT]: 只依赖 Lombok
 * [OUTPUT]: keyId/publicKey/algorithm
 * [POS]: CertFeign.key() 的响应；由 ServerCertPin 固定
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
@Data
public class SenderCertKeyView {

    private String keyId;

    private String publicKey;

    private String algorithm;
}
