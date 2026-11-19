package com.junesoon.mim.mapper;

import com.junesoon.mim.entity.CipherMessage;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface MessageMapper {

    int insert(CipherMessage message);

    /**
     * 幂等查询：同一条逻辑消息对同一目标设备只应有一份。
     *
     * <p>唯一键是 {@code (client_msg_id, target_device_id)}，不含发信人：
     * sealed 消息本来就不保存明文发信人。
     */
    CipherMessage selectByClientMsgId(@Param("clientMsgId") String clientMsgId,
        @Param("targetDeviceId") String targetDeviceId);

    CipherMessage selectById(@Param("id") Long id);

    /**
     * 按 clientMsgId 取当前用户拥有的任意一份副本（不限目标设备），
     * 用于撤回时的权限与时限判定。
     */
    CipherMessage selectOwnedCopy(@Param("clientMsgId") String clientMsgId,
        @Param("fromUser") String fromUser, @Param("fromRef") String fromRef);

    /**
     * 当前用户参与过的原始副本（不含设备间转发的副本）。
     */
    CipherMessage selectParticipantCopy(@Param("clientMsgId") String clientMsgId,
        @Param("username") String username, @Param("userRef") String userRef);

    /**
     * 会话历史，优先取这台设备解得开的副本（旧消息，发信人是明文用户名）。
     */
    List<CipherMessage> selectHistoryForDevice(@Param("viewer") String viewer,
        @Param("peer") String peer, @Param("deviceId") String deviceId,
        @Param("beforeId") Long beforeId, @Param("limit") int limit);

    /**
     * 会话历史，按 client_msg_id 去重（旧消息，发信人是明文用户名）。
     */
    List<CipherMessage> selectHistory(@Param("a") String a, @Param("b") String b,
        @Param("beforeId") Long beforeId, @Param("limit") int limit);

    /**
     * sealed 消息历史：from_ref 是发信人盲引用，to_user 是收信人用户名。
     */
    List<CipherMessage> selectRefHistory(@Param("fromRef") String fromRef,
        @Param("toUser") String toUser, @Param("beforeId") Long beforeId,
        @Param("limit") int limit);

    /**
     * sealed 消息的按设备历史。viewer 用于识别收件副本，
     * deviceRef 用于在发件方向挑出本设备发出的那份副本。
     */
    List<CipherMessage> selectRefHistoryForDevice(@Param("fromRef") String fromRef,
        @Param("toUser") String toUser, @Param("viewer") String viewer,
        @Param("deviceId") String deviceId, @Param("deviceRef") String deviceRef,
        @Param("beforeId") Long beforeId, @Param("limit") int limit);

    /**
     * 按会话标签查历史（不透明标签，双方共享）。
     * to_user 限定在会话双方，避免标签被复用后串到别的会话。
     */
    /**
     * 按会话标签查历史的按设备变体：收件侧必须选发给本设备的副本。
     */
    List<CipherMessage> selectTagHistoryForDevice(@Param("tags") List<String> tags,
        @Param("viewer") String viewer, @Param("peer") String peer,
        @Param("deviceId") String deviceId, @Param("beforeId") Long beforeId,
        @Param("limit") int limit);

    /**
     * 按会话标签取本人参与的一份副本（撤回权限判定）。
     */
    CipherMessage selectOwnedCopyByTags(@Param("tags") List<String> tags,
        @Param("clientMsgId") String clientMsgId);

    /**
     * 按会话标签取原消息（跨设备同步的授权依据）。
     */
    CipherMessage selectParticipantCopyByTag(@Param("clientMsgId") String clientMsgId,
        @Param("tag") String tag);

    /**
     * 标签会话的已读 / 未读 / 清除点相关操作。
     */
    int markReadByTags(@Param("tags") List<String> tags,
        @Param("toUsername") String toUsername, @Param("beforeId") Long beforeId);

    /**
     * 双写期未读数：盲引用与标签命中同一逻辑消息时只算一次。
     */
    long countUnreadByRefOrTags(@Param("fromRef") String fromRef,
        @Param("tags") List<String> tags, @Param("toUsername") String toUsername,
        @Param("afterId") Long afterId);

    long countUnreadByTags(@Param("tags") List<String> tags,
        @Param("toUsername") String toUsername, @Param("afterId") Long afterId);

    Long maxTagConversationId(@Param("tags") List<String> tags,
        @Param("viewer") String viewer, @Param("peer") String peer);

    int wipeTagConversation(@Param("tags") List<String> tags,
        @Param("viewer") String viewer, @Param("peer") String peer,
        @Param("beforeId") long beforeId);

    List<CipherMessage> selectTagHistory(@Param("tags") List<String> tags,
        @Param("viewer") String viewer, @Param("peer") String peer,
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

    int markReadByRef(@Param("fromRef") String fromRef, @Param("toUsername") String toUsername,
        @Param("beforeId") Long beforeId);

    long countUnread(@Param("fromUsername") String fromUsername,
        @Param("toUsername") String toUsername);

    long countUnreadByRef(@Param("fromRef") String fromRef,
        @Param("toUsername") String toUsername);

    /**
     * 未读数，忽略已经从收信人视角清除的消息（id 不超过清除点）。
     *
     * <p>不能写成 default 方法：MyBatis 会直接调用 default，对应的 XML 永远不会执行。
     */
    long countUnreadAfter(@Param("fromUsername") String fromUsername,
        @Param("toUsername") String toUsername, @Param("afterId") long afterId);

    long countUnreadAfterByRef(@Param("fromRef") String fromRef,
        @Param("toUsername") String toUsername, @Param("afterId") long afterId);

    /**
     * 这段对话里目前最大的消息 id（旧消息）。没有消息时返回 null。
     */
    Long maxConversationId(@Param("a") String a, @Param("b") String b);

    /**
     * sealed 方向的最大消息 id：from_ref 指向发信人盲引用。
     */
    Long maxRefConversationId(@Param("fromRef") String fromRef, @Param("toUser") String toUser);

    /**
     * 清除双方时抹掉密文。只清自己这边时不能调用，对方还要读这些消息。
     */
    int wipeConversation(@Param("a") String a, @Param("b") String b,
        @Param("beforeId") long beforeId);

    int wipeRefConversation(@Param("fromRef") String fromRef, @Param("toUser") String toUser,
        @Param("beforeId") long beforeId);

    /**
     * 用一次性撤回凭证撤回：只比对哈希，不需要发信人引用。
     *
     * @return 影响行数；0 表示凭证不匹配或已撤回
     */
    int recallByToken(@Param("clientMsgId") String clientMsgId,
        @Param("recallTokenHash") String recallTokenHash, @Param("recalledAt") long recalledAt);

    /**
     * 旧消息兼容撤回：没有 recall_token_hash 的历史数据按发信人引用授权。
     */
    int recallBySender(@Param("clientMsgId") String clientMsgId,
        @Param("fromUser") String fromUser, @Param("fromRef") String fromRef,
        @Param("recalledAt") long recalledAt);
}
