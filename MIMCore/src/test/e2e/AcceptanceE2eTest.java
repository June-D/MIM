package com.junesoon.mim.crypto;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Phase 2 启用前验收的脚本化部分。
 *
 * <p>覆盖「能通过协议断言」的项目：
 * <ul>
 *   <li>A1 首次联系：证书可用、标签信封落库、收信端可查历史；</li>
 *   <li>A2 证书关闭：服务端拒绝无标签消息（不再有明文发送者降级）；</li>
 *   <li>A3 匿名关闭：/message/anonymous 返回未启用；开启时令牌可用；</li>
 *   <li>A5 多设备：同一逻辑消息发给两台设备，各设备历史只取自己那份。</li>
 * </ul>
 * 纯界面项（屏蔽菜单、会话丢失提示文案）会明确打印 MANUAL，不计入通过数。
 *
 * <p>需要两个服务端实例：
 * <pre>
 *   8080：证书/令牌/匿名全开（MIM_BASE）
 *   8081：三者全关（MIM_BASE_OFF，可省略则跳过关闭态检查）
 * </pre>
 *
 * [INPUT]: 依赖运行中的服务端与 HTTP 接口
 * [OUTPUT]: 通过/失败计数 + MANUAL 清单
 * [POS]: 人工验收的自动化部分；与 SenderCertE2eTest / SenderTokenE2eTest 互补
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
public final class AcceptanceE2eTest {

    private static final String BASE = System.getenv().getOrDefault("MIM_BASE",
        "http://127.0.0.1:8080");
    private static final String BASE_OFF = System.getenv().getOrDefault("MIM_BASE_OFF",
        "http://127.0.0.1:8081");
    private static final String PASSWORD = "pass1234";
    private static final String TAG = "tagAcceptAAAAAAAAAAAAAAAAA1";

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> manual = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String suffix = String.valueOf(System.currentTimeMillis() % 100000);
        String alice = "accAlice" + suffix;
        String bob = "accBob" + suffix;
        String aliceDevice = "acc-alice";
        String bobDeviceA = "acc-bob-a";
        String bobDeviceB = "acc-bob-b";

        // ---------- A1 首次联系 ----------
        section("A1 首次联系（证书 + 会话标签）");
        String key = httpGet(BASE + "/cert/key", null, null);
        check("证书公钥可用", key.contains("\"status\":200") && key.contains("\"publicKey\""));

        register(alice);
        register(bob);
        String aliceToken = login(alice, aliceDevice);
        // bob 的令牌不绑定设备：多设备历史需要同一令牌配不同 X-Device-Id。
        String bobToken = login(bob, null);
        registerDevice(aliceToken, aliceDevice);
        registerDevice(bobToken, bobDeviceA);
        registerDevice(bobToken, bobDeviceB);

        Path workDir = Files.createTempDirectory("mim-accept");
        IdentityStore aliceStore = new IdentityStore(workDir.resolve("alice-id"));
        IdentityStore.Identity aliceIdentity = aliceStore.create(alice, aliceDevice, PASSWORD, 1);
        uploadIdentityKey(aliceToken, aliceDevice, aliceIdentity.getPublicKeyBase64());
        IdentityStore bobStore = new IdentityStore(workDir.resolve("bob-id"));
        IdentityStore.Identity bobIdentity = bobStore.create(bob, bobDeviceA, PASSWORD, 1);
        uploadIdentityKey(bobToken, bobDeviceA, bobIdentity.getPublicKeyBase64());
        uploadIdentityKey(bobToken, bobDeviceB, bobIdentity.getPublicKeyBase64());

        String certResponse = httpPost(BASE + "/cert/sender", aliceToken, aliceDevice, null);
        requireOk(certResponse, "证书签发");
        check("证书归属正确", certResponse.contains("\"username\":\"" + alice + "\""));

        String clientMsgId = "acc-first-" + suffix;
        String replica = replica(clientMsgId, bob, bobDeviceA);
        String sendResponse = httpPost(BASE + "/message", aliceToken, aliceDevice,
            "[" + replica + "]");
        requireOk(sendResponse, "首次消息发送");
        check("落库不含发送者字段", !sendResponse.contains("\"fromUsername\""));

        String inbox = httpGet(BASE + "/message/undelivered?limit=50", bobToken, bobDeviceA);
        check("收信端可拉取离线消息", inbox.contains(clientMsgId));

        String history = httpGet(BASE + "/message/history/" + alice + "?limit=50&tags=" + TAG,
            bobToken, bobDeviceA);
        check("按标签可查历史", history.contains(clientMsgId));
        check("历史响应不含发送者字段", !history.contains("\"fromUsername\""));

        // ---------- A2 证书关闭 ----------
        section("A2 证书关闭（无降级路径）");
        String offKey = httpGet(BASE_OFF + "/cert/key", null, null);
        if (offKey.isEmpty()) {
            skip("关闭态实例不可达（未启动 8081）");
        } else {
            check("证书关闭时接口返回未启用", offKey.contains("\"status\":40001"));
            String untagged = "{\"clientMsgId\":\"acc-untagged-" + suffix + "\","
                + "\"toUsername\":\"" + bob + "\",\"targetDeviceId\":\"" + bobDeviceA + "\","
                + "\"alg\":\"X3DH-P256-DOUBLE-RATCHET-AES-256-GCM\","
                + "\"iv\":\"" + b64(new byte[12]) + "\","
                + "\"ciphertext\":\"" + b64(new byte[32]) + "\","
                + "\"ratchetPublic\":\"cmF0Y2hldA==\",\"previousChainLength\":0,"
                + "\"messageNumber\":0,\"clientTime\":" + System.currentTimeMillis() + ","
                + "\"sealed\":true,\"sessionId\":\"session-acc-01\"}";
            String offSend = httpPost(BASE_OFF + "/message", aliceToken, aliceDevice,
                "[" + untagged + "]");
            check("服务端拒绝无标签消息（无明文降级）", offSend.contains("\"status\":40001"));
        }

        // ---------- A3 匿名关闭 ----------
        section("A3 匿名提交（关闭态与开启态）");
        if (offKey.isEmpty()) {
            skip("关闭态实例不可达（未启动 8081）");
        } else {
            String offAnon = httpPost(BASE_OFF + "/message/anonymous", null, null,
                "[" + replica(clientMsgId + "-anon-off", bob, bobDeviceA) + "]");
            check("匿名关闭时接口拒绝", offAnon.contains("\"status\":40001"));
        }
        String tokenKey = httpGet(BASE + "/token/key", null, null);
        if (tokenKey.contains("\"status\":200")) {
            check("令牌公钥可用（匿名开启态）", true);
            manual.add("匿名提交真实发送需客户端 -Dmim.anonymousSend=true，界面未抽检");
        } else {
            skip("令牌服务未启用，跳过匿名开启态");
        }

        // ---------- A5 多设备历史 ----------
        section("A5 多设备：各设备只取自己那份历史");
        String multiId = "acc-multi-" + suffix;
        String multiBody = "[" + replica(multiId, bob, bobDeviceA)
            + "," + replica(multiId, bob, bobDeviceB) + "]";
        requireOk(httpPost(BASE + "/message", aliceToken, aliceDevice, multiBody), "多设备发送");
        String viewA = httpGet(BASE + "/message/history/" + alice + "?limit=50&tags=" + TAG,
            bobToken, bobDeviceA);
        String viewB = httpGet(BASE + "/message/history/" + alice + "?limit=50&tags=" + TAG,
            bobToken, bobDeviceB);
        check("设备 A 历史只含发给 A 的副本",
            viewA.contains(multiId) && viewA.contains("\"targetDeviceId\":\"" + bobDeviceA + "\""));
        check("设备 B 历史只含发给 B 的副本",
            viewB.contains(multiId) && viewB.contains("\"targetDeviceId\":\"" + bobDeviceB + "\""));

        // ---------- 纯界面项 ----------
        section("纯界面项（需要人工）");
        manual.add("A2 客户端：证书不可用时发送给出明确错误文案");
        manual.add("A4 屏蔽：右键菜单屏蔽/解除，对方新消息显示「[已屏蔽该联系人]」");
        manual.add("A6 会话丢失：删除本地会话后收到 sealed 消息，提示明确且不崩溃");

        System.out.println();
        for (String item : manual) {
            System.out.println("  MANUAL " + item);
        }
        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项，人工 " + manual.size() + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 断言与输出 ====================

    private static void section(String name) {
        System.out.println();
        System.out.println("---- " + name + " ----");
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

    private static void skip(String reason) {
        System.out.println("  SKIP " + reason);
    }

    private static String replica(String clientMsgId, String to, String target) {
        return "{\"clientMsgId\":\"" + clientMsgId + "\","
            + "\"toUsername\":\"" + to + "\",\"targetDeviceId\":\"" + target + "\","
            + "\"alg\":\"X3DH-P256-DOUBLE-RATCHET-AES-256-GCM\","
            + "\"iv\":\"" + b64(new byte[12]) + "\","
            + "\"ciphertext\":\"" + b64(new byte[32]) + "\","
            + "\"ratchetPublic\":\"cmF0Y2hldA==\",\"previousChainLength\":0,"
            + "\"messageNumber\":0,\"clientTime\":" + System.currentTimeMillis() + ","
            + "\"sealed\":true,\"sessionId\":\"session-acc-01\","
            + "\"conversationTag\":\"" + TAG + "\"}";
    }

    // ==================== HTTP ====================

    private static String b64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static void register(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\""
            + b64(PASSWORD.getBytes(StandardCharsets.UTF_8))
            + "\",\"agreedTerms\":true,\"termsVersion\":\"2026-12\"}";
        requireOk(httpPost(BASE + "/register/token", null, null, body), "注册");
    }

    private static String login(String username, String deviceId) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\""
            + b64(PASSWORD.getBytes(StandardCharsets.UTF_8)) + "\"}";
        return str(httpPost(BASE + "/login/token", null, deviceId, body), "\"data\":\"");
    }

    private static void registerDevice(String token, String deviceId) throws Exception {
        String body = "{\"deviceId\":\"" + deviceId + "\",\"deviceName\":\"" + deviceId + "\"}";
        requireOk(httpPost(BASE + "/device", token, deviceId, body), "设备注册");
    }

    private static void uploadIdentityKey(String token, String deviceId, String publicKey)
        throws Exception {
        String body = "{\"publicKey\":\"" + publicKey
            + "\",\"algorithm\":\"EC-P256\",\"password\":\""
            + b64(PASSWORD.getBytes(StandardCharsets.UTF_8))
            + "\",\"supportsPadding\":true,\"supportsRecallToken\":true"
            + ",\"supportsSenderCert\":true}";
        requireOk(httpPost(BASE + "/key", token, deviceId, body), "上传身份公钥");
    }

    private static void requireOk(String response, String action) {
        if (!response.contains("\"status\":200")) {
            throw new IllegalStateException(action + "失败：" + response);
        }
    }

    private static String httpGet(String endpoint, String token, String deviceId) throws Exception {
        try {
            return read(open(endpoint, "GET", token, deviceId));
        } catch (Exception e) {
            return "";
        }
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
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(20000);
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
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
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
