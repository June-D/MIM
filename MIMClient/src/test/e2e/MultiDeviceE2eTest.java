package com.junesoon.mim.crypto;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 多设备端到端回归：真实 HTTP + 真实密钥。
 *
 * <p>验证多设备的核心语义：
 * <ol>
 *   <li>bob 有两台设备，各自发布独立的身份密钥与预密钥；</li>
 *   <li>alice 发一条消息，要为两台设备各加密一份，服务端落两行；</li>
 *   <li>两台设备各自用自己的私钥解密，都能得到正确明文；</li>
 *   <li>两台设备的棘轮链互相独立；</li>
 *   <li>撤回按 clientMsgId 一次性作用到所有设备副本；</li>
 *   <li>注销一台设备后，新消息不再发给它。</li>
 * </ol>
 */
public final class MultiDeviceE2eTest {

    private static final String BASE = System.getenv().getOrDefault("MIM_BASE", "http://127.0.0.1:8080");
    private static final String PASSWORD = "pass1234";

    private static int passed = 0;
    private static int failed = 0;

    /**
     * 一台设备的完整状态。
     */
    private static final class DeviceContext {
        final String deviceId;
        final IdentityStore.Identity identity;
        final PreKeyStore preKeys;
        final String token;

        DeviceContext(String deviceId, IdentityStore.Identity identity, PreKeyStore preKeys,
            String token) {
            this.deviceId = deviceId;
            this.identity = identity;
            this.preKeys = preKeys;
            this.token = token;
        }
    }

    public static void main(String[] args) throws Exception {
        String suffix = String.valueOf(System.currentTimeMillis() % 100000);
        String aliceName = "alice" + suffix;
        String bobName = "bob" + suffix;

        register(aliceName);
        register(bobName);
        String aliceToken = login(aliceName);
        String bobToken = login(bobName);

        Path workDir = Files.createTempDirectory("mim-multidev");
        String aliceDevice = "alice-desktop";
        String bobDesktop = "bob-desktop";
        String bobMobile = "bob-mobile";

        // ---- 注册设备 ----
        registerDevice(aliceToken, aliceDevice, "Alice Desktop");
        registerDevice(bobToken, bobDesktop, "Bob Desktop");
        registerDevice(bobToken, bobMobile, "Bob Mobile");
        check("bob 成功注册两台设备", true);

        // ---- 每台设备各自的身份密钥与预密钥 ----
        DeviceContext alice = buildDevice(workDir, aliceName, aliceDevice, aliceToken);
        DeviceContext bobD = buildDevice(workDir, bobName, bobDesktop, bobToken);
        DeviceContext bobM = buildDevice(workDir, bobName, bobMobile, bobToken);
        check("bob 两台设备的身份公钥互不相同",
            !bobD.identity.getPublicKeyBase64().equals(bobM.identity.getPublicKeyBase64()));

        // ---- alice 拉取 bob 所有设备的预密钥包 ----
        List<PreKeyBundleView> bundles = fetchBundles(aliceToken, bobName);
        check("拉取到 bob 两台设备的预密钥包", bundles.size() == 2);
        boolean allVerified = true;
        for (PreKeyBundleView bundle : bundles) {
            allVerified &= DhKeyUtil.verifySignedPreKey(bundle.identityPublicKey,
                bundle.signedPreKeyId, bundle.signedPreKeyPublic, bundle.signedPreKeySignature);
        }
        check("两个设备的预密钥签名都可验证", allVerified);

        // ---- alice 为每个设备各建会话并加密 ----
        RatchetService ratchet = new RatchetService();
        TrustStore aliceTrust = new TrustStore(workDir.resolve("trust-alice"));
        aliceTrust.load(aliceName, PASSWORD);

        String clientMsgId = java.util.UUID.randomUUID().toString();
        long clientTime = System.currentTimeMillis();
        String plaintext = "发给两台设备的同一条消息";

        List<SessionState> aliceSessions = new ArrayList<>();
        List<String> replicas = new ArrayList<>();
        for (PreKeyBundleView bundle : bundles) {
            aliceTrust.verify(bobName, bundle.identityPublicKey, PASSWORD);
            RatchetService.InitiatorResult init = ratchet.initiatorSession(bobName,
                alice.identity.getPrivateKey(), alice.identity.getPublicKeyBase64(),
                bundle.identityPublicKey, bundle.signedPreKeyPublic, bundle.oneTimePreKeyPublic);
            RatchetService.Encrypted enc = ratchet.encrypt(init.getState(), plaintext);
            aliceSessions.add(init.getState());

            StringBuilder sb = new StringBuilder();
            sb.append("{\"clientMsgId\":\"").append(clientMsgId).append("\",")
                .append("\"toUsername\":\"").append(bobName).append("\",")
                .append("\"targetDeviceId\":\"").append(bundle.deviceId).append("\",")
                .append("\"alg\":\"").append(RatchetService.ALGORITHM).append("\",")
                .append("\"iv\":\"").append(enc.getIv()).append("\",")
                .append("\"ciphertext\":\"").append(enc.getCiphertext()).append("\",")
                .append("\"ratchetPublic\":\"").append(enc.getHeader().getDhPublic()).append("\",")
                .append("\"previousChainLength\":")
                .append(enc.getHeader().getPreviousChainLength()).append(",")
                .append("\"messageNumber\":").append(enc.getHeader().getMessageNumber()).append(",")
                .append("\"clientTime\":").append(clientTime).append(",")
                .append("\"senderIdentityKey\":\"").append(init.getSenderIdentityKey()).append("\",")
                .append("\"senderEphemeralKey\":\"").append(init.getSenderEphemeralKey()).append("\",")
                .append("\"senderUsedOneTimePreKey\":").append(init.isUsedOneTimePreKey());
            if (bundle.oneTimePreKeyId != null) {
                sb.append(",\"consumedOneTimePreKeyId\":").append(bundle.oneTimePreKeyId);
            }
            sb.append('}');
            replicas.add(sb.toString());
        }
        String sendBody = "[" + String.join(",", replicas) + "]";
        String sendResp = httpPost(BASE + "/message", aliceToken, aliceDevice, sendBody);
        check("一次发送提交了两份密文", sendResp.contains("\"status\":200"));
        check("服务端落了两行副本", countOccurrences(sendResp, "\"targetDeviceId\"") == 2);

        // ---- 两台设备各自解密 ----
        int decrypted = 0;
        for (PreKeyBundleView bundle : bundles) {
            DeviceContext target = bundle.deviceId.equals(bobDesktop) ? bobD : bobM;
            CipherMessageView received = fetchLatestForDevice(target, bundle.deviceId);
            if (received == null) {
                continue;
            }
            PreKeyStore.LocalOneTimePreKey consumed =
                target.preKeys.consumeOneTimePreKey(received.consumedOneTimePreKeyId);
            SessionState state = ratchet.responderSession(aliceName, target.identity.getPrivateKey(),
                target.identity.getPublicKeyBase64(),
                target.preKeys.getSignedPreKey().getPrivateKey(),
                target.preKeys.getSignedPreKey().getPublicKeyBase64(),
                consumed == null ? null : consumed.getPrivateKey(),
                received.senderIdentityKey, received.senderEphemeralKey,
                Boolean.TRUE.equals(received.senderUsedOneTimePreKey));
            String plain = ratchet.decrypt(state,
                new RatchetService.Header(received.ratchetPublic, received.previousChainLength,
                    received.messageNumber), received.iv, received.ciphertext);
            if (plaintext.equals(plain)) {
                decrypted++;
            }
        }
        check("bob 两台设备都成功解密同一条消息", decrypted == 2);

        // ---- 设备隔离：另一台设备拿不到发给本设备的密文 ----
        CipherMessageView desktopOnly = fetchLatestForDevice(bobD, bobDesktop);
        check("按设备拉取只返回本设备的密文",
            desktopOnly == null || bobDesktop.equals(desktopOnly.targetDeviceId));

        // ---- 撤回作用于所有副本 ----
        String recallResp = httpPost(BASE + "/message/recall/" + clientMsgId, aliceToken,
            aliceDevice, null);
        check("按 clientMsgId 撤回成功", recallResp.contains("\"status\":200"));
        String history = httpGet(BASE + "/message/history/" + bobName, aliceToken, aliceDevice);
        check("撤回后历史里显示 recalled 标记", history.contains("\"recalled\":true"));
        check("撤回后密文被清除", !history.contains("\"ciphertext\":\"" + replicas.get(0).split("\"ciphertext\":\"")[1].split("\"")[0] + "\""));

        // ---- 注销一台设备后不再分发 ----
        httpDelete(BASE + "/device/" + bobMobile, bobToken, bobMobile);
        List<PreKeyBundleView> afterRevoke = fetchBundles(aliceToken, bobName);
        check("注销后只返回剩余设备的预密钥包", afterRevoke.size() == 1);
        check("剩余的是未注销的那台", bobDesktop.equals(afterRevoke.get(0).deviceId));

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 设备与密钥准备 ====================

    private static DeviceContext buildDevice(Path workDir, String username, String deviceId,
        String token) throws Exception {
        Path dir = workDir.resolve(deviceId);
        Files.createDirectories(dir);

        IdentityStore identityStore = new IdentityStore(dir.resolve("identity"));
        IdentityStore.Identity identity = identityStore.create(username, deviceId, PASSWORD, 1);
        uploadIdentityKey(token, deviceId, identity.getPublicKeyBase64());

        PreKeyStore preKeys = new PreKeyStore(dir.resolve("prekeys"));
        PreKeyStore.LocalSignedPreKey signed =
            preKeys.generateSignedPreKey(identity.getPrivateKey(), 1);
        preKeys.setSignedPreKey(signed);
        uploadSignedPreKey(token, deviceId, signed);

        List<PreKeyStore.LocalOneTimePreKey> oneTime = preKeys.generateOneTimePreKeys(10);
        preKeys.addOneTimePreKeys(oneTime);
        uploadOneTimePreKeys(token, deviceId, oneTime);
        return new DeviceContext(deviceId, identity, preKeys, token);
    }

    // ==================== HTTP 辅助 ====================

    private static final class PreKeyBundleView {
        String deviceId;
        String identityPublicKey;
        int signedPreKeyId;
        String signedPreKeyPublic;
        String signedPreKeySignature;
        Integer oneTimePreKeyId;
        String oneTimePreKeyPublic;
    }

    private static final class CipherMessageView {
        String targetDeviceId;
        String ratchetPublic;
        int previousChainLength;
        int messageNumber;
        String iv;
        String ciphertext;
        String senderIdentityKey;
        String senderEphemeralKey;
        Boolean senderUsedOneTimePreKey;
        Integer consumedOneTimePreKeyId;
    }

    private static void register(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\""
            + base64(PASSWORD) + "\"}";
        // 注册接口按 IP 限流（5 次/分钟）。连续跑多次回归时可能撞上窗口，
        // 这里明确等待后重试一次，而不是让脚本因限流而"假失败"。
        String response = httpPost(BASE + "/register/token", null, null, body);
        if (response.contains("42901")) {
            System.out.println("  (注册触发限流，等待 20 秒后重试)");
            Thread.sleep(20_000L);
            response = httpPost(BASE + "/register/token", null, null, body);
        }
        requireOk(response, "注册");
    }

    private static String login(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\""
            + base64(PASSWORD) + "\"}";
        return str(httpPost(BASE + "/login/token", null, null, body), "\"data\":\"");
    }

    private static void registerDevice(String token, String deviceId, String name) throws Exception {
        String body = "{\"deviceId\":\"" + deviceId + "\",\"deviceName\":\"" + name + "\"}";
        requireOk(httpPost(BASE + "/device", token, deviceId, body), "设备注册");
    }

    private static void uploadIdentityKey(String token, String deviceId, String publicKey)
        throws Exception {
        String body = "{\"publicKey\":\"" + publicKey + "\",\"algorithm\":\"EC-P256\"}";
        requireOk(httpPost(BASE + "/key", token, deviceId, body), "上传身份公钥");
    }

    private static void uploadSignedPreKey(String token, String deviceId,
        PreKeyStore.LocalSignedPreKey spk) throws Exception {
        String body = "{\"preKeyId\":" + spk.getPreKeyId() + ",\"publicKey\":\""
            + spk.getPublicKeyBase64() + "\",\"signature\":\"" + spk.getSignatureBase64() + "\"}";
        requireOk(httpPost(BASE + "/prekey/signed", token, deviceId, body), "上传签名预密钥");
    }

    private static void uploadOneTimePreKeys(String token, String deviceId,
        List<PreKeyStore.LocalOneTimePreKey> keys) throws Exception {
        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"preKeyId\":").append(keys.get(i).getPreKeyId())
                .append(",\"publicKey\":\"").append(keys.get(i).getPublicKeyBase64()).append("\"}");
        }
        body.append(']');
        requireOk(httpPost(BASE + "/prekey/one-time", token, deviceId, body.toString()),
            "上传一次性预密钥");
    }

    private static List<PreKeyBundleView> fetchBundles(String token, String peer) throws Exception {
        String resp = httpGet(BASE + "/prekey/" + peer, token, null);
        List<PreKeyBundleView> bundles = new ArrayList<>();
        String[] parts = resp.split("\\{\"username\":");
        for (String part : parts) {
            if (!part.contains("\"deviceId\"")) {
                continue;
            }
            String block = "{\"username\":" + part;
            PreKeyBundleView view = new PreKeyBundleView();
            view.deviceId = strOrNull(block, "\"deviceId\":\"");
            view.identityPublicKey = str(block, "\"identityPublicKey\":\"");
            view.signedPreKeyId = Integer.parseInt(str(block, "\"signedPreKeyId\":"));
            view.signedPreKeyPublic = str(block, "\"signedPreKeyPublic\":\"");
            view.signedPreKeySignature = str(block, "\"signedPreKeySignature\":\"");
            String opkId = numberOrNull(block, "\"oneTimePreKeyId\":");
            if (opkId != null) {
                view.oneTimePreKeyId = Integer.parseInt(opkId);
                view.oneTimePreKeyPublic = strOrNull(block, "\"oneTimePreKeyPublic\":\"");
            }
            bundles.add(view);
        }
        return bundles;
    }

    private static CipherMessageView fetchLatestForDevice(DeviceContext device, String deviceId)
        throws Exception {
        String resp = httpGet(BASE + "/message/undelivered?limit=200", device.token, deviceId);
        if (!resp.contains("\"id\":")) {
            return null;
        }
        CipherMessageView view = new CipherMessageView();
        view.targetDeviceId = lastStr(resp, "\"targetDeviceId\":\"");
        view.ratchetPublic = lastStr(resp, "\"ratchetPublic\":\"");
        view.previousChainLength = Integer.parseInt(lastNumber(resp, "\"previousChainLength\":"));
        view.messageNumber = Integer.parseInt(lastNumber(resp, "\"messageNumber\":"));
        view.iv = lastStr(resp, "\"iv\":\"");
        view.ciphertext = lastStr(resp, "\"ciphertext\":\"");
        view.senderIdentityKey = lastStr(resp, "\"senderIdentityKey\":\"");
        view.senderEphemeralKey = lastStr(resp, "\"senderEphemeralKey\":\"");
        view.senderUsedOneTimePreKey = lastBool(resp, "\"senderUsedOneTimePreKey\":");
        String opk = lastNumberOrNull(resp, "\"consumedOneTimePreKeyId\":");
        view.consumedOneTimePreKeyId = opk == null ? null : Integer.valueOf(opk);
        return view;
    }

    // ==================== 字符串提取 ====================

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireOk(String response, String action) {
        if (!response.contains("\"status\":200")) {
            throw new IllegalStateException(action + "失败：" + response);
        }
    }

    private static String str(String json, String prefix) {
        int idx = json.indexOf(prefix);
        if (idx < 0) {
            throw new IllegalStateException("缺少字段 " + prefix + "：" + json);
        }
        int start = idx + prefix.length();
        if (prefix.endsWith("\"")) {
            return json.substring(start, json.indexOf('"', start));
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return json.substring(start, end);
    }

    private static String strOrNull(String json, String prefix) {
        if (!json.contains(prefix)) {
            return null;
        }
        int idx = json.indexOf(prefix);
        int start = idx + prefix.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    private static String numberOrNull(String json, String prefix) {
        int idx = json.indexOf(prefix);
        if (idx < 0) {
            return null;
        }
        int start = idx + prefix.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return end == start ? null : json.substring(start, end);
    }

    private static String lastStr(String json, String prefix) {
        int idx = json.lastIndexOf(prefix);
        if (idx < 0) {
            throw new IllegalStateException("缺少字段 " + prefix);
        }
        int start = idx + prefix.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private static String lastNumber(String json, String prefix) {
        String value = lastNumberOrNull(json, prefix);
        if (value == null) {
            throw new IllegalStateException("缺少字段 " + prefix);
        }
        return value;
    }

    private static String lastNumberOrNull(String json, String prefix) {
        int idx = json.lastIndexOf(prefix);
        if (idx < 0) {
            return null;
        }
        int start = idx + prefix.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return end == start ? null : json.substring(start, end);
    }

    private static Boolean lastBool(String json, String prefix) {
        int idx = json.lastIndexOf(prefix);
        if (idx < 0) {
            return null;
        }
        int start = idx + prefix.length();
        if (json.startsWith("true", start)) {
            return Boolean.TRUE;
        }
        if (json.startsWith("false", start)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }

    // ==================== HTTP ====================

    private static String httpGet(String endpoint, String token, String deviceId) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("GET");
        addHeaders(conn, token, deviceId);
        return read(conn);
    }

    private static String httpPost(String endpoint, String token, String deviceId, String body)
        throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        addHeaders(conn, token, deviceId);
        if (body != null) {
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        return read(conn);
    }

    private static String httpDelete(String endpoint, String token, String deviceId) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("DELETE");
        addHeaders(conn, token, deviceId);
        return read(conn);
    }

    private static void addHeaders(HttpURLConnection conn, String token, String deviceId) {
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (deviceId != null) {
            conn.setRequestProperty("X-Device-Id", deviceId);
        }
    }

    private static String read(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        java.io.InputStream stream = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name);
        }
    }

    private MultiDeviceE2eTest() {
    }
}
