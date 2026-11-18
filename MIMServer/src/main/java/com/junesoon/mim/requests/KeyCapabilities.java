package com.junesoon.mim.requests;

/**
 * 设备能力声明。
 *
 * <p>三个能力位语义相同：只有对方设备声明支持，服务端/发送端才会使用
 * 对应格式。未声明（null）一律按 false 处理，旧客户端行为不变。
 *
 * <p>用值对象而不是三个并列布尔参数：调用点不会再把「填充」和「证书」
 * 传反，新增能力时也不用改方法签名。
 *
 * [INPUT]: 只依赖 JDK
 * [OUTPUT]: 提供 padding / recallToken / senderCert 三项不可变能力位
 * [POS]: KeyService.upload 的输入；由 KeyUploadRequest 组装
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
public final class KeyCapabilities {

    private final Boolean padding;

    private final Boolean recallToken;

    private final Boolean senderCert;

    private KeyCapabilities(Boolean padding, Boolean recallToken, Boolean senderCert) {
        this.padding = padding;
        this.recallToken = recallToken;
        this.senderCert = senderCert;
    }

    public static KeyCapabilities of(Boolean padding, Boolean recallToken, Boolean senderCert) {
        return new KeyCapabilities(padding, recallToken, senderCert);
    }

    public Boolean padding() {
        return padding;
    }

    public Boolean recallToken() {
        return recallToken;
    }

    public Boolean senderCert() {
        return senderCert;
    }
}
