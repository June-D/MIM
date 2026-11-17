package com.junesoon.mim.responses;

import lombok.Data;

/**
 * 用户搜索结果。
 *
 * <p>刻意不直接返回 {@link com.junesoon.mim.entity.User}：实体里有 password 字段，
 * 序列化出去等于泄漏 BCrypt 哈希。搜索结果也不需要 status / lastLoginTime 等内部字段。
 */
@Data
public class UserSummary {

    private String username;

    private String nickname;

    private String pic;

    private String signature;

    /**
     * 是否已建立加密身份（能接收消息）。false 表示对方很久没登录过。
     */
    private boolean canReceive;

    public static UserSummary of(String username, String nickname, String pic, String signature,
        boolean canReceive) {
        UserSummary summary = new UserSummary();
        summary.username = username;
        summary.nickname = nickname;
        summary.pic = pic;
        summary.signature = signature;
        summary.canReceive = canReceive;
        return summary;
    }
}
