package com.junesoon.mim.entity;

import java.io.Serializable;
import lombok.Data;

/**
 * WebSocket 下行通知信封。
 */
@Data
public class BaseNotify<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer type;

    private T data;

    public static <T> BaseNotify<T> of(Integer type, T data) {
        BaseNotify<T> notify = new BaseNotify<>();
        notify.setType(type);
        notify.setData(data);
        return notify;
    }
}
