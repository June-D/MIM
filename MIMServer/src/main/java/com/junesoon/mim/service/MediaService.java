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

    /**
     * 清理早于 {@code cutoffMillis} 的密文，返回删除条数。
     *
     * <p>先删文件再删库行：文件删不掉时保留记录，下一轮再试，
     * 这样不会出现「库里没有、磁盘留着」的孤儿文件。
     */
    int purgeOlderThan(long cutoffMillis, int batchSize);
}
