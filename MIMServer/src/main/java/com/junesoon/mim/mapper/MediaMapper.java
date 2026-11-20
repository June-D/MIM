package com.junesoon.mim.mapper;

import com.junesoon.mim.entity.MediaObject;
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
}
