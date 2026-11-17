package com.junesoon.mim.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 关闭二次验证：当前口令（Base64）与验证码。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TwoFactorDisableRequest {
    private String password;
    private String code;
}
