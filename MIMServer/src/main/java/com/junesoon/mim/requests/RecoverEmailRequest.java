package com.junesoon.mim.requests;

import lombok.Data;

/**
 * 请求发送邮箱验证码。
 */
@Data
public class RecoverEmailRequest {
    private String username;
}
