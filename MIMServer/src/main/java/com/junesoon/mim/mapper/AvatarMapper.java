package com.junesoon.mim.mapper;

import com.junesoon.mim.entity.Avatar;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 头像元数据。 */
@Mapper
public interface AvatarMapper {

    Avatar select(@Param("username") String username);

    int upsert(@Param("username") String username, @Param("version") int version,
        @Param("updatedAt") long updatedAt);

    int delete(@Param("username") String username);
}
