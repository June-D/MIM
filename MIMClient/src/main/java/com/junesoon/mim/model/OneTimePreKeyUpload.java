package com.junesoon.mim.model;

import lombok.Data;

/**
 * 一次性预密钥上传体。
 */
@Data
public class OneTimePreKeyUpload {

    private Integer preKeyId;

    private String publicKey;

    public OneTimePreKeyUpload() {
    }

    public OneTimePreKeyUpload(Integer preKeyId, String publicKey) {
        this.preKeyId = preKeyId;
        this.publicKey = publicKey;
    }
}
