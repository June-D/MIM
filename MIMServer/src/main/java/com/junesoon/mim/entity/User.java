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

    private static final long serialVersionUID = 1L;

    @Override
    public String toString() {
        return nickname;
    }
}
