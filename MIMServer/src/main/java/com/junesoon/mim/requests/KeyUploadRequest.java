package com.junesoon.mim.requests;

import lombok.Data;

@Data
public class KeyUploadRequest {

    /**
     * Base64(X.509 SubjectPublicKeyInfo)。
     */
    private String publicKey;

    private String algorithm;
}
