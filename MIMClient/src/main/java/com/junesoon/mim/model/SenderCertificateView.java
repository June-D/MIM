package com.junesoon.mim.model;

import lombok.Data;

/**
 * 发送者证书（客户端视角）。
 *
 * <p>字段与规范化串必须与服务端 `responses/SenderCertificate` 逐字节一致，
 * 否则验签必然失败。
 *
 * [INPUT]: 只依赖 Lombok
 * [OUTPUT]: username/deviceId/identityKey/issuedAt/expiresAt/keyId/signature 与 canonical()
 * [POS]: SenderCertEnvelope 的内容；SndCertVerifier 用它做验签
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
@Data
public class SenderCertificateView {

    private String username;

    private String deviceId;

    private String identityKey;

    private Long issuedAt;

    private Long expiresAt;

    private String keyId;

    private String signature;

    public String canonical() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("MIM-SENDER-CERT-1\n");
        sb.append(username).append('\n');
        sb.append(deviceId).append('\n');
        sb.append(identityKey).append('\n');
        sb.append(issuedAt == null ? "" : issuedAt.toString()).append('\n');
        sb.append(expiresAt == null ? "" : expiresAt.toString()).append('\n');
        sb.append(keyId).append('\n');
        return sb.toString();
    }
}
