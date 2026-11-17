package com.junesoon.mim.model;

import lombok.Data;

@Data
public class UserInfo {
    private String id;
    private String username;
    private String nickname;
    private String pic;
    private String signature;

    /**
     * 仅账号本人可见的找回密码邮箱。
     */
    private String email;
}
