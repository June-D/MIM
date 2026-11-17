package com.junesoon.mim.entity;

import java.io.Serializable;
import lombok.Data;

/**
 * 签名预密钥（Signed PreKey），按设备维度。
 */
@Data
public class SignedPreKey implements Serializable {

    private static final long serialVersionUID = 1L;

    private String username;

    private String deviceId = UserKey.DEFAULT_DEVICE_ID;

    /**
     * 预密钥编号。客户端轮换时自增。
     */
    private Integer preKeyId;

    /**
     * Base64(X.509) 的 EC P-256 公钥。
     */
    private String publicKey;

    /**
     * Base64(ECDSA-SHA256 签名)，被签内容是 {@code preKeyId || publicKey}。
     */
    private String signature;

    private Long crtTime;

    private Long updTime;
}
