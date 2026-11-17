package com.junesoon.mim.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

@Data
public class User implements Serializable {
    private Integer id;

    private String username;

    private String password;

    private String nickname;

    private String pic;

    private String signature;

    private String status;

    private Date crtTime;

    private Date updTime;

    private Date lastLoginTime;

    /**
     * TOTP 共享密钥（Base32）。启用二次验证后才有值。
     */
    private String totpSecret;

    /**
     * 是否已启用 TOTP 二次验证。
     */
    private Boolean totpEnabled;

    private static final long serialVersionUID = 1L;

    @Override
    public String toString() {
        return nickname;
    }
}
