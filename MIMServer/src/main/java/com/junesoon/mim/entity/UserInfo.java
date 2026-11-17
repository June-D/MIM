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

}
