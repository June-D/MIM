package com.junesoon.mim.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.junesoon.mim.components.ApplicatonStore;
import com.junesoon.mim.model.BaseNotify;
import com.junesoon.mim.model.CipherMessage;
import com.junesoon.mim.model.OnlineNotify;
import com.junesoon.mim.service.MessageService;
import com.junesoon.mim.utils.JacksonUtil;
import com.junesoon.mim.utils.TlsSupport;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.handshake.ServerHandshake;

/**
 * WebSocket 客户端。
 *
 * <p>只负责接收服务端下行通知（新消息、在线状态、已读回执），
 * 发送消息一律走 HTTP，以便拿到明确的成功/失败与幂等保证。
 */
@Slf4j
public class WSClient extends WebSocketClient {

    /**
     * 通知类型，与服务端 CommonConstants 保持一致。
     */
    public static final int NOTIFY_CONNECT_SUCCESS = 100000;
    public static final int NOTIFY_USER_ONLINE = 100001;
    public static final int NOTIFY_USER_OFFLINE = 100002;
    public static final int NOTIFY_NEW_MESSAGE = 100010;
    public static final int NOTIFY_READ_RECEIPT = 100012;
    public static final int NOTIFY_MESSAGE_RECALLED = 100014;
    public static final int NOTIFY_FRIEND_REQUEST = 100016;
    public static final int NOTIFY_FRIEND_ACCEPTED = 100017;
    public static final int NOTIFY_FRIEND_REJECTED = 100018;
    public static final int NOTIFY_CONVERSATION_CLEARED = 100019;
    public static final int NOTIFY_PONG = 100021;
    /** 其他设备请本机把已解开的历史转过去 */
    public static final int NOTIFY_HISTORY_SYNC = 100022;
    public static final int NOTIFY_SECURITY_ALERT = 100023;

    private static volatile WSClient instance;

    private final List<Consumer<BaseNotify<?>>> listeners = new CopyOnWriteArrayList<>();

    public static synchronized WSClient getInstance() {
        if (instance == null) {
            instance = new WSClient(URI.create(defaultUrl()));
        }
        return instance;
    }

    /**
     * 断开并丢弃单例，下次 getInstance 会按当前配置重建（重连/切换账号时需要）。
     *
     * <p>关闭动作放到独立线程：Java-WebSocket 的 {@code close()} 会等待连接线程收尾，
     * 在 JavaFX 线程上调用会卡住界面（登出时表现为"点了没反应"）。
     */
    public static synchronized void discard() {
        WSClient existing = instance;
        instance = null;
        if (existing == null) {
            return;
        }
        Thread closer = new Thread(() -> {
            try {
                existing.close();
            } catch (Exception e) {
                log.debug("关闭旧连接失败：{}", e.getMessage());
            }
        }, "ws-closer");
        closer.setDaemon(true);
        closer.start();
    }

    private static String defaultUrl() {
        String base = System.getProperty("mim.ws.url", "");
        if (base != null && !base.trim().isEmpty()) {
            return base.trim();
        }
        return "ws://127.0.0.1:8080/websocket/test";
    }

    /**
     * 按指定地址创建客户端实例，供联调脚本与多账号测试使用。
     * 生产路径统一走 {@link #getInstance()}。
     */
    public static WSClient create(URI serverUri) {
        return new WSClient(serverUri);
    }

    /**
     * 创建并预置握手头。用于需要模拟多设备/多账号的联调场景，
     * 避免修改全局单例的 header 状态。
     */
    public static WSClient createWithHeaders(URI serverUri, java.util.Map<String, String> headers) {
        WSClient client = new WSClient(serverUri);
        if (headers != null) {
            for (java.util.Map.Entry<String, String> entry : headers.entrySet()) {
                client.addHeader(entry.getKey(), entry.getValue());
            }
        }
        return client;
    }

    private WSClient(URI serverUri) {
        super(serverUri);
        // wss 需要显式注入 SSL 工厂；ws 下设置也不会参与握手
        if (serverUri != null && "wss".equalsIgnoreCase(serverUri.getScheme())) {
            setSocketFactory(TlsSupport.socketFactory());
        }
    }
    public void addListener(Consumer<BaseNotify<?>> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<BaseNotify<?>> listener) {
        listeners.remove(listener);
    }

    /**
     * 带上当前登录凭证与设备标识建立连接。
     *
     * <p>设备标识必须带上：服务端按设备路由推送，缺失会降级为 default，
     * 多设备场景下就收不到属于自己的密文。
     */
    public void connectWithToken() {
        String token = ApplicatonStore.getToken();
        if (token != null && !token.trim().isEmpty()) {
            addHeader("Authorization", "Bearer " + token);
        }
        String deviceId = ApplicatonStore.getDeviceId();
        if (deviceId != null && !deviceId.trim().isEmpty()) {
            addHeader("X-Device-Id", deviceId);
        }
        try {
            if (getReadyState() == ReadyState.NOT_YET_CONNECTED) {
                connectBlocking();
            } else if (getReadyState() == ReadyState.CLOSED) {
                reconnectBlocking();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("WebSocket 连接被中断");
        } catch (Exception e) {
            log.warn("WebSocket 连接失败：{}", e.getMessage());
        }
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        log.info("[websocket] 连接成功");
        ApplicatonStore.setOnline(true);
    }

    @Override
    public void onMessage(String message) {
        BaseNotify<Object> notify = JacksonUtil.from(message, new TypeReference<BaseNotify<Object>>() {
        });
        if (notify == null || notify.getType() == null) {
            return;
        }
        switch (notify.getType()) {
            case NOTIFY_CONNECT_SUCCESS:
                log.info("[websocket] {}", notify.getData());
                break;
            case NOTIFY_USER_ONLINE:
            case NOTIFY_USER_OFFLINE:
                applyOnlineStatus(message, notify.getType() == NOTIFY_USER_ONLINE);
                break;
            case NOTIFY_NEW_MESSAGE:
                dispatchNewMessage(message);
                break;
            case NOTIFY_MESSAGE_RECALLED:
                dispatchToListeners(notify);
                break;
            case NOTIFY_READ_RECEIPT:
                dispatchToListeners(notify);
                break;
            case NOTIFY_FRIEND_REQUEST:
            case NOTIFY_FRIEND_ACCEPTED:
            case NOTIFY_FRIEND_REJECTED:
            case NOTIFY_CONVERSATION_CLEARED:
            case NOTIFY_HISTORY_SYNC:
            case NOTIFY_SECURITY_ALERT:
                dispatchToListeners(notify);
                break;
            case NOTIFY_PONG:
                break;
            default:
                log.debug("未处理的通知类型 {}", notify.getType());
        }
    }

    private void applyOnlineStatus(String message, boolean online) {
        BaseNotify<OnlineNotify> parsed = JacksonUtil.from(message,
            new TypeReference<BaseNotify<OnlineNotify>>() {
            });
        if (parsed == null || parsed.getData() == null) {
            return;
        }
        String username = parsed.getData().getUsername();
        ApplicatonStore.getChatContacts().forEach(group -> group.getUsers().forEach(user -> {
            if (username != null && username.equals(user.getUsername())) {
                user.setOnline(online);
            }
        }));
        ApplicatonStore.chatContactsRefresh = true;
    }

    private void dispatchNewMessage(String message) {
        BaseNotify<CipherMessage> parsed = JacksonUtil.from(message,
            new TypeReference<BaseNotify<CipherMessage>>() {
            });
        if (parsed == null || parsed.getData() == null) {
            return;
        }
        CipherMessage payload = parsed.getData();
        String self = ApplicatonStore.getUsername();
        String myDevice = ApplicatonStore.getDeviceId();

        // 防御性过滤：服务端已按设备路由，这里再校验一次。
        // 收到的密文要么是发给"我这台设备"的，要么是自己其它设备发出的同步副本。
        if (self != null && myDevice != null) {
            // 服务端只把密文推给收信人的目标设备，这里再做一次防御性过滤。
            boolean forMyDevice = self.equals(payload.getToUsername())
                && myDevice.equals(payload.getTargetDeviceId());
            if (!forMyDevice) {
                log.debug("忽略不属于本设备的密文 target={}", payload.getTargetDeviceId());
                return;
            }
        }

        // 先解密一遍以便推进棘轮状态，再交给监听器
        MessageService.getInstance().decrypt(payload);
        dispatchToListeners(parsed);
    }

    private void dispatchToListeners(BaseNotify<?> notify) {
        for (Consumer<BaseNotify<?>> listener : listeners) {
            try {
                listener.accept(notify);
            } catch (Exception e) {
                log.warn("消息监听器异常：{}", e.getMessage());
            }
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info("[websocket] 连接关闭 code={} reason={}", code, reason);
        ApplicatonStore.setOnline(false);
    }

    @Override
    public void onError(Exception ex) {
        log.warn("[websocket] 连接异常：{}", ex.getMessage());
        ApplicatonStore.setOnline(false);
    }
}
