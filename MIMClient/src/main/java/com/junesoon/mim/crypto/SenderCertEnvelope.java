package com.junesoon.mim.crypto;

import com.junesoon.mim.model.SenderCertificateView;

/**
 * 首条消息正文里的「发送者证书信封」。
 *
 * <p>首条消息还没有会话可供客户端判断对端，服务端又不能把 {@code from_user}
 * 写进数据库。于是发送端把服务端签发的证书放进<b>已端到端加密</b>的正文里：
 * 收信端解出正文后先验签，再据此确定这条消息属于谁。
 *
 * <p>格式沿用 {@link SyncEnvelope} 的思路：`U+0001` 开头的魔数、
 * `U+0001` 分隔 7 个证书字段、第一个换行之后是真正的正文。
 * 任一字段缺失或格式不合法都返回 null，调用方必须按「无法确定发送者」处理，
 * 绝不能猜。
 *
 * [INPUT]: 依赖 SenderCertificateView
 * [OUTPUT]: pack / unpack / isEnvelope；unpack 返回证书 + 正文
 * [POS]: MessageService 发送端打包、收信端首条消息解密后拆包
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
public final class SenderCertEnvelope {

    private static final String MAGIC = "\u0001mimcert1\u0001";

    private static final char FIELD_SEP = '\u0001';

    private static final char BODY_SEP = '\n';

    /** username/deviceId 的长度上限与服务端一致。 */
    private static final int MAX_ID_LENGTH = 64;

    private SenderCertEnvelope() {
    }

    public static String pack(SenderCertificateView certificate, String payload) {
        if (certificate == null || payload == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(payload.length() + 512);
        sb.append(MAGIC);
        sb.append(certificate.getUsername()).append(FIELD_SEP);
        sb.append(certificate.getDeviceId()).append(FIELD_SEP);
        sb.append(certificate.getIdentityKey()).append(FIELD_SEP);
        sb.append(certificate.getIssuedAt()).append(FIELD_SEP);
        sb.append(certificate.getExpiresAt()).append(FIELD_SEP);
        sb.append(certificate.getKeyId()).append(FIELD_SEP);
        sb.append(certificate.getSignature());
        sb.append(BODY_SEP);
        sb.append(payload);
        return sb.toString();
    }

    public static boolean isEnvelope(String text) {
        return text != null && text.startsWith(MAGIC);
    }

    /**
     * 拆包；不是信封或结构不合法时返回 null，调用方应把它当作不可信消息。
     */
    public static Parsed unpack(String text) {
        if (!isEnvelope(text)) {
            return null;
        }
        int bodyStart = text.indexOf(BODY_SEP, MAGIC.length());
        if (bodyStart < 0) {
            return null;
        }
        String[] fields = text.substring(MAGIC.length(), bodyStart).split(
            String.valueOf(FIELD_SEP), -1);
        if (fields.length != 7) {
            return null;
        }
        if (fields[0].isEmpty() || fields[0].length() > MAX_ID_LENGTH
            || fields[1].isEmpty() || fields[1].length() > MAX_ID_LENGTH
            || fields[2].isEmpty() || fields[6].isEmpty()) {
            return null;
        }
        Long issuedAt = parseLong(fields[3]);
        Long expiresAt = parseLong(fields[4]);
        if (issuedAt == null || expiresAt == null || expiresAt <= issuedAt) {
            return null;
        }

        SenderCertificateView certificate = new SenderCertificateView();
        certificate.setUsername(fields[0]);
        certificate.setDeviceId(fields[1]);
        certificate.setIdentityKey(fields[2]);
        certificate.setIssuedAt(issuedAt);
        certificate.setExpiresAt(expiresAt);
        certificate.setKeyId(fields[5]);
        certificate.setSignature(fields[6]);
        return new Parsed(certificate, text.substring(bodyStart + 1));
    }

    private static Long parseLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 拆包结果：证书 + 正文。
     */
    public static final class Parsed {
        private final SenderCertificateView certificate;
        private final String text;

        public Parsed(SenderCertificateView certificate, String text) {
            this.certificate = certificate;
            this.text = text;
        }

        public SenderCertificateView getCertificate() {
            return certificate;
        }

        public String getText() {
            return text;
        }
    }
}
