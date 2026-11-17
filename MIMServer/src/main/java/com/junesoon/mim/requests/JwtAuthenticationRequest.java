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
     * 启用 TOTP 二次验证后登录必须携带的 6 位验证码。
     */
    private String code;
}
