package com.junesoon.mim.entity;

import lombok.Getter;
import lombok.Setter;

/** 账号头像的元数据；图片本身在磁盘，随时可由版本号失效缓存。 */
@Getter
@Setter
public class Avatar {

    private String username;

    /** 每次上传自增；客户端据此判断本机缓存是否过期。 */
    private int version;

    private long updatedAt;
}
