package com.junesoon.mim.requests;

import lombok.Data;

/**
 * 关闭二次验证：需要当前口令与 6 位 TOTP 验证码。
 */
@Data
public class TwoFactorDisableRequest {
    private String password;
    private String code;
}
