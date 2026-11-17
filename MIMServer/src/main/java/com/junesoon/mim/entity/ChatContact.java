package com.junesoon.mim.entity;

import java.io.Serializable;
import java.util.List;
import lombok.Data;

/**
 * @program: MIMServer
 * @description: 联系人
 * @version: 1.0
 * @create: 2024-03-01 14:10
 */
@Data
public class ChatContact implements Serializable {

    /**
     * 分组ID
     */
    private String groupId;
    /**
     * 分组名称
     */
    private String groupName;
    /**
     * 分组成员
     */
    private List<ChatUser> users;

}
