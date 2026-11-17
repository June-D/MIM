package com.junesoon.mim.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.entity.BaseNotify;
import com.junesoon.mim.entity.ChatUser;
import com.junesoon.mim.entity.OnlineNotify;
import com.junesoon.mim.service.ContactService;
import com.junesoon.mim.service.DeviceService;
import com.junesoon.mim.service.PresenceService;
import com.junesoon.mim.service.PushService;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket 长连接处理器（按设备）。
 *
 * <p>职责刻意收窄：这里<b>只负责连接生命周期与心跳</b>。
 * <ul>
 *   <li>消息的落库与转发走 HTTP {@code POST /message}，因为需要鉴权、幂等、
 *       事务和明确的失败回执；</li>
 *   <li>下行推送走 {@link PushService}，按设备路由，由 Redis Pub/Sub 保证跨实例可达；</li>
 *   <li>在线状态走 {@link PresenceService}，以 Redis 为准而非本机 Map。</li>
 * </ul>
 * 这样即使客户端伪造报文里的 fromUsername 也影响不到任何数据，
 * 因为发信人身份只从 JWT 中取。
 */
@Slf4j
@Component
public class WebSocketServer extends TextWebSocketHandler {

    private final SessionRegistry sessionRegistry;
    private final PresenceService presenceService;
    private final PushService pushService;
    private final ContactService contactService;
    private final DeviceService deviceService;
    private final ObjectMapper objectMapper;

    public WebSocketServer(SessionRegistry sessionRegistry, PresenceService presenceService,
        PushService pushService, ContactService contactService, DeviceService deviceService,
        ObjectMapper objectMapper) {
        this.sessionRegistry = sessionRegistry;
        this.presenceService = presenceService;
        this.pushService = pushService;
        this.contactService = contactService;
        this.deviceService = deviceService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String username = username(session);
        String deviceId = SessionRegistry.deviceOf(session);
        if (username == null) {
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        sessionRegistry.register(username, deviceId, session);
        presenceService.markOnline(username);
        // 刷新设备活跃时间，供"设备列表"排序与陈旧设备识别
        deviceService.touch(username, deviceId);

        // 连接成功只回给本设备
        pushService.pushToDevice(username, deviceId,
            BaseNotify.of(CommonConstants.NOTIFY_TYPE_CONNECT_SUCCESS, "连接服务器成功"));
        notifyContactsOnline(username);
        log.info("用户[{}] 设备[{}] 上线，本实例连接数 {}", username, deviceId,
            sessionRegistry.localConnectionCount());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String username = username(session);
        if (username == null) {
            return;
        }
        String deviceId = SessionRegistry.deviceOf(session);
        int remaining = sessionRegistry.unregister(username, session);
        // 同一用户的其它设备可能还连着；只有本实例最后一个连接断开才摘在线标记
        if (remaining == 0) {
            boolean offline = presenceService.markOffline(username);
            if (offline) {
                notifyContactsOffline(username);
            }
        }
        log.info("用户[{}] 设备[{}] 下线，本实例连接数 {}", username, deviceId,
            sessionRegistry.localConnectionCount());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String username = username(session);
        String deviceId = SessionRegistry.deviceOf(session);
        if (username == null) {
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        // 心跳：顺带给在线标记与设备活跃时间续期
        String payload = message.getPayload();
        if (payload != null && payload.contains("\"ping\"")) {
            presenceService.touch(username);
            deviceService.touch(username, deviceId);
            try {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                    BaseNotify.of(CommonConstants.NOTIFY_TYPE_PONG, System.currentTimeMillis()))));
            } catch (Exception e) {
                log.debug("心跳响应发送失败：{}", e.getMessage());
            }
            return;
        }
        // 上行只接受心跳。任何"发消息"的文本帧都不在这里处理，
        // 避免绕过 HTTP 层的参数校验与幂等控制。
        log.debug("忽略用户[{}] 设备[{}] 的非心跳上行帧", username, deviceId);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket 传输错误：{}", exception.getMessage());
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    private String username(WebSocketSession session) {
        Object value = session.getAttributes().get(CommonConstants.WEB_SOCKET_USER_ID);
        return value == null ? null : value.toString();
    }

    private void notifyContactsOnline(String username) {
        notifyContacts(username, true);
    }

    private void notifyContactsOffline(String username) {
        notifyContacts(username, false);
    }

    /**
     * 只通知「把该用户当作联系人」的人。在线状态是用户级信息（人是否在线），
     * 与设备无关，因此推给联系人的所有设备。
     */
    private void notifyContacts(String username, boolean online) {
        try {
            List<ChatUser> contacts = contactService.getFlatContacts(username);
            if (contacts == null || contacts.isEmpty()) {
                return;
            }
            List<String> targets = new ArrayList<>(contacts.size());
            for (ChatUser contact : contacts) {
                targets.add(contact.getUsername());
            }
            BaseNotify<OnlineNotify> notify = BaseNotify.of(
                online ? CommonConstants.NOTIFY_TYPE_USER_ONLINE : CommonConstants.NOTIFY_TYPE_USER_OFFLINE,
                new OnlineNotify(username, online));
            pushService.pushAll(targets, notify);
        } catch (Exception e) {
            log.warn("广播用户[{}]在线状态失败：{}", username, e.getMessage());
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (Exception ignored) {
            // 关闭失败无需处理
        }
    }
}
