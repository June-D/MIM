package com.junesoon.mim.model;

import java.io.Serializable;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class JwtAuthenticationRequest implements Serializable {

    private static final long serialVersionUID = -8445943548965154778L;

    private String username;
    private String password;

    /**
     * 启用 TOTP 二次验证后携带的 6 位验证码。
     */
    private String code;

    /**
     * 注册时可选填写的找回密码邮箱。
     */
    private String email;

    /**
     * 注册时必须为 true：已阅读并同意用户协议。
     */
    private Boolean agreedTerms;

    /**
     * 用户协议版本，必须与服务端当前版本一致。
     */
    private String termsVersion;

    public JwtAuthenticationRequest(String username, String password) {
        this.username = username;
        this.password = password;
    }
}
