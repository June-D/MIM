package com.junesoon.mim.entity;

import java.io.Serializable;
import lombok.Data;

/**
 * 加密媒体对象的元数据。
 *
 * <p>服务端只保存密文文件与「谁上传、多大、什么时候」，
 * 不保存文件名、类型或任何明文信息——这些都放在端到端密文里。
 */
@Data
public class MediaObject implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 32 字节随机数的 Base64URL，不可猜测。 */
    private String id;

    private String owner;

    private long size;

    private long crtTime;
}
