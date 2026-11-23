package com.junesoon.mim.requests;

import lombok.Getter;
import lombok.Setter;

/** 开始一次分片上传：密文总大小与分片大小，都由客户端决定。 */
@Getter
@Setter
public class InitUploadRequest {

    /** 密文总字节数（不是明文大小）。 */
    private long size;

    private int chunkSize;
}
