package com.junesoon.mim.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 对话用户。
 */
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
     * 是否在线
     */
    private Boolean online = Boolean.FALSE;
    /**
     * 备注或标签，显示用
     */
    private String labelName;
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
     * 按创建时间升序；时间缺失时排到末尾，避免 NPE 打断整张联系人列表的渲染。
     */
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
