package com.junesoon.mim.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: MIMServer
 * @description:
 * @version: 1.0
 * @create: 2024-03-01 15:53
 */
@Data
@NoArgsConstructor
public class ChatGroup implements Serializable, Comparable<ChatGroup> {
    private static final long serialVersionUID = 1L;
    private Integer id;
    private String ownerUser;
    private String groupId;
    private String groupName;
    private Date crtTime;

    @Override
    public int compareTo(ChatGroup o) {
        return this.getCrtTime().compareTo(o.getCrtTime());
    }
}
