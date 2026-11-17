package com.junesoon.mim.model;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

@Data
public class ChatUser implements Serializable, Comparable<ChatUser> {

    private static final long serialVersionUID = 1L;

    private Integer id;
    /**
     * 拥有者
     */
    private String ownerUser;
    /**
     * 用户名
     */
    private String username;
    /**
     * 备注或标签，显示用
     */
    private String labelName;
    /**
     * 是否在线
     */
    private Boolean online = Boolean.FALSE;
    /**
     * 是否存在未读
     */
    private Boolean hasUnread = Boolean.FALSE;
    /**
     * 未读条数。用于红点徽标显示具体数字。
     */
    private Integer unreadCount = 0;
    /**
     * 头像
     */
    private String pic;
    /**
     * 昵称
     */
    private String nickname;
    /**
     * 分组ID
     */
    private String groupId;
    /**
     * 创建时间
     */
    private Date crtTime;

    /**
     * 展示名：优先备注，其次昵称，最后用户名。
     */
    public String displayName() {
        if (labelName != null && !labelName.trim().isEmpty()) {
            return labelName;
        }
        if (nickname != null && !nickname.trim().isEmpty()) {
            return nickname;
        }
        return username;
    }

    @Override
    public int compareTo(ChatUser other) {
        if (other == null) {
            return 1;
        }
        Date left = this.crtTime;
        Date right = other.crtTime;
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return left.compareTo(right);
    }
}
