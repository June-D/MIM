package com.junesoon.mim.requests;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JwtAuthenticationRequest implements Serializable {

    private static final long serialVersionUID = -8445943548965154778L;

    private String username;

    private String password;

    /**
     * 启用 TOTP 二次验证后登录必须携带：6 位动态验证码，或一次性恢复码。
     */
    private String code;

    /**
     * 注册时可选填写的找回密码邮箱。仅本人可见，不做真实性校验。
     */
    private String email;

    /**
     * 注册时必须为 true：已阅读并同意用户协议。
     */
    private Boolean agreedTerms;

    /**
     * 用户协议版本，由客户端携带；服务端以当前版本为准记录。
     */
    private String termsVersion;
}
