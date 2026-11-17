package com.junesoon.mim.feign;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公钥上传请求体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeyUploadRequest {

    private String publicKey;

    private String algorithm;

    /**
     * 当前登录口令的 Base64。新增或更换身份公钥时必须提供。
     */
    private String password;
}
