package com.junesoon.mim.requests;

import lombok.Data;

/**
 * 使用邮箱验证码重置密码。
 */
@Data
public class RecoverResetRequest {
    private String username;
    private String code;
    private String newPassword;
}
