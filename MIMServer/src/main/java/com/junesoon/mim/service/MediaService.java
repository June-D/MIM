package com.junesoon.mim.service;

import com.junesoon.mim.entity.MediaObject;
import java.nio.file.Path;

/**
 * 加密媒体存储。
 *
 * <p>入参与出参都是密文：服务端不解析、不解密，也不保存文件名与媒体类型。
 */
public interface MediaService {

    MediaObject save(String owner, byte[] encrypted);

    /** 读取密文文件；不存在或不属于任何记录时返回 null。 */
    Path load(String id);

    MediaObject metadata(String id);

    boolean delete(String owner, String id);
}
