package com.junesoon.mim.entity;

import java.io.Serializable;
import lombok.Data;

@Data
public class UserInfo implements Serializable {
    private String id;
    private String username;
    private String nickname;
    private String pic;
    private String signature;

    /**
     * 仅返回给账号本人，用于找回密码与确认；不会出现在搜索/联系人接口里。
     */
    private String email;

}
