package com.junesoon.mim.mapper;

import com.junesoon.mim.entity.RecoveryCode;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface RecoveryCodeMapper {

    int deleteAllByUsername(@Param("username") String username);

    int insertBatch(@Param("codes") List<RecoveryCode> codes);

    List<RecoveryCode> selectUnusedByUsername(@Param("username") String username);

    /**
     * 原子地占用一个恢复码；返回 0 表示已被并发请求用掉。
     */
    int markUsed(@Param("id") Long id, @Param("usedAt") long usedAt);

    int countUnused(@Param("username") String username);
}
