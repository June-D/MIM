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

    public JwtAuthenticationRequest(String username, String password) {
        this.username = username;
        this.password = password;
    }
}
