package com.junesoon.mim.responses;

import lombok.Data;

/**
 * 发送者证书的<b>验签公钥</b>，供客户端首次拉取并 pin 住。
 *
 * <p>客户端不能每次都无条件信任这里返回的公钥，否则被攻破的服务端
 * 可以换成自己的密钥去伪造证书。正确做法是 TOFU：第一次拿到后固定，
 * 之后 keyId 变化必须由用户显式确认。
 *
 * [INPUT]: 只依赖 Lombok
 * [OUTPUT]: keyId / publicKey / algorithm
 * [POS]: /cert/key 的响应体；与 SenderCertificate.keyId 一一对应
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
@Data
public class SenderCertKey {

    /** 公钥 SHA-256 十六进制。 */
    private String keyId;

    /** Base64(X.509 SubjectPublicKeyInfo)。 */
    private String publicKey;

    private String algorithm;
}
