package com.junesoon.mim.service;

import com.junesoon.mim.entity.CipherMessage;
import com.junesoon.mim.responses.ClearResult;
import java.util.List;

public interface MessageService {

    /**
     * 发送一条密文消息。
     *
     * <p>多设备下同一个逻辑消息可能带多份密文（每个目标设备一份），
     * 以 {@code targetDeviceId} 区分。发信人身份由鉴权层传入。
     */
    List<CipherMessage> send(String authenticatedUser, String fromDeviceId, List<CipherMessage> messages);

    /**
     * 匿名提交（Phase 2c）：用发送者令牌替代 JWT。
     * 只接受带会话标签的 sealed 消息；服务端不保存任何发送者形式。
     */
    List<CipherMessage> sendAnonymous(String senderToken, List<CipherMessage> messages);

    /**
     * 会话历史。{@code deviceId} 不为空时，优先返回这台设备解得开的那一份副本。
     * {@code tags} 是客户端持有的会话标签，非空时额外按标签查询
     * （双写期与盲引用查询并存，结果按 clientMsgId 去重）。
     */
    List<CipherMessage> history(String username, String peer, Long beforeId, int limit,
        String deviceId, List<String> tags);

    default List<CipherMessage> history(String username, String peer, Long beforeId, int limit,
        String deviceId) {
        return history(username, peer, beforeId, limit, deviceId, null);
    }

    default List<CipherMessage> history(String username, String peer, Long beforeId, int limit) {
        return history(username, peer, beforeId, limit, null, null);
    }

    /**
     * 把已有消息的原文，换成当前设备为另一台自己的设备加密的副本。
     * 发信人、收信人、消息 ID 以库里的原消息为准，请求体改不了。
     */
    List<CipherMessage> syncReplicas(String username, String fromDeviceId, List<CipherMessage> replicas);

    /**
     * 通知本账号的其他设备：这台设备需要它们转发历史。
     */
    void requestHistorySync(String username, String deviceId);

    /**
     * 本设备尚未投递的消息。
     */
    List<CipherMessage> undelivered(String username, String deviceId, int limit);

    void markDelivered(Long id);

    int markDeliveredTo(String username, String deviceId, long beforeId);

    /**
     * 标记已读。{@code tags} 为客户端持有的会话标签，非空时一并按标签标记。
     */
    int markRead(String username, String peer, Long beforeId, List<String> tags);

    default int markRead(String username, String peer, Long beforeId) {
        return markRead(username, peer, beforeId, null);
    }

    /**
     * 未读数。{@code tags} 非空时额外按标签统计（不依赖盲引用）。
     */
    long countUnread(String username, String peer, List<String> tags);

    default long countUnread(String username, String peer) {
        return countUnread(username, peer, null);
    }

    /**
     * 撤回自己发出的消息（按 clientMsgId 撤回所有设备副本）。
     *
     * <p>{@code recallToken} 是发送时客户端生成的一次性凭证；服务端只保存哈希。
     * 没有凭证的历史消息退回发信人引用兼容路径。
     *
     * @return 撤回后的消息快照；null 表示不存在
     */
    CipherMessage recall(String username, String clientMsgId, String recallToken,
        List<String> tags);

    default CipherMessage recall(String username, String clientMsgId, String recallToken) {
        return recall(username, clientMsgId, recallToken, null);
    }

    default CipherMessage recall(String username, String clientMsgId) {
        return recall(username, clientMsgId, null, null);
    }

    /**
     * 清除与某人的对话。{@code forBoth} 为真时对方的记录也一起清掉，并抹掉服务端密文。
     */
    /**
     * 清除会话。{@code tags} 非空时同时按标签写入清除点并抹除密文，
     * 这样未投递查询不必再从盲引用反推会话对象。
     */
    ClearResult clearConversation(String username, String peer, boolean forBoth,
        List<String> tags);

    default ClearResult clearConversation(String username, String peer, boolean forBoth) {
        return clearConversation(username, peer, forBoth, null);
    }
}
