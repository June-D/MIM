package com.junesoon.mim.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 启用二次验证时提交的验证码。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TwoFactorCodeRequest {
    private String code;
}
