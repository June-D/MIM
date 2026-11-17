package com.junesoon.mim.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 设备端到端加密身份公钥。私钥永远只保存在客户端。
 *
 * <p>多设备下主键是 (username, deviceId)，同一用户的不同设备各有独立密钥。
 */
@Data
public class UserKey implements Serializable {

    private static final long serialVersionUID = 1L;

    private String username;

    /**
     * 设备标识。老数据或缺省时使用 {@link #DEFAULT_DEVICE_ID}。
     */
    private String deviceId = DEFAULT_DEVICE_ID;

    /**
     * 缺省设备标识。用于兼容尚未升级到多设备的客户端。
     */
    public static final String DEFAULT_DEVICE_ID = "default";

    /**
     * Base64(X.509 SubjectPublicKeyInfo)，EC P-256。
     */
    private String publicKey;

    private String algorithm;

    private Integer version;

    /**
     * 公钥 SHA-256 指纹，便于用户线下核对。
     */
    private String fingerprint;

    private Date crtTime;

    private Date updTime;
}
