package com.junesoon.mim.service;

import com.junesoon.mim.responses.FriendRequestView;
import lombok.Data;

/**
 * 申请操作的结果。
 *
 * <p>{@link #notifyUser} 非空时，控制器应给这个用户推一条通知。
 * 重复提交同一条待处理申请时它为空，避免每次点击都响一次。
 */
@Data
public class FriendRequestResult {

    private FriendRequestView view;

    /** 需要推送的用户；幂等重复时为 null */
    private String notifyUser;

    public static FriendRequestResult of(FriendRequestView view, String notifyUser) {
        FriendRequestResult result = new FriendRequestResult();
        result.view = view;
        result.notifyUser = notifyUser;
        return result;
    }
}
