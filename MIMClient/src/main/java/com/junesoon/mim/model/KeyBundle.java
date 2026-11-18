package com.junesoon.mim.model;

import lombok.Data;

/**
 * 对方的加密公钥信息。
 */
@Data
public class KeyBundle {

    private String username;

    /**
     * 设备标识。一个用户有多个设备时，每个设备一把独立身份公钥。
     */
    private String deviceId;

    /**
     * Base64(X.509 SubjectPublicKeyInfo)，EC P-256。
     */
    private String publicKey;

    private String algorithm;

    private Integer version;

    /**
     * 公钥 SHA-256 指纹，供用户线下核对。
     */
    private String fingerprint;

    /**
     * 该设备是否支持消息长度填充。旧客户端为 false。
     */
    private Boolean supportsPadding;
}
