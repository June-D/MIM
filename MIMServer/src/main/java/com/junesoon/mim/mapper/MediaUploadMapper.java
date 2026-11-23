package com.junesoon.mim.mapper;

import com.junesoon.mim.entity.MediaUpload;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 分片上传的进度索引。 */
@Mapper
public interface MediaUploadMapper {

    int insert(MediaUpload upload);

    MediaUpload select(@Param("uploadId") String uploadId);

    /** 同一用户的未完成上传数，用来限制并发，避免半成品堆满磁盘。 */
    int countByOwner(@Param("owner") String owner);

    /** 早于 cutoff 未再变动的上传，供保留期巡检清理。 */
    List<MediaUpload> selectStale(@Param("cutoff") long cutoff, @Param("limit") int limit);

    /** 收到一个分片后刷新「最后活跃时间」，保留期巡检据此判断半成品是否已废弃。 */
    int touch(@Param("uploadId") String uploadId, @Param("updatedAt") long updatedAt);

    int delete(@Param("uploadId") String uploadId);
}
