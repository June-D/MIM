package com.junesoon.mim.entity;

import lombok.Getter;
import lombok.Setter;

/**
 * 分片上传的进度记录。分片文件在磁盘（`&lt;media&gt;/.parts/&lt;uploadId&gt;/`），
 * 表里只存分片大小、总大小与时间，够用来算「还缺哪几片」。
 */
@Getter
@Setter
public class MediaUpload {

    private String uploadId;

    private String owner;

    /** 密文总字节数。 */
    private long totalSize;

    private int chunkSize;

    private long createdAt;

    private long updatedAt;
}
