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
}
