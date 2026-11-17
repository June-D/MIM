package com.junesoon.mim.responses;

import lombok.Data;

/**
 * 好友申请的对外视图。
 *
 * <p>只暴露对方是谁、申请方向和状态，不把表结构原样返回。
 */
@Data
public class FriendRequestView {

    public static final String IN = "in";
    public static final String OUT = "out";

    public static final String PENDING = "pending";
    public static final String ACCEPTED = "accepted";
    public static final String REJECTED = "rejected";

    private Long id;

    /** 对方用户名 */
    private String username;

    /** in：待我处理；out：我发出的 */
    private String direction;

    /** pending / accepted / rejected */
    private String status;

    public static FriendRequestView of(Long id, String username, String direction, String status) {
        FriendRequestView view = new FriendRequestView();
        view.id = id;
        view.username = username;
        view.direction = direction;
        view.status = status;
        return view;
    }
}
