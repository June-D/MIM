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

    /** 单个分片上传的进度。{@code received} 是已落盘的分片下标（升序）。 */
    record UploadStatus(int chunkSize, int totalChunks, java.util.List<Integer> received) {
    }

    /**
     * 开始一次分片上传，返回 uploadId。
     *
     * <p>为什么需要分片：单请求上传大文件会把内存与网关一起撑爆。分片后每个请求
     * 只带一块，断了可以从缺的那片继续。
     */
    String initUpload(String owner, long totalSize, int chunkSize);

    /** 收下一个分片，返回已收到的分片数。同一片重复上传直接覆盖（幂等）。 */
    int putChunk(String owner, String uploadId, int index, byte[] chunk);

    UploadStatus uploadStatus(String owner, String uploadId);

    /** 校验分片齐全后合并成正式密文，落 t_media 并返回记录。 */
    MediaObject completeUpload(String owner, String uploadId);

    boolean abortUpload(String owner, String uploadId);

    /** 清理超过 cutoff 仍未完成的分片上传（半成品），返回清理条数。 */
    int purgeStaleUploads(long cutoffMillis, int batchSize);
}
