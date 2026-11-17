package com.junesoon.mim.mapper;

import com.junesoon.mim.entity.CipherMessage;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface MessageMapper {

    int insert(CipherMessage message);

    /**
     * 幂等查询：同一条逻辑消息对同一目标设备只应有一份。
     */
    CipherMessage selectByClientMsgId(@Param("fromUsername") String fromUsername,
        @Param("clientMsgId") String clientMsgId,
        @Param("targetDeviceId") String targetDeviceId);

    CipherMessage selectById(@Param("id") Long id);

    /**
     * 按 clientMsgId 取任意一份副本（不限目标设备），用于撤回时的权限与时限判定。
     */
    CipherMessage selectAnyByClientMsgId(@Param("fromUsername") String fromUsername,
        @Param("clientMsgId") String clientMsgId);

    /**
     * 会话历史，按 client_msg_id 去重（多设备副本不会重复出现）。
     */
    List<CipherMessage> selectHistory(@Param("a") String a, @Param("b") String b,
        @Param("beforeId") Long beforeId, @Param("limit") int limit);

    /**
     * 本设备的未投递消息。
     */
    List<CipherMessage> selectUndelivered(@Param("toUsername") String toUsername,
        @Param("deviceId") String deviceId, @Param("limit") int limit);

    int markDelivered(@Param("id") Long id);

    int markDeliveredUpTo(@Param("toUsername") String toUsername,
        @Param("deviceId") String deviceId, @Param("beforeId") long beforeId);

    int markRead(@Param("fromUsername") String fromUsername, @Param("toUsername") String toUsername,
        @Param("beforeId") Long beforeId);

    long countUnread(@Param("fromUsername") String fromUsername,
        @Param("toUsername") String toUsername);

    /**
     * 撤回：按 client_msg_id 一次性清空所有设备副本的密文。
     *
     * @return 影响行数；0 表示无权撤回或已撤回
     */
    int recall(@Param("clientMsgId") String clientMsgId,
        @Param("fromUsername") String fromUsername, @Param("recalledAt") long recalledAt);
}
