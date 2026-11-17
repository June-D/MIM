package com.junesoon.mim.model;

import lombok.Data;

/**
 * 对方的加密公钥信息。
 */
@Data
public class KeyBundle {

    private String username;

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
}
