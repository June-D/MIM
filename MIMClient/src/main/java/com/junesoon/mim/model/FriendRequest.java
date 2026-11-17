package com.junesoon.mim.model;

import lombok.Data;

/**
 * 一条好友申请。对方同意之前，不会出现在联系人里。
 */
@Data
public class FriendRequest {

    private Long id;

    /** 对方用户名 */
    private String username;

    /** in：待我处理；out：我发出的 */
    private String direction;

    /** pending / accepted / rejected */
    private String status;
}
