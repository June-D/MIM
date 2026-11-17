package com.junesoon.mim.requests;

import lombok.Data;

/**
 * 启用二次验证时提交的 6 位 TOTP 验证码。
 */
@Data
public class TwoFactorCodeRequest {
    private String code;
}
