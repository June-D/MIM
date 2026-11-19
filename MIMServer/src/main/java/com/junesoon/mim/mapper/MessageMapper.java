package com.junesoon.mim.mapper;

import com.junesoon.mim.entity.CipherMessage;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 密文消息存储。
 *
 * <p>Phase 2 断代后，消息只有一条存储路径：
 * <ul>
 *   <li>不保存发信人/发信设备的任何形式（明文或盲引用）；</li>
 *   <li>会话归属由客户端生成的 {@code conversation_tag} 表达；</li>
 *   <li>撤回只认一次性 token 的哈希。</li>
 * </ul>
 * 因此这里的查询全部按标签、收信人与目标设备进行，没有任何用户名反查。
 *
 * [INPUT]: 只依赖实体与 MyBatis 注解
 * [OUTPUT]: 消息的写入、标签查询、投递与撤回
 * [POS]: MessageServiceImpl 的唯一持久化入口
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
public interface MessageMapper {

    int insert(CipherMessage message);

    /**
     * 幂等查询：同一条逻辑消息对同一目标设备只应有一份。
     *
     * <p>唯一键是 {@code (client_msg_id, target_device_id)}，不含发信人。
     */
    CipherMessage selectByClientMsgId(@Param("clientMsgId") String clientMsgId,
        @Param("targetDeviceId") String targetDeviceId);

    CipherMessage selectById(@Param("id") Long id);

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
     * 按会话标签查历史的按设备变体：收件侧必须选发给本设备的副本。
     */
    List<CipherMessage> selectTagHistoryForDevice(@Param("tags") List<String> tags,
        @Param("viewer") String viewer, @Param("peer") String peer,
        @Param("deviceId") String deviceId, @Param("beforeId") Long beforeId,
        @Param("limit") int limit);

    /**
     * 按会话标签查历史（不指定设备时使用）。
     */
    List<CipherMessage> selectTagHistory(@Param("tags") List<String> tags,
        @Param("viewer") String viewer, @Param("peer") String peer,
        @Param("beforeId") Long beforeId, @Param("limit") int limit);

    /**
     * 标签会话的已读标记。
     */
    int markReadByTags(@Param("tags") List<String> tags,
        @Param("toUsername") String toUsername, @Param("beforeId") Long beforeId);

    /**
     * 标签会话的未读数；{@code afterId} 用于忽略已清除的消息。
     */
    long countUnreadByTags(@Param("tags") List<String> tags,
        @Param("toUsername") String toUsername, @Param("afterId") Long afterId);

    /**
     * 这段会话里目前最大的消息 id；没有消息时返回 null。
     */
    Long maxTagConversationId(@Param("tags") List<String> tags,
        @Param("viewer") String viewer, @Param("peer") String peer);

    /**
     * 清除双方时抹掉密文。只清自己这边时不能调用，对方还要读这些消息。
     */
    int wipeTagConversation(@Param("tags") List<String> tags,
        @Param("viewer") String viewer, @Param("peer") String peer,
        @Param("beforeId") long beforeId);

    /**
     * 本设备的未投递消息。
     */
    List<CipherMessage> selectUndelivered(@Param("toUsername") String toUsername,
        @Param("deviceId") String deviceId, @Param("limit") int limit);

    int markDelivered(@Param("id") Long id);

    int markDeliveredUpTo(@Param("toUsername") String toUsername,
        @Param("deviceId") String deviceId, @Param("beforeId") long beforeId);

    /**
     * 用一次性撤回凭证撤回：只比对哈希，不需要发信人引用。
     *
     * @return 影响行数；0 表示凭证不匹配或已撤回
     */
    int recallByToken(@Param("clientMsgId") String clientMsgId,
        @Param("recallTokenHash") String recallTokenHash, @Param("recalledAt") long recalledAt);
}
