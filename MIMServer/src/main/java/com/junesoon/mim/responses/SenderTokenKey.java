package com.junesoon.mim.responses;

import lombok.Data;

/**
 * 盲签名令牌的公钥。
 *
 * [INPUT]: 只依赖 Lombok
 * [OUTPUT]: keyId / publicKey / algorithm
 * [POS]: /token/key 的响应；客户端用它致盲与自检
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
@Data
public class SenderTokenKey {

    /** 公钥 SHA-256 十六进制。 */
    private String keyId;

    /** Base64(X.509 SubjectPublicKeyInfo)。 */
    private String publicKey;

    /** 固定为 RSA。 */
    private String algorithm;
}
