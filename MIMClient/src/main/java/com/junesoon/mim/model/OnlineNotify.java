package com.junesoon.mim.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户在线状态变更通知。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OnlineNotify {
    private String username;

    private boolean online;
}
