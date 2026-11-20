package com.junesoon.mim.mapper;

import com.junesoon.mim.entity.MediaObject;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 加密媒体的索引。文件本身在磁盘，数据库只存可检索的元数据。
 */
@Mapper
public interface MediaMapper {

    int insert(MediaObject object);

    MediaObject selectById(@Param("id") String id);

    int deleteByIdAndOwner(@Param("id") String id, @Param("owner") String owner);

    /**
     * 早于 cutoff 的媒体 id，按创建时间从旧到新，最多 limit 条。
     *
     * <p>保留期巡检用：服务端看不到密文里的媒体引用关系，
     * 只能按时间兜底清理。
     */
    List<String> selectIdsOlderThan(@Param("cutoff") long cutoff,
        @Param("limit") int limit);

    /** 系统清理专用：不校验 owner。 */
    int deleteById(@Param("id") String id);
}
