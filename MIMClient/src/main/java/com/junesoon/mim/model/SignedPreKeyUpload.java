package com.junesoon.mim.model;

import lombok.Data;

/**
 * 签名预密钥上传体。
 */
@Data
public class SignedPreKeyUpload {

    private Integer preKeyId;

    private String publicKey;

    private String signature;
}
