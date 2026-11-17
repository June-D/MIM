package com.junesoon.mim.crypto;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 演示辅助：为一个用户完整建立加密身份并保持在线。
 *
 * <p>真实客户端由用户手工操作，但演示时需要第二个账号"在线"才能展示双向聊天，
 * 所以用这个无界面的 bot 顶替。它做与真实客户端完全相同的事：
 * 注册设备、上传身份公钥、发布预密钥、建立 WS 连接、定期心跳。
 *
 * <p>用法：{@code java ... DemoBot <用户名> <口令>}
 */
public final class DemoBot {

    private static final String BASE = "http://127.0.0.1:8080";
    private static final String WS_URL = "ws://127.0.0.1:8080/websocket/test";

    public static void main(String[] args) throws Exception {
        String username = args.length > 0 ? args[0] : "bob";
        String password = args.length > 1 ? args[1] : "123456";
        String deviceId = username + "-bot";

        // 1. 登录
        String token = login(username, password);
        System.out.println("[bot] 登录成功");

        // 2. 注册设备
        post("/device", token, deviceId,
            "{\"deviceId\":\"" + deviceId + "\",\"deviceName\":\"演示机器人\"}");
        System.out.println("[bot] 设备已注册：" + deviceId);

        // 3. 身份密钥
        Path dir = Files.createTempDirectory("mim-bot-" + username);
        IdentityStore identityStore = new IdentityStore(dir);
        IdentityStore.Identity identity = identityStore.create(username, deviceId, password, 1);
        post("/key", token, deviceId,
            "{\"publicKey\":\"" + identity.getPublicKeyBase64() + "\",\"algorithm\":\"EC-P256\"}");
        System.out.println("[bot] 身份公钥已上传");

        // 4. 预密钥
        PreKeyStore preKeys = new PreKeyStore(dir.resolve("prekeys"));
        PreKeyStore.LocalSignedPreKey spk =
            preKeys.generateSignedPreKey(identity.getPrivateKey(), 1);
        preKeys.setSignedPreKey(spk);
        post("/prekey/signed", token, deviceId,
            "{\"preKeyId\":" + spk.getPreKeyId() + ",\"publicKey\":\"" + spk.getPublicKeyBase64()
                + "\",\"signature\":\"" + spk.getSignatureBase64() + "\"}");
        StringBuilder opks = new StringBuilder("[");
        java.util.List<PreKeyStore.LocalOneTimePreKey> oneTime = preKeys.generateOneTimePreKeys(20);
        preKeys.addOneTimePreKeys(oneTime);
        for (int i = 0; i < oneTime.size(); i++) {
            if (i > 0) {
                opks.append(',');
            }
            opks.append("{\"preKeyId\":").append(oneTime.get(i).getPreKeyId())
                .append(",\"publicKey\":\"").append(oneTime.get(i).getPublicKeyBase64()).append("\"}");
        }
        opks.append(']');
        post("/prekey/one-time", token, deviceId, opks.toString());
        System.out.println("[bot] 预密钥已发布（含 " + oneTime.size() + " 个一次性预密钥）");

        // 5. 建立 WS 保持在线，并自动回复收到的消息
        CountDownLatch stop = new CountDownLatch(1);
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("X-Device-Id", deviceId);
        com.junesoon.mim.websocket.WSClient ws =
            com.junesoon.mim.websocket.WSClient.createWithHeaders(URI.create(WS_URL), headers);
        ws.addListener(notify -> {
            if (notify.getType() != null
                && notify.getType() == com.junesoon.mim.websocket.WSClient.NOTIFY_NEW_MESSAGE) {
                System.out.println("[bot] 收到一条新消息（密文，未解密——本机器人为演示占位）");
            }
        });
        ws.connectBlocking();
        System.out.println("[bot] WS 已连接，保持在线中…（Ctrl+C 退出）");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ws.close();
            stop.countDown();
        }));
        stop.await(60, TimeUnit.MINUTES);
    }

    private static String login(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\""
            + Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)) + "\"}";
        String resp = send(BASE + "/login/token", null, null, body);
        int idx = resp.indexOf("\"data\":\"");
        if (idx < 0) {
            // 首次运行可能需要注册
            send(BASE + "/register/token", null, null, body);
            resp = send(BASE + "/login/token", null, null, body);
            idx = resp.indexOf("\"data\":\"");
            if (idx < 0) {
                throw new IllegalStateException("登录失败：" + resp);
            }
        }
        return resp.substring(idx + 8, resp.indexOf('"', idx + 8));
    }

    private static void post(String path, String token, String deviceId, String body)
        throws Exception {
        String resp = send(BASE + path, token, deviceId, body);
        if (!resp.contains("\"status\":200")) {
            System.out.println("[bot] 请求 " + path + " 返回：" + resp);
        }
    }

    private static String send(String url, String token, String deviceId, String body)
        throws Exception {
        java.net.HttpURLConnection conn =
            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (deviceId != null) {
            conn.setRequestProperty("X-Device-Id", deviceId);
        }
        conn.setDoOutput(true);
        try (java.io.OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        java.io.InputStream stream = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) {
            return "";
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private DemoBot() {
    }
}
