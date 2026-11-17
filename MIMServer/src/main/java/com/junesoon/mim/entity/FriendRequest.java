package com.junesoon.mim.entity;

import java.io.Serializable;
import lombok.Data;

/**
 * 一条好友申请。
 *
 * <p>关系还没建立之前，状态只存在这张表里。多实例下不能放在内存 Map：
 * 申请打到 Pod A，同意的请求可能打到 Pod B。
 */
@Data
public class FriendRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待对方同意 */
    public static final int PENDING = 0;
    /** 对方已同意，双方已是好友 */
    public static final int ACCEPTED = 1;
    /** 对方拒绝。之后可以再次申请，复用这一行 */
    public static final int REJECTED = 2;

    private Long id;

    private String fromUser;

    private String toUser;

    private int status;

    private Long createdAt;

    private Long updatedAt;
}
