package com.junesoon.mim.mapper;

import com.junesoon.mim.entity.OneTimePreKey;
import com.junesoon.mim.entity.SignedPreKey;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 预密钥访问。所有操作都带 deviceId 维度。
 */
public interface PreKeyMapper {

    // ---------------- 签名预密钥 ----------------

    SignedPreKey selectSignedPreKey(@Param("username") String username,
        @Param("deviceId") String deviceId);

    int upsertSignedPreKey(SignedPreKey key);

    // ---------------- 一次性预密钥 ----------------

    int insertOneTimePreKey(OneTimePreKey key);

    /**
     * 批量插入。重复编号被忽略（INSERT IGNORE），避免重传时整体失败。
     */
    int batchInsertOneTimePreKeys(@Param("keys") List<OneTimePreKey> keys);

    /**
     * 领取并删除一个一次性预密钥（事务内配合 FOR UPDATE）。
     */
    OneTimePreKey selectOneTimePreKeyForUpdate(@Param("username") String username,
        @Param("deviceId") String deviceId);

    int deleteOneTimePreKey(@Param("id") Long id, @Param("username") String username);

    int countOneTimePreKeys(@Param("username") String username,
        @Param("deviceId") String deviceId);

    int deleteAllOneTimePreKeys(@Param("username") String username,
        @Param("deviceId") String deviceId);
}
