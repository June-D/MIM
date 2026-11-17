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

    /**
     * 预留该 OPK 的请求者（形如 {@code username@deviceId}）。
     * 只有同一个请求者重试时才会再次拿到它；消息真正使用后再删除。
     */
    private String claimedBy;

    /**
     * 预留时间（毫秒）。超过 TTL 未使用会被释放，供其他请求者领取。
     */
    private Long claimedAt;
}
