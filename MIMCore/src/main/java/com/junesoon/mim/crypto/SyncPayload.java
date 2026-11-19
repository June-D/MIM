package com.junesoon.mim.crypto;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;

/**
 * 设备间历史同步的来源签名。
 *
 * <p>同步副本由「本账号的另一台设备」重新加密，服务端只知道密文。但服务端会
 * 附带原消息的 from/to/device 字段，如果客户端直接采信，被攻破的服务端可以把
 * 任意内容挂到任意联系人名下。转发设备用自己的身份私钥对
 * 「原消息归属 + 内容」签名后，接收方用该设备的身份公钥验签，服务端就无法
 * 在不破坏签名的前提下改写归属。
 *
 * <p>字段采用长度前缀编码，避免用分隔符拼接时产生歧义。
 */
public final class SyncPayload {

    private static final String DOMAIN = "MIM/sync/v2|";

    private SyncPayload() {
    }

    /**
     * 构造参与签名的规范字符串。
     */
    public static String canonical(String originalFrom, String originalTo,
        String originalDevice, String clientMsgId, long clientTime, String targetDeviceId,
        String text) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(DOMAIN);
        append(sb, originalFrom);
        append(sb, originalTo);
        append(sb, originalDevice);
        append(sb, clientMsgId);
        append(sb, Long.toString(clientTime));
        append(sb, targetDeviceId);
        append(sb, text);
        return sb.toString();
    }

    public static String sign(PrivateKey privateKey, String canonical) {
        return DhKeyUtil.sign(privateKey, canonical.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean verify(String identityPublicKeyBase64, String canonical,
        String signatureBase64) {
        if (identityPublicKeyBase64 == null || canonical == null || signatureBase64 == null) {
            return false;
        }
        try {
            return DhKeyUtil.verify(DhKeyUtil.decodePublicKey(identityPublicKeyBase64),
                canonical.getBytes(StandardCharsets.UTF_8), signatureBase64);
        } catch (Exception e) {
            return false;
        }
    }

    private static void append(StringBuilder sb, String value) {
        String safe = value == null ? "" : value;
        sb.append(safe.length()).append(':').append(safe).append('|');
    }
}
