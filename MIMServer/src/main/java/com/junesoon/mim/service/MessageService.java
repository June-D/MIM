package com.junesoon.mim.service;

import com.junesoon.mim.entity.CipherMessage;
import java.util.List;

public interface MessageService {

    /**
     * 发送一条密文消息。
     *
     * <p>多设备下同一个逻辑消息可能带多份密文（每个目标设备一份），
     * 以 {@code targetDeviceId} 区分。发信人身份由鉴权层传入。
     */
    List<CipherMessage> send(String authenticatedUser, String fromDeviceId, List<CipherMessage> messages);

    List<CipherMessage> history(String username, String peer, Long beforeId, int limit);

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
}
