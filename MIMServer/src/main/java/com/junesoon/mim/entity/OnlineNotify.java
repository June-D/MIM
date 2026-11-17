package com.junesoon.mim.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 在线状态变更通知。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OnlineNotify implements Serializable {

    private static final long serialVersionUID = 1L;

    private String username;

    /**
     * true 表示上线，false 表示离线。
     */
    private boolean online;
}
