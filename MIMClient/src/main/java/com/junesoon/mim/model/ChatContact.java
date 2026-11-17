package com.junesoon.mim.model;

import java.io.Serializable;
import java.util.List;
import java.util.ArrayList;
import lombok.Data;

/**
 * 联系人分组。
 */
@Data
public class ChatContact implements Serializable {

    private static final long serialVersionUID = 1L;

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
    private List<ChatUser> users = new ArrayList<>();

    /**
     * 分组内是否有未读。仅本地 UI 使用，服务端不下发。
     */
    private transient boolean unread;
}
