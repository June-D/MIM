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
}
