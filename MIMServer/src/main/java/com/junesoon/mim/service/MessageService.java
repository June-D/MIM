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
     * 会话历史。{@code deviceId} 不为空时，优先返回这台设备解得开的那一份副本。
     */
    List<CipherMessage> history(String username, String peer, Long beforeId, int limit,
        String deviceId);

    default List<CipherMessage> history(String username, String peer, Long beforeId, int limit) {
        return history(username, peer, beforeId, limit, null);
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

    int markRead(String username, String peer, Long beforeId);

    long countUnread(String username, String peer);

    /**
     * 撤回自己发出的消息（按 clientMsgId 撤回所有设备副本）。
     *
     * @return 撤回后的消息快照；null 表示不存在
     */
    CipherMessage recall(String username, String clientMsgId);

    /**
     * 清除与某人的对话。{@code forBoth} 为真时对方的记录也一起清掉，并抹掉服务端密文。
     */
    ClearResult clearConversation(String username, String peer, boolean forBoth);
}
