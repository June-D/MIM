package com.junesoon.mim.model;

import lombok.Data;

/**
 * 搜索结果里的用户摘要。不含任何内部字段。
 */
@Data
public class UserSummary {

    private String username;

    private String nickname;

    private String pic;

    private String signature;

    /**
     * 是否已建立加密身份（能接收消息）。false 说明对方从未登录过。
     */
    private boolean canReceive;
}
