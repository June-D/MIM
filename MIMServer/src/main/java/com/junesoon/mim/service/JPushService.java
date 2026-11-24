package com.junesoon.mim.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 极光推送（JPush）通道：App 在后台/被杀死时靠它叫醒。
 *
 * <p>为什么是「叫醒」而不是「投递内容」：消息是端到端加密的，服务端只持有密文。
 * 所以推送正文只放一句「你有新消息」，{@code extras} 里带会话对象与类型，
 * 客户端被唤醒后仍然走 {@code /message/undelivered} 拉取并解密。
 *
 * <p>没配置 appKey/secret 时全部方法静默跳过——本地开发和没买推送服务的部署
 * 不该因为缺配置而报错。
 */
@Slf4j
@Service
public class JPushService {

    private static final String ENDPOINT = "https://api.jpush.cn/v3/push";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${mim.push.jpush.app-key:}")
    private String appKey;

    @Value("${mim.push.jpush.master-secret:}")
    private String masterSecret;

    /** 生产环境才会投到 APNs production，开发期用 sandbox（默认）。 */
    @Value("${mim.push.jpush.apns-production:false}")
    private boolean apnsProduction;

    @Value("${mim.push.jpush.enabled:true}")
    private boolean enabled;

    /**
     * 推送是「尽力而为」：不能阻塞消息落库，也不该让发送方因为推送失败而重试。
     * 单线程足够——推送只是通知，跑不快也不会积压出问题。
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jpush-notifier");
        thread.setDaemon(true);
        return thread;
    });

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public JPushService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return enabled && StringUtils.hasText(appKey) && StringUtils.hasText(masterSecret);
    }

    /**
     * 异步通知某台设备有新消息。
     *
     * <p>把 {@code conversationTag} 一起带过去：客户端用它在本地的会话索引里反查
     * 出「是跟谁在聊」，从而直接打开对应会话。标签是双方共享的随机串，
     * **不含用户名**——服务端与极光都看不出会话对象是谁。
     *
     * @param tag 该消息所属会话的标签（可为 null）
     */
    public void notifyNewMessage(String username, String deviceId, String tag, int unread) {
        if (!isConfigured()) {
            return;
        }
        executor.submit(() -> {
            try {
                List<String> ids = jdbcTemplate.queryForList(
                    "select push_registration from t_device "
                        + "where username = ? and device_id = ? and push_registration is not null",
                    String.class, username, deviceId);
                if (ids.isEmpty() || !StringUtils.hasText(ids.get(0))) {
                    return; // 这台设备没上报极光 registrationId
                }
                Map<String, Object> extras = new LinkedHashMap<>();
                extras.put("type", "message");
                if (StringUtils.hasText(tag)) {
                    extras.put("tag", tag);
                }
                String body = unread > 1 ? "你有 " + unread + " 条新消息" : "你有一条新消息";
                send(List.of(ids.get(0)), "MIM", body, extras);
            } catch (Exception e) {
                log.warn("极光推送失败（{} / {}）：{}", username, deviceId, e.getMessage());
            }
        });
    }

    /**
     * 调 {@code POST /v3/push}。
     *
     * <p>失败只记日志：推送是附加能力，不该影响主流程。
     */
    private void send(List<String> registrationIds, String title, String body,
        Map<String, Object> extras) throws Exception {
        Map<String, Object> ios = new LinkedHashMap<>();
        ios.put("alert", body);
        ios.put("sound", "default");
        ios.put("badge", "+1");
        ios.put("extras", extras);

        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("ios", ios);

        Map<String, Object> audience = new LinkedHashMap<>();
        audience.put("registration_id", new ArrayList<>(registrationIds));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("apns_production", apnsProduction);
        // 消息过一段时间就失去了「提醒」的意义，别在用户睡觉时炸他。
        options.put("time_to_live", 600);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("platform", List.of("ios"));
        payload.put("audience", audience);
        payload.put("notification", notification);
        payload.put("options", options);

        String credentials = appKey + ":" + masterSecret;
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
            .timeout(Duration.ofSeconds(8))
            .header("Authorization",
                "Basic " + Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8)))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            log.warn("极光推送被拒绝：HTTP {} {} extras={}",
                response.statusCode(), response.body(), extras);
        } else {
            log.info("极光推送已提交：{} extras={}", response.body(), extras);
        }
    }
}
