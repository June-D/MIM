package com.junesoon.mim.responses;

import lombok.Data;

/**
 * 返回给客户端的公钥信息。
 */
@Data
public class KeyBundle {

    private String username;

    /**
     * 多设备下用于区分同一用户的不同设备。
     */
    private String deviceId;

    private String publicKey;

    private String algorithm;

    private Integer version;

    private String fingerprint;

    /**
     * 该设备是否支持消息长度填充。旧客户端为 false。
     */
    private Boolean supportsPadding;

    /**
     * 该设备能否在「自己设备间同步」的副本里接收加密的撤回凭证。
     * 只有声明支持的目标设备才会收到带凭证的副本，旧客户端保持纯文本。
     */
    private Boolean supportsRecallToken;
}
