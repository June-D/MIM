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
     * 领取该设备一个<b>未被预留</b>的一次性预密钥（事务内配合 FOR UPDATE）。
     */
    OneTimePreKey selectOneTimePreKeyForUpdate(@Param("username") String username,
        @Param("deviceId") String deviceId);

    /**
     * 同一请求者重试时，取回之前预留的 OPK，避免重复消耗。
     */
    OneTimePreKey selectClaimedOneTimePreKey(@Param("username") String username,
        @Param("deviceId") String deviceId, @Param("claimedBy") String claimedBy);

    /**
     * 把 OPK 预留給请求者。
     */
    int claimOneTimePreKey(@Param("id") Long id, @Param("username") String username,
        @Param("claimedBy") String claimedBy, @Param("claimedAt") long claimedAt);

    /**
     * 释放超时未使用的预留。
     */
    int releaseExpiredOneTimePreKeys(@Param("username") String username,
        @Param("deviceId") String deviceId, @Param("before") long before);

    /**
     * 消息真正使用 OPK 后删除；只允许预留者本人删除。
     */
    int deleteClaimedOneTimePreKey(@Param("username") String username,
        @Param("deviceId") String deviceId, @Param("preKeyId") int preKeyId,
        @Param("claimedBy") String claimedBy);

    int deleteOneTimePreKey(@Param("id") Long id, @Param("username") String username);

    int countOneTimePreKeys(@Param("username") String username,
        @Param("deviceId") String deviceId);

    int deleteAllOneTimePreKeys(@Param("username") String username,
        @Param("deviceId") String deviceId);
}
