package com.junesoon.mim.controller;

import com.junesoon.mim.auth.CurrentDevice;
import com.junesoon.mim.auth.CurrentUser;
import com.junesoon.mim.entity.CipherMessage;
import com.junesoon.mim.ratelimit.RateLimit;
import com.junesoon.mim.requests.ReadRequest;
import com.junesoon.mim.responses.ObjectRestResponse;
import com.junesoon.mim.service.MessageService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 密文消息接口。
 *
 * <p>发信人身份一律取自 JWT（{@link CurrentUser}），发信设备取自
 * {@code X-Device-Id} 头，请求体里的 fromUsername 会被忽略。
 *
 * <p>多设备：发送时请求体是<b>密文数组</b>，每个元素对应收信人的一个设备。
 */
@Slf4j
@RestController
@RequestMapping("message")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * 发送一条逻辑消息的所有设备副本。{@code clientMsgId} 保证重试幂等。
     */
    @PostMapping
    @RateLimit(limit = 120, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<List<CipherMessage>> send(@CurrentUser String username,
        @CurrentDevice String deviceId, @RequestBody List<CipherMessage> messages) {
        List<CipherMessage> saved = messageService.send(username, deviceId, messages);
        return new ObjectRestResponse<List<CipherMessage>>().data(saved).rel(true);
    }

    /**
     * 与某人的历史消息，按 id 倒序翻页（返回时已转为时间正序，且已按 clientMsgId 去重）。
     */
    @GetMapping("/history/{peer}")
    public ObjectRestResponse<List<CipherMessage>> history(@CurrentUser String username,
        @PathVariable String peer,
        @RequestParam(value = "beforeId", required = false) Long beforeId,
        @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return new ObjectRestResponse<List<CipherMessage>>()
            .data(messageService.history(username, peer, beforeId, limit))
            .rel(true);
    }

    /**
     * 本设备尚未投递的离线消息，客户端上线后调用补齐。
     */
    @GetMapping("/undelivered")
    public ObjectRestResponse<List<CipherMessage>> undelivered(@CurrentUser String username,
        @CurrentDevice String deviceId,
        @RequestParam(value = "limit", defaultValue = "200") int limit) {
        return new ObjectRestResponse<List<CipherMessage>>()
            .data(messageService.undelivered(username, deviceId, limit))
            .rel(true);
    }

    /**
     * 确认收到。批量语义：把本设备 beforeId 之前的消息全部标记为已投递。
     */
    @PostMapping("/delivered")
    public ObjectRestResponse<Integer> delivered(@CurrentUser String username,
        @CurrentDevice String deviceId, @RequestParam("beforeId") long beforeId) {
        int affected = messageService.markDeliveredTo(username, deviceId, beforeId);
        return new ObjectRestResponse<Integer>().data(affected).rel(true);
    }

    /**
     * 标记已读，并给对方发已读回执。已读状态作用于整条逻辑消息。
     */
    @PostMapping("/read")
    public ObjectRestResponse<Integer> read(@CurrentUser String username,
        @RequestBody ReadRequest request) {
        int affected = messageService.markRead(username, request.getPeer(), request.getBeforeId());
        return new ObjectRestResponse<Integer>().data(affected).rel(true);
    }

    /**
     * 未读数（按逻辑消息计数，多设备副本不重复统计）。
     */
    @GetMapping("/unread/{peer}")
    public ObjectRestResponse<Long> unread(@CurrentUser String username, @PathVariable String peer) {
        return new ObjectRestResponse<Long>().data(messageService.countUnread(username, peer)).rel(true);
    }

    /**
     * 撤回自己发送的消息。按 {@code clientMsgId} 撤回所有设备副本。
     */
    @PostMapping("/recall/{clientMsgId}")
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<CipherMessage> recall(@CurrentUser String username,
        @PathVariable String clientMsgId) {
        return new ObjectRestResponse<CipherMessage>()
            .data(messageService.recall(username, clientMsgId))
            .rel(true);
    }
}
