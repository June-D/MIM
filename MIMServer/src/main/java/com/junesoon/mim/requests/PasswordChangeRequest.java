package com.junesoon.mim.requests;

import lombok.Data;

/**
 * 修改登录密码请求。字段是原始口令的 Base64，与注册/登录保持一致。
 */
@Data
public class PasswordChangeRequest {
    private String oldPassword;
    private String newPassword;
}
