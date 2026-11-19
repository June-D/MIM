package com.junesoon.mim.crypto;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 发送者令牌与匿名提交端到端回归（Phase 2c）。
 *
 * <p>覆盖：致盲签名跨模块一致性、令牌一次性（重放被拒）、
 * 匿名消息落库无发送者、收信端按标签查历史。
 *
 * <p>服务端默认关闭这两个功能。未启用时打印 SKIP 并以 0 退出。
 * 启用方式：{@code MIM_SENDER_TOKEN_ENABLED=true MIM_ANONYMOUS_SEND_ENABLED=true}。
 *
 * [INPUT]: 依赖运行中的服务端与客户端 BlindRsaToken
 * [OUTPUT]: 通过 7 项检查或明确跳过
 * [POS]: 2c 的服务端契约回归
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
public final class SenderTokenE2eTest {

    private static final String BASE = System.getenv().getOrDefault("MIM_BASE",
        "http://127.0.0.1:8080");
    private static final String PASSWORD = "pass1234";

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        String suffix = String.valueOf(System.currentTimeMillis() % 100000);
        String alice = "tokenAlice" + suffix;
        String bob = "tokenBob" + suffix;
        String aliceDevice = "token-alice-dev";
        String bobDevice = "token-bob-dev";

        String keyResponse = httpGet(BASE + "/token/key", null, null);
        if (keyResponse.contains("\"status\":40001")) {
            System.out.println("SKIP: 发送者令牌未启用（MIM_SENDER_TOKEN_ENABLED=false）");
            return;
        }

        register(alice);
        register(bob);
        String aliceToken = login(alice, aliceDevice);
        String bobToken = login(bob, bobDevice);
        registerDevice(aliceToken, aliceDevice);
        registerDevice(bobToken, bobDevice);

        // ---- 致盲签发：客户端不可见 nonce，服务端也看不到 ----
        String publicKey = str(keyResponse, "\"publicKey\":\"");
        byte[] nonce = BlindRsaToken.newNonce();
        BlindRsaToken.Blinded blinded = BlindRsaToken.blind(nonce, publicKey);
        String signResponse = httpPost(BASE + "/token/blind", aliceToken, aliceDevice,
            "{\"blinded\":\"" + blinded.getBlindedValue() + "\"}");
        requireOk(signResponse, "致盲签名");
        String token = BlindRsaToken.unblind(blinded, str(signResponse, "\"data\":\""));
        check("盲签名跨模块可验证（客户端去盲 + 自检）",
            BlindRsaToken.verify(token, publicKey));

        // ---- 匿名提交：只带令牌，不带 JWT ----
        String conversationTag = "tagTokenAAAAAAAAAAAAAAAAAA1";
        String clientMsgId = "anon-msg-" + suffix;
        String replica = "{"
            + "\"clientMsgId\":\"" + clientMsgId + "\","
            + "\"toUsername\":\"" + bob + "\","
            + "\"targetDeviceId\":\"" + bobDevice + "\","
            + "\"alg\":\"" + RatchetService.ALGORITHM + "\","
            + "\"iv\":\"" + Base64.getEncoder().encodeToString(new byte[12]) + "\","
            + "\"ciphertext\":\"" + Base64.getEncoder().encodeToString(new byte[32]) + "\","
            + "\"ratchetPublic\":\"cmF0Y2hldC1wdWJsaWM\","
            + "\"previousChainLength\":0,\"messageNumber\":0,"
            + "\"clientTime\":" + System.currentTimeMillis() + ","
            + "\"conversationTag\":\"" + conversationTag + "\","
            + "\"sealed\":true,\"sessionId\":\"session-anon-01\"}";

        HttpURLConnection connection = open(BASE + "/message/anonymous", "POST", null, null);
        connection.setRequestProperty("X-Sender-Token", token);
        connection.setDoOutput(true);
        connection.getOutputStream().write(("[" + replica + "]")
            .getBytes(StandardCharsets.UTF_8));
        String sendResponse = read(connection);
        requireOk(sendResponse, "匿名提交");
        check("匿名消息落库无明文发送者", sendResponse.contains("\"fromUsername\":null"));
        check("匿名消息不落盲引用", !sendResponse.contains("fromRef"));
        check("匿名提交不回传会话标签", !sendResponse.contains("conversationTag"));

        // ---- 令牌一次性：重放必须被拒 ----
        HttpURLConnection replay = open(BASE + "/message/anonymous", "POST", null, null);
        replay.setRequestProperty("X-Sender-Token", token);
        replay.setDoOutput(true);
        replay.getOutputStream().write(("[" + replica + "]")
            .getBytes(StandardCharsets.UTF_8));
        String replayResponse = read(replay);
        check("同一令牌重放被拒", replayResponse.contains("\"status\":40001")
            && replayResponse.contains("已使用"));

        // ---- 收信端：按标签取回这条匿名消息 ----
        String history = httpGet(BASE + "/message/history/" + alice + "?limit=50&tags="
            + conversationTag, bobToken, bobDevice);
        check("收信端可按标签查到匿名消息", history.contains(clientMsgId));
        check("匿名消息的历史响应不含发送者与标签",
            !history.contains("\"fromUsername\":\"")
                && !history.contains("\"conversationTag\""));

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name);
        }
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireOk(String response, String action) {
        if (!response.contains("\"status\":200")) {
            throw new IllegalStateException(action + "失败：" + response);
        }
    }

    private static void register(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\""
            + base64(PASSWORD) + "\",\"agreedTerms\":true,\"termsVersion\":\"2026-11\"}";
        requireOk(httpPost(BASE + "/register/token", null, null, body), "注册");
    }

    private static String login(String username, String deviceId) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\""
            + base64(PASSWORD) + "\"}";
        return str(httpPost(BASE + "/login/token", null, deviceId, body), "\"data\":\"");
    }

    private static void registerDevice(String token, String deviceId) throws Exception {
        String body = "{\"deviceId\":\"" + deviceId + "\",\"deviceName\":\"" + deviceId + "\"}";
        requireOk(httpPost(BASE + "/device", token, deviceId, body), "设备注册");
    }

    private static String httpGet(String endpoint, String token, String deviceId) throws Exception {
        return read(open(endpoint, "GET", token, deviceId));
    }

    private static String httpPost(String endpoint, String token, String deviceId, String body)
        throws Exception {
        HttpURLConnection connection = open(endpoint, "POST", token, deviceId);
        if (body != null) {
            connection.setDoOutput(true);
            connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(connection);
    }

    private static HttpURLConnection open(String endpoint, String method, String token,
        String deviceId) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Content-Type", "application/json");
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (deviceId != null) {
            connection.setRequestProperty("X-Device-Id", deviceId);
        }
        return connection;
    }

    private static String read(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream()
            : connection.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (stream != null) {
            byte[] buffer = new byte[4096];
            int readCount;
            while ((readCount = stream.read(buffer)) >= 0) {
                out.write(buffer, 0, readCount);
            }
            stream.close();
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String str(String json, String prefix) {
        int index = json.indexOf(prefix);
        if (index < 0) {
            throw new IllegalStateException("缺少字段 " + prefix + "：" + json);
        }
        int start = index + prefix.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
