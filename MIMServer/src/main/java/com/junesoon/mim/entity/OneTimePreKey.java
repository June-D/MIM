package com.junesoon.mim.entity;

import java.io.Serializable;
import lombok.Data;

/**
 * 一次性预密钥（One-Time PreKey），按设备维度。
 *
 * <p>每个只能用一次，用完即删，为会话初始消息提供前向安全性。
 */
@Data
public class OneTimePreKey implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String username;

    private String deviceId = UserKey.DEFAULT_DEVICE_ID;

    private Integer preKeyId;

    /**
     * Base64(X.509) 的 EC P-256 公钥。
     */
    private String publicKey;

    private Long crtTime;
}
