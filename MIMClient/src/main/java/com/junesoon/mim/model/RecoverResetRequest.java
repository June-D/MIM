package com.junesoon.mim.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 使用邮箱验证码重置密码。newPassword 是原始口令的 Base64。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecoverResetRequest {
    private String username;
    private String code;
    private String newPassword;
}
