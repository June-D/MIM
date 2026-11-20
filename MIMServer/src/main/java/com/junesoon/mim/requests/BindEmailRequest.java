package com.junesoon.mim.requests;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 绑定 / 更换找回邮箱。
 *
 * <p>必填当前密码：拿到一个有效令牌不等于能改写找回通道，
 * 否则令牌泄漏会直接变成「改邮箱 → 收验证码 → 重置密码」的接管链。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BindEmailRequest {

    /** 当前登录密码（明文，由 TLS 保护）。 */
    private String password;

    /** 新邮箱；空字符串表示解绑（解绑后无法用邮箱找回密码）。 */
    private String email;
}
