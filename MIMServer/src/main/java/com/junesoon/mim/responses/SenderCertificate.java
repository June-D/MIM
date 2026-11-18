package com.junesoon.mim.responses;

import lombok.Data;

/**
 * 服务端签发的「发送者证书」。
 *
 * <p>用途：首条消息（Phase 2b）把证书放进端到端加密的正文里，
 * 收信端解码后就能确认「这条消息来自谁」，而不需要服务端把
 * {@code from_user} 写进数据库。
 *
 * <p>签名覆盖 {@code canonical()} 拼出的全部字段，用户名取自 JWT，
 * 客户端无法伪造归属。证书本身不提供匿名：它只回答「谁发的」。
 *
 * [INPUT]: 只依赖 Lombok
 * [OUTPUT]: username / deviceId / identityKey / issuedAt / expiresAt / keyId / signature
 * [POS]: /cert/sender 的响应体；客户端用 /cert/key 的公钥验签
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
@Data
public class SenderCertificate {

    private String username;

    private String deviceId;

    /**
     * 该设备的身份公钥（Base64 X.509）。收信端必须校验它与 X3DH 握手
     * 里的 senderIdentityKey 一致，否则拒绝这条消息。
     */
    private String identityKey;

    /** 签发时间，Unix 毫秒。 */
    private Long issuedAt;

    /** 失效时间，Unix 毫秒。客户端必须检查当前时间早于它。 */
    private Long expiresAt;

    /** 签名密钥标识（公钥 SHA-256 十六进制），用于客户端选择已 pin 的公钥。 */
    private String keyId;

    /** Base64(RSA-SHA256(canonical))。 */
    private String signature;

    /**
     * 参与签名的规范化串。服务端与客户端必须逐字节一致。
     */
    public String canonical() {
        return canonical(username, deviceId, identityKey, issuedAt, expiresAt, keyId);
    }

    public static String canonical(String username, String deviceId, String identityKey,
        Long issuedAt, Long expiresAt, String keyId) {
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
