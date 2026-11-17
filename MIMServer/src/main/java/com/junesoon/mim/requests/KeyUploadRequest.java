package com.junesoon.mim.requests;

import lombok.Data;

@Data
public class KeyUploadRequest {

    /**
     * Base64(X.509 SubjectPublicKeyInfo)。
     */
    private String publicKey;

    private String algorithm;

    /**
     * 当前登录口令的 Base64。新增或更换身份公钥时必须提供，
     * 防止仅凭被盗 JWT 就能替换本设备的身份公钥。
     */
    private String password;
}
