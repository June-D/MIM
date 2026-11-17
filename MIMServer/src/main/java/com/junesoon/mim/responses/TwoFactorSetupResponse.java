package com.junesoon.mim.responses;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 二次验证初始化结果：共享密钥与可直接导入验证器的 otpauth URI。
 */
@Data
@AllArgsConstructor
public class TwoFactorSetupResponse {
    private String secret;
    private String otpauthUri;
}
