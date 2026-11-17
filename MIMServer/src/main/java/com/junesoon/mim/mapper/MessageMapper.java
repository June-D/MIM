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
     * 未读数，忽略已经从收信人视角清除的消息（id 不超过清除点）。
     *
     * <p>不能写成 default 方法：MyBatis 会直接调用 default，对应的 XML 永远不会执行。
     */
    long countUnreadAfter(@Param("fromUsername") String fromUsername,
        @Param("toUsername") String toUsername, @Param("afterId") long afterId);

    /**
     * 这段对话里目前最大的消息 id。没有消息时返回 null。
     */
    Long maxConversationId(@Param("a") String a, @Param("b") String b);

    /**
     * 清除双方时抹掉密文。只清自己这边时不能调用，对方还要读这些消息。
     */
    int wipeConversation(@Param("a") String a, @Param("b") String b,
        @Param("beforeId") long beforeId);

    /**
     * 撤回：按 client_msg_id 一次性清空所有设备副本的密文。
     *
     * @return 影响行数；0 表示无权撤回或已撤回
     */
    int recall(@Param("clientMsgId") String clientMsgId,
        @Param("fromUsername") String fromUsername, @Param("recalledAt") long recalledAt);
}
