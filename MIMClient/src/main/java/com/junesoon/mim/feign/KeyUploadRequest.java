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

    /**
     * 本设备是否支持消息长度填充。
     */
    private Boolean supportsPadding;

    /**
     * 本设备能否在「自己设备间同步」的副本里接收加密的撤回凭证。
     * 声明后，另一台设备转发历史时会顺带把撤回凭证加密送过来。
     */
    private Boolean supportsRecallToken;

    /**
     * 本设备能否解析首条消息里的发送者证书（Phase 2b）。
     */
    private Boolean supportsSenderCert;
}
