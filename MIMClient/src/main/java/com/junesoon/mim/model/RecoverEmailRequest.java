package com.junesoon.mim.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 请求发送找回密码邮箱验证码。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecoverEmailRequest {
    private String username;
}
