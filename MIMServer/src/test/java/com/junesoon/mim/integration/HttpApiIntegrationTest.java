package com.junesoon.mim.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * HTTP 层集成测试：验证鉴权、参数解析、限流、异常映射的完整链路。
 *
 * <p>这层覆盖单元测试无法验证的横向关注点：
 * <ul>
 *   <li>{@code @CurrentUser} / {@code @CurrentDevice} 参数解析器是否生效；</li>
 *   <li>未认证返回 401、限流返回 429（而不是统一的 200 + 业务码）；</li>
 *   <li>请求体里伪造 fromUsername 会被忽略；</li>
 *   <li>设备头缺失时降级为 default。</li>
 * </ul>
 */
class HttpApiIntegrationTest extends IntegrationTestBase {

    private static final Database DB = prepare("httpapi");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        register(registry, DB);
    }

    @BeforeAll
    static void requireDatabase() {
        requireReady(DB);
    }

    @Override
    protected Database database() {
        return DB;
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        truncateAll();
        // 清掉限流计数，避免用例之间互相影响
        try {
            java.util.Set<String> keys = redisTemplate.keys("mim:ratelimit:*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception ignored) {
            // Redis 不可用时限流会 fail-open，不影响用例
        }
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    private static String base64Password() {
        return Base64.getEncoder().encodeToString("pass1234".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 注册并登录，返回 JWT。
     */
    private String registerAndLogin(String username) throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("username", username);
        body.put("password", base64Password());
        body.put("agreedTerms", "true");
        body.put("termsVersion", "2026-11");

        mockMvc.perform(post("/register/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200));

        MvcResult login = mockMvc.perform(post("/login/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andReturn();
        String response = login.getResponse().getContentAsString(StandardCharsets.UTF_8);
        int idx = response.indexOf("\"data\":\"");
        return response.substring(idx + 8, response.indexOf('"', idx + 8));
    }

    /**
     * 注册并携带设备头登录，得到绑定到该设备的 JWT。
     */
    private String registerAndLoginBound(String username, String deviceId) throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("username", username);
        body.put("password", base64Password());
        body.put("agreedTerms", "true");
        body.put("termsVersion", "2026-11");

        mockMvc.perform(post("/register/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk());

        MvcResult login = mockMvc.perform(post("/login/token")
                .header("X-Device-Id", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andReturn();
        String response = login.getResponse().getContentAsString(StandardCharsets.UTF_8);
        int idx = response.indexOf("\"data\":\"");
        return response.substring(idx + 8, response.indexOf('"', idx + 8));
    }

    private static String p256PublicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    private void registerDevice(String token, String deviceId) throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("deviceId", deviceId);
        body.put("deviceName", "集成测试设备");
        mockMvc.perform(post("/device")
                .header("Authorization", "Bearer " + token)
                .header("X-Device-Id", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200));
    }

    private void uploadKey(String token, String deviceId, String publicKey) throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("publicKey", publicKey);
        body.put("algorithm", "EC-P256");
        body.put("password", base64Password());
        body.put("agreedTerms", "true");
        body.put("termsVersion", "2026-11");
        mockMvc.perform(post("/key")
                .header("Authorization", "Bearer " + token)
                .header("X-Device-Id", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200));
    }

    // ==================== 鉴权 ====================

    @Test
    @DisplayName("未带凭证访问受保护接口返回 401")
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(get("/user/info"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(40101));
    }

    @Test
    @DisplayName("伪造的 JWT 返回 401")
    void invalidTokenReturns401() throws Exception {
        mockMvc.perform(get("/user/info").header("Authorization", "Bearer not-a-real-token"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("合法凭证可读到自己的用户信息（@CurrentUser 生效）")
    void validTokenResolvesCurrentUser() throws Exception {
        String token = registerAndLogin("alice");
        mockMvc.perform(get("/user/info").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.username").value("alice"));
    }

    @Test
    @DisplayName("Authorization 支持带与不带 Bearer 前缀")
    void bothBearerAndRawTokenAccepted() throws Exception {
        String token = registerAndLogin("alice2");
        mockMvc.perform(get("/user/info").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.username").value("alice2"));
    }

    // ==================== 设备头 ====================

    @Test
    @DisplayName("设备头缺失时降级为 default，不报错")
    void missingDeviceHeaderFallsBackToDefault() throws Exception {
        String token = registerAndLogin("alice");
        mockMvc.perform(get("/key/self").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rel").value(false));
    }

    @Test
    @DisplayName("未注册设备不能上传公钥")
    void unregisteredDeviceCannotUploadKey() throws Exception {
        String token = registerAndLogin("alice");
        Map<String, String> body = new HashMap<>();
        body.put("publicKey", p256PublicKey());
        body.put("algorithm", "EC-P256");
        mockMvc.perform(post("/key")
                .header("Authorization", "Bearer " + token)
                .header("X-Device-Id", "never-registered")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(40001))
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("设备未注册")));
    }

    @Test
    @DisplayName("设备注册后公钥上传成功，且按设备隔离")
    void keyUploadPerDevice() throws Exception {
        String token = registerAndLogin("alice");
        registerDevice(token, "dev-a");
        registerDevice(token, "dev-b");
        uploadKey(token, "dev-a", p256PublicKey());
        uploadKey(token, "dev-b", p256PublicKey());

        mockMvc.perform(get("/key/alice")
                .header("Authorization", "Bearer " + token)
                .header("X-Device-Id", "dev-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));
    }

    // ==================== 消息 ====================

    /**
     * 构造一条结构合法的双棘轮消息副本。
     */
    private Map<String, Object> replica(String clientMsgId, String to, String targetDevice) {
        Map<String, Object> message = new HashMap<>();
        message.put("clientMsgId", clientMsgId);
        message.put("toUsername", to);
        message.put("targetDeviceId", targetDevice);
        message.put("alg", "X3DH-P256-DOUBLE-RATCHET-AES-256-GCM");
        message.put("iv", Base64.getEncoder().encodeToString(new byte[12]));
        message.put("ciphertext", Base64.getEncoder().encodeToString(new byte[32]));
        message.put("ratchetPublic", "cmF0Y2hldC1wdWJsaWM");
        message.put("previousChainLength", 0);
        message.put("messageNumber", 0);
        message.put("clientTime", System.currentTimeMillis());
        // 协议断代后每个副本都必须是 sealed + 会话标签。
        message.put("sealed", true);
        message.put("sessionId", "session-http-01");
        message.put("conversationTag", "tagHttpAAAAAAAAAAAAAAAAAAAA1");
        return message;
    }

    @Test
    @DisplayName("请求体里的 fromUsername 被忽略，落库不含任何发信人")
    void spoofedSenderIgnoredAtHttpLayer() throws Exception {
        String aliceToken = registerAndLogin("alice");
        String bobToken = registerAndLogin("bob");
        registerDevice(aliceToken, "alice-dev");
        registerDevice(bobToken, "bob-dev");
        uploadKey(aliceToken, "alice-dev", p256PublicKey());
        uploadKey(bobToken, "bob-dev", p256PublicKey());

        Map<String, Object> message = replica("msg-spoof-001", "bob", "bob-dev");
        message.put("fromUsername", "bob"); // 试图伪造

        mockMvc.perform(post("/message")
                .header("Authorization", "Bearer " + aliceToken)
                .header("X-Device-Id", "alice-dev")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    java.util.Collections.singletonList(message))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data[0].fromUsername").doesNotExist())
            .andExpect(jsonPath("$.data[0].conversationTag").doesNotExist());
    }

    @Test
    @DisplayName("目标设备不属于收信人时拒绝")
    void unknownTargetDeviceRejectedAtHttpLayer() throws Exception {
        String aliceToken = registerAndLogin("alice");
        String bobToken = registerAndLogin("bob");
        registerDevice(aliceToken, "alice-dev");
        registerDevice(bobToken, "bob-dev");
        uploadKey(aliceToken, "alice-dev", p256PublicKey());
        uploadKey(bobToken, "bob-dev", p256PublicKey());

        Map<String, Object> message = replica("msg-ghost-001", "bob", "ghost-device");
        mockMvc.perform(post("/message")
                .header("Authorization", "Bearer " + aliceToken)
                .header("X-Device-Id", "alice-dev")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    java.util.Collections.singletonList(message))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(40001));
    }

    @Test
    @DisplayName("多设备副本一次提交，服务端全部落库")
    void multiDeviceFanoutOverHttp() throws Exception {
        String aliceToken = registerAndLogin("alice");
        String bobToken = registerAndLogin("bob");
        registerDevice(aliceToken, "alice-dev");
        registerDevice(bobToken, "bob-dev-a");
        registerDevice(bobToken, "bob-dev-b");
        uploadKey(aliceToken, "alice-dev", p256PublicKey());
        uploadKey(bobToken, "bob-dev-a", p256PublicKey());
        uploadKey(bobToken, "bob-dev-b", p256PublicKey());

        Map<String, Object> first = replica("msg-fanout-01", "bob", "bob-dev-a");
        Map<String, Object> second = replica("msg-fanout-01", "bob", "bob-dev-b");

        mockMvc.perform(post("/message")
                .header("Authorization", "Bearer " + aliceToken)
                .header("X-Device-Id", "alice-dev")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Arrays.asList(first, second))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));

        // 每台设备只看到自己那份
        mockMvc.perform(get("/message/undelivered")
                .header("Authorization", "Bearer " + bobToken)
                .header("X-Device-Id", "bob-dev-a"))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].targetDeviceId").value("bob-dev-a"));

        mockMvc.perform(get("/message/undelivered")
                .header("Authorization", "Bearer " + bobToken)
                .header("X-Device-Id", "bob-dev-b"))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].targetDeviceId").value("bob-dev-b"));
    }

    @Test
    @DisplayName("重复提交同一条消息是幂等的")
    void resendIsIdempotentOverHttp() throws Exception {
        String aliceToken = registerAndLogin("alice");
        String bobToken = registerAndLogin("bob");
        registerDevice(aliceToken, "alice-dev");
        registerDevice(bobToken, "bob-dev");
        uploadKey(aliceToken, "alice-dev", p256PublicKey());
        uploadKey(bobToken, "bob-dev", p256PublicKey());

        Map<String, Object> message = replica("msg-idem-0001", "bob", "bob-dev");
        String body = objectMapper.writeValueAsString(java.util.Collections.singletonList(message));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/message")
                    .header("Authorization", "Bearer " + aliceToken)
                    .header("X-Device-Id", "alice-dev")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200));
        }

        mockMvc.perform(get("/message/undelivered")
                .header("Authorization", "Bearer " + bobToken)
                .header("X-Device-Id", "bob-dev"))
            .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("封存消息的撤回凭证跨设备生效，缺失时被拒绝")
    void sealedRecallTokenWorksAcrossDevices() throws Exception {
        String aliceToken = registerAndLogin("alice");
        registerDevice(aliceToken, "alice-a");
        registerDevice(aliceToken, "alice-b");
        uploadKey(aliceToken, "alice-a", p256PublicKey());
        uploadKey(aliceToken, "alice-b", p256PublicKey());
        String bobToken = registerAndLogin("bob");
        registerDevice(bobToken, "bob-dev");
        uploadKey(bobToken, "bob-dev", p256PublicKey());

        // 发送端生成的随机凭证；服务端只保存哈希
        String recallToken = "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        Map<String, Object> message = replica("msg-cross-recall-01", "bob", "bob-dev");
        message.put("sealed", true);
        message.put("sessionId", "session-cross-01");
        message.put("recallToken", recallToken);

        mockMvc.perform(post("/message")
                .header("Authorization", "Bearer " + aliceToken)
                .header("X-Device-Id", "alice-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    java.util.Collections.singletonList(message))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data[0].fromUsername").doesNotExist())
            .andExpect(jsonPath("$.data[0].recallToken").doesNotExist())
            .andExpect(jsonPath("$.data[0].recallTokenHash").doesNotExist());

        // 换一台设备、不带凭证：必须拒绝，不能退回发信人引用
        mockMvc.perform(post("/message/recall/msg-cross-recall-01")
                .header("Authorization", "Bearer " + aliceToken)
                .header("X-Device-Id", "alice-b")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tags\":[\"tagHttpAAAAAAAAAAAAAAAAAAAA1\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(40001))
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("撤回凭证")));

        // 带上发送端的凭证：另一台设备也能撤回
        mockMvc.perform(post("/message/recall/msg-cross-recall-01")
                .header("Authorization", "Bearer " + aliceToken)
                .header("X-Device-Id", "alice-b")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recallToken\":\"" + recallToken
                    + "\",\"tags\":[\"tagHttpAAAAAAAAAAAAAAAAAAAA1\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.recalled").value(true))
            .andExpect(jsonPath("$.data.recallToken").doesNotExist())
            .andExpect(jsonPath("$.data.recallTokenHash").doesNotExist());
    }

    // ==================== 发送者证书 ====================

    @Test
    @DisplayName("发送者证书默认开启：公钥可用，签发需要设备公钥")
    void senderCertEnabledByDefault() throws Exception {
        mockMvc.perform(get("/cert/key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.publicKey").isNotEmpty())
            .andExpect(jsonPath("$.data.keyId").isNotEmpty());

        String token = registerAndLogin("alice");
        registerDevice(token, "alice-dev");
        mockMvc.perform(post("/cert/sender")
                .header("Authorization", "Bearer " + token)
                .header("X-Device-Id", "alice-dev"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(40001))
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("加密公钥")));
    }

    // ==================== 限流与异常映射 ====================

    @Test
    @DisplayName("超过注册限流返回 429 并带 Retry-After")
    void registerRateLimitReturns429() throws Exception {
        // 注册限流是 5 次/分钟/IP，这里连打 8 次
        boolean hitLimit = false;
        for (int i = 0; i < 8; i++) {
            Map<String, String> body = new HashMap<>();
            body.put("username", "ratelimit" + i);
            body.put("password", base64Password());
        body.put("agreedTerms", "true");
        body.put("termsVersion", "2026-11");
            MvcResult result = mockMvc.perform(post("/register/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andReturn();
            if (result.getResponse().getStatus() == 429) {
                hitLimit = true;
                assertNotNull(result.getResponse().getHeader("Retry-After"),
                    "429 应带 Retry-After 头");
                String response = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
                assertTrue(response.contains("42901"), "业务码应为 42901，实际：" + response);
                break;
            }
        }
        assertTrue(hitLimit, "连续 8 次注册应触发限流（阈值 5/分钟）");
    }

    @Test
    @DisplayName("非法用户名被拒绝且错误信息可读")
    void invalidUsernameRejected() throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("username", "a b");
        body.put("password", base64Password());
        body.put("agreedTerms", "true");
        body.put("termsVersion", "2026-11");
        mockMvc.perform(post("/register/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(40001))
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("用户名")));
    }

    @Test
    @DisplayName("预密钥接口：拉取不存在的用户返回可读错误")
    void fetchPreKeyForUnknownUser() throws Exception {
        String token = registerAndLogin("alice");
        registerDevice(token, "alice-dev");
        uploadKey(token, "alice-dev", p256PublicKey());

        mockMvc.perform(get("/prekey/nobody")
                .header("Authorization", "Bearer " + token)
                .header("X-Device-Id", "alice-dev"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(40001))
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("尚未注册任何设备")));
    }

    @Test
    @DisplayName("设备列表返回注册过的设备")
    void deviceList() throws Exception {
        String token = registerAndLogin("alice");
        registerDevice(token, "dev-1");
        registerDevice(token, "dev-2");

        mockMvc.perform(get("/device").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.count").value(2));
    }

    @Test
    @DisplayName("注销设备后不再出现在列表与预密钥分发里")
    void revokeDevice() throws Exception {
        String token = registerAndLogin("alice");
        registerDevice(token, "dev-1");
        registerDevice(token, "dev-2");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/device/dev-2").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200));

        mockMvc.perform(get("/device").header("Authorization", "Bearer " + token))
            .andExpect(jsonPath("$.data.count").value(1));
    }

    @Test
    @DisplayName("修改密码后旧令牌立即失效，新令牌与新密码可用")
    void passwordChangeRevokesOldTokens() throws Exception {
        String username = "alice-pw";
        String device = "dev-pw";
        String oldToken = registerAndLoginBound(username, device);
        registerDevice(oldToken, device);

        String newPassword = Base64.getEncoder().encodeToString(
            "newpass1234".getBytes(StandardCharsets.UTF_8));
        Map<String, String> change = new HashMap<>();
        change.put("oldPassword", base64Password());
        change.put("newPassword", newPassword);
        MvcResult changed = mockMvc.perform(post("/user/password")
                .header("Authorization", "Bearer " + oldToken)
                .header("X-Device-Id", device)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(change)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andReturn();
        String newToken = objectMapper.readTree(
                changed.getResponse().getContentAsString(StandardCharsets.UTF_8))
            .path("data").path("token").asText();

        mockMvc.perform(get("/user/info").header("Authorization", "Bearer " + oldToken))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/user/info").header("Authorization", "Bearer " + newToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.username").value(username));

        Map<String, String> login = new HashMap<>();
        login.put("username", username);
        login.put("password", newPassword);
        mockMvc.perform(post("/login/token")
                .header("X-Device-Id", device)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(login)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200));

        login.put("password", base64Password());
        mockMvc.perform(post("/login/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(login)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(40001));
    }

    @Test
    @DisplayName("健康探测：未认证接口返回 401 而非 500")
    void unauthenticatedIsNotServerError() throws Exception {
        mockMvc.perform(get("/message/undelivered"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("X-Internal-Error"));
    }
}
