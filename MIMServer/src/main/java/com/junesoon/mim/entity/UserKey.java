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

    /**
     * 客户端是否支持消息长度填充。旧客户端不上报，按 false 处理。
     */
    private Boolean supportsPadding;

    /**
     * 客户端能否在设备间同步副本里接收加密的撤回凭证。
     * 旧客户端不上报，按 false 处理；不支持的设备只会收到纯文本副本。
     */
    private Boolean supportsRecallToken;

    /**
     * 客户端能否解析首条消息里的发送者证书（Phase 2b）。
     * 旧客户端不上报，按 false 处理，继续收明文发送者格式。
     */
    private Boolean supportsSenderCert;

    private Date crtTime;

    private Date updTime;
}
