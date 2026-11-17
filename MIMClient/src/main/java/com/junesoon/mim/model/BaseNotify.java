package com.junesoon.mim.model;

import lombok.Data;

/**
 * @program: MIMServer
 * @description: 基础通知类
 * @version: 1.0
 * @create: 2024-03-12 12:08
 */
@Data
public class BaseNotify<T> {
    private Integer type;

    private T data;
}
