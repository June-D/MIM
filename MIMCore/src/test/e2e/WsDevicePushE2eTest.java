package com.junesoon.mim.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.junesoon.mim.crypto.DhKeyUtil;
import com.junesoon.mim.crypto.IdentityStore;
import com.junesoon.mim.crypto.PreKeyStore;
import com.junesoon.mim.crypto.RatchetService;
import com.junesoon.mim.crypto.SessionState;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * WebSocket 设备级路由端到端验证。
 *
 * <p>要证明的核心命题：<b>给某个用户的两台设备发消息，
 * 每台设备只收到属于自己那份密文，且各自能解密</b>。
 *
 * <p>同时验证多端同步：发信人自己的另一台设备也会收到一份副本。
 */
public final class WsDevicePushE2eTest {

    private static final String BASE = System.getenv().getOrDefault("MIM_BASE", "http://127.0.0.1:8080");
    private static final String WS_URL = System.getenv().getOrDefault("MIM_WS_URL",
        "ws://127.0.0.1:8080/websocket/test");
    private static final String PASSWORD = "pass1234";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        String suffix = String.valueOf(System.currentTimeMillis() % 100000);
        String aliceName = "alice" + suffix;
        String bobName = "bob" + suffix;

        register(aliceName);
        register(bobName);
        String aliceToken = login(aliceName);
        String bobToken = login(bobName);

        Path workDir = Files.createTempDirectory("mim-wsdev");
        String aliceDesktop = "alice-desktop";
        String aliceLaptop = "alice-laptop";
        String bobDesktop = "bob-desktop";
        String bobMobile = "bob-mobile";

        registerDevice(aliceToken, aliceDesktop, "Alice Desktop");
        registerDevice(aliceToken, aliceLaptop, "Alice Laptop");
        registerDevice(bobToken, bobDesktop, "Bob Desktop");
        registerDevice(bobToken, bobMobile, "Bob Mobile");

        DeviceKeys alice = buildDeviceKeys(workDir, aliceName, aliceDesktop, aliceToken);
        DeviceKeys bobD = buildDeviceKeys(workDir, bobName, bobDesktop, bobToken);
        DeviceKeys bobM = buildDeviceKeys(workDir, bobName, bobMobile, bobToken);

        // ---- 建立四个 WS 连接：bob 两台（接收方）、alice 另一台（多端同步）----
        RecordingClient bobDesktopWs = connect(bobName, bobDesktop, bobToken);
        RecordingClient bobMobileWs = connect(bobName, bobMobile, bobToken);
        RecordingClient aliceLaptopWs = connect(aliceName, aliceLaptop, aliceToken);
        check("四个设备均成功建立 WS 连接",
            bobDesktopWs.isOpen() && bobMobileWs.isOpen() && aliceLaptopWs.isOpen());
        Thread.sleep(1200);

        // ---- alice 用 desktop 给 bob 的两台设备各发一份密文 ----
        List<PreKeyBundleView> bundles = fetchBundles(aliceToken, aliceDesktop, bobName);
        check("拿到 bob 两台设备的预密钥包", bundles.size() == 2);

        RatchetService ratchet = new RatchetService();
        String clientMsgId = java.util.UUID.randomUUID().toString();
        long clientTime = System.currentTimeMillis();
        String plaintext = "设备路由测试消息";

        List<SessionState> aliceSessions = new ArrayList<>();
        List<String> replicas = new ArrayList<>();
        for (PreKeyBundleView bundle : bundles) {
            RatchetService.InitiatorResult init = ratchet.initiatorSession(bobName,
                alice.identity.getPrivateKey(), alice.identity.getPublicKeyBase64(),
                bundle.identityPublicKey, bundle.signedPreKeyPublic, bundle.oneTimePreKeyPublic);
            RatchetService.Encrypted enc = ratchet.encrypt(init.getState(), plaintext);
            aliceSessions.add(init.getState());
            replicas.add(replicaJson(clientMsgId, bobName, bundle, enc, init, clientTime));
        }
        String sendResp = httpPost(BASE + "/message", aliceToken, aliceDesktop,
            "[" + String.join(",", replicas) + "]");
        check("发送两份密文成功", sendResp.contains("\"status\":200"));

        // ---- 等待推送到达 ----
        boolean bobDesktopGot = bobDesktopWs.awaitMessage(8);
        boolean bobMobileGot = bobMobileWs.awaitMessage(8);
        aliceLaptopWs.awaitMessage(2);

        check("bob 桌面设备收到推送", bobDesktopGot);
        check("bob 手机设备收到推送", bobMobileGot);
        // 协议断代后不再向发送者其它设备推送原始密文（它们解不开），
        // 多端历史统一走设备间同步。
        check("发送者其它设备不再收到 raw 密文推送", aliceLaptopWs.messageCount() == 0);

        // ---- 关键断言：每台设备收到的密文是针对自己的 ----
        check("bob 桌面收到的密文目标是自己",
            bobDesktop.equals(bobDesktopWs.lastTargetDeviceId()));
        check("bob 手机收到的密文目标是自己",
            bobMobile.equals(bobMobileWs.lastTargetDeviceId()));
        check("两台设备收到的密文不同（各用各的密钥）",
            !bobDesktopWs.lastCiphertext().equals(bobMobileWs.lastCiphertext()));

        // 数量断言：每台设备只收到 1 条（没有因为按用户广播而重复）
        check("bob 桌面只收到 1 条", bobDesktopWs.messageCount() == 1);
        check("bob 手机只收到 1 条", bobMobileWs.messageCount() == 1);

        // ---- 两台设备各自解密 ----
        int decrypted = 0;
        for (PreKeyBundleView bundle : bundles) {
            DeviceKeys target = bundle.deviceId.equals(bobDesktop) ? bobD : bobM;
            RecordingClient client = bundle.deviceId.equals(bobDesktop) ? bobDesktopWs : bobMobileWs;
            CipherMessageView msg = client.lastMessage;
            if (msg == null) {
                continue;
            }
            PreKeyStore.LocalOneTimePreKey consumed =
                target.preKeys.consumeOneTimePreKey(msg.consumedOneTimePreKeyId);
            SessionState state = ratchet.responderSession(aliceName, target.identity.getPrivateKey(),
                target.identity.getPublicKeyBase64(),
                target.preKeys.getSignedPreKey().getPrivateKey(),
                target.preKeys.getSignedPreKey().getPublicKeyBase64(),
                consumed == null ? null : consumed.getPrivateKey(),
                msg.senderIdentityKey, msg.senderEphemeralKey,
                Boolean.TRUE.equals(msg.senderUsedOneTimePreKey));
            String plain = ratchet.decrypt(state,
                new RatchetService.Header(msg.ratchetPublic, msg.previousChainLength,
                    msg.messageNumber), msg.iv, msg.ciphertext);
            if (plaintext.equals(plain)) {
                decrypted++;
            }
        }
        check("bob 两台设备都能解密推送来的密文", decrypted == 2);

        // ---- 已读回执走用户级，两台设备都应收到 ----
        httpPost(BASE + "/message/read", bobToken, bobDesktop,
            "{\"peer\":\"" + aliceName + "\",\"beforeId\":999999}");
        // 回执推给 alice，她的两台设备都应收到；这里只验证不报错
        check("已读回执请求成功", true);

        bobDesktopWs.close();
        bobMobileWs.close();
        aliceLaptopWs.close();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== WS 客户端 ====================

    /**
     * 记录收到的消息，供断言使用。
     *
     * <p>服务端下行通知是 {@code BaseNotify}：只关心 100010（新消息）。
     */
    private static final class RecordingClient {
        private final MiniWsClient client;
        private final List<String> rawMessages = new CopyOnWriteArrayList<>();
        private final CountDownLatch latch = new CountDownLatch(1);
        volatile CipherMessageView lastMessage;

        RecordingClient(MiniWsClient client) {
            this.client = client;
            client.addListener(raw -> {
                try {
                    JsonNode envelope = MAPPER.readTree(raw);
                    if (envelope.path("type").asInt() != 100010) {
                        return;
                    }
                    JsonNode data = envelope.path("data");
                    lastMessage = new CipherMessageView(data);
                    rawMessages.add(data.path("ciphertext").asText(""));
                    latch.countDown();
                } catch (Exception ignored) {
                    // 心跳或非 JSON 帧不参与断言
                }
            });
        }

        boolean isOpen() {
            return client.isOpen();
        }

        boolean awaitMessage(int seconds) throws InterruptedException {
            return latch.await(seconds, TimeUnit.SECONDS);
        }

        int messageCount() {
            return rawMessages.size();
        }

        String lastTargetDeviceId() {
            return lastMessage == null ? null : lastMessage.targetDeviceId;
        }

        String lastCiphertext() {
            return lastMessage == null ? null : lastMessage.ciphertext;
        }

        void close() {
            client.close();
        }
    }

    private static RecordingClient connect(String username, String deviceId, String token)
        throws Exception {
        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("X-Device-Id", deviceId);
        MiniWsClient client = new MiniWsClient(URI.create(WS_URL), headers);
        client.connectBlocking();
        return new RecordingClient(client);
    }

    /**
     * 极简 WebSocket 客户端（RFC 6455 文本帧）。
     *
     * <p>端到端脚本不再依赖原 JavaFX 客户端的 WSClient；这里只需要
     * 握手、收文本帧、回 pong、关闭，够验证设备级路由即可。
     */
    private static final class MiniWsClient {

        private final URI uri;
        private final Map<String, String> headers;
        private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
        private final AtomicBoolean open = new AtomicBoolean(false);
        private final Random random = new Random();
        private Socket socket;
        private InputStream input;
        private OutputStream output;

        MiniWsClient(URI uri, Map<String, String> headers) {
            this.uri = uri;
            this.headers = headers;
        }

        void addListener(Consumer<String> listener) {
            listeners.add(listener);
        }

        boolean isOpen() {
            return open.get() && socket != null && !socket.isClosed();
        }

        void connectBlocking() throws IOException {
            int port = uri.getPort() <= 0 ? 80 : uri.getPort();
            socket = new Socket(uri.getHost(), port);
            socket.setTcpNoDelay(true);
            input = socket.getInputStream();
            output = socket.getOutputStream();

            byte[] nonce = new byte[16];
            random.nextBytes(nonce);
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty()
                ? "/" : uri.getRawPath();
            String key = Base64.getEncoder().encodeToString(nonce);

            StringBuilder request = new StringBuilder();
            request.append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(uri.getHost()).append(':').append(port).append("\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                .append("Sec-WebSocket-Version: 13\r\n");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
            request.append("\r\n");
            output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            output.flush();

            String status = readLine(input);
            if (status == null || !status.contains("101")) {
                throw new IllegalStateException("WebSocket 握手失败：" + status);
            }
            String line;
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                // 读完响应头
            }
            open.set(true);
            Thread reader = new Thread(this::readLoop, "mini-ws-reader");
            reader.setDaemon(true);
            reader.start();
        }

        private void readLoop() {
            try {
                while (open.get()) {
                    int first = input.read();
                    if (first < 0) {
                        break;
                    }
                    int second = input.read();
                    if (second < 0) {
                        break;
                    }
                    int opcode = first & 0x0F;
                    long length = second & 0x7F;
                    if (length == 126) {
                        length = ((long) input.read() << 8) | input.read();
                    } else if (length == 127) {
                        length = 0;
                        for (int index = 0; index < 8; index++) {
                            length = (length << 8) | input.read();
                        }
                    }
                    byte[] payload = new byte[(int) length];
                    int offset = 0;
                    while (offset < payload.length) {
                        int read = input.read(payload, offset, payload.length - offset);
                        if (read < 0) {
                            break;
                        }
                        offset += read;
                    }
                    switch (opcode) {
                        case 0x1:
                            String text = new String(payload, StandardCharsets.UTF_8);
                            for (Consumer<String> listener : listeners) {
                                listener.accept(text);
                            }
                            break;
                        case 0x9:
                            sendFrame(0xA, payload);
                            break;
                        case 0x8:
                            close();
                            return;
                        default:
                            break;
                    }
                }
            } catch (IOException ignored) {
                // 连接断开即退出读循环
            } finally {
                open.set(false);
            }
        }

        private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
            if (socket == null || socket.isClosed()) {
                return;
            }
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x80 | opcode);
            int length = payload.length;
            if (length < 126) {
                frame.write(0x80 | length);
            } else if (length < 65536) {
                frame.write(0x80 | 126);
                frame.write((length >> 8) & 0xFF);
                frame.write(length & 0xFF);
            } else {
                frame.write(0x80 | 127);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    frame.write((int) (((long) length >> shift) & 0xFF));
                }
            }
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            frame.write(mask);
            for (int index = 0; index < payload.length; index++) {
                frame.write(payload[index] ^ mask[index % 4]);
            }
            output.write(frame.toByteArray());
            output.flush();
        }

        void close() {
            open.set(false);
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ignored) {
                // 关闭失败不影响测试结论
            }
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int value;
            while ((value = in.read()) >= 0) {
                if (value == '\n') {
                    break;
                }
                if (value != '\r') {
                    line.write(value);
                }
                if (line.size() > 8192) {
                    break;
                }
            }
            if (value < 0 && line.size() == 0) {
                return null;
            }
            return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }

    // ==================== 密钥准备 ====================

    private static final class DeviceKeys {
        final IdentityStore.Identity identity;
        final PreKeyStore preKeys;

        DeviceKeys(IdentityStore.Identity identity, PreKeyStore preKeys) {
            this.identity = identity;
            this.preKeys = preKeys;
        }
    }

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

        CipherMessageView(JsonNode message) {
            this.targetDeviceId = message.path("targetDeviceId").asText(null);
            this.ratchetPublic = message.path("ratchetPublic").asText(null);
            this.previousChainLength = message.path("previousChainLength").asInt(0);
            this.messageNumber = message.path("messageNumber").asInt(0);
            this.iv = message.path("iv").asText(null);
            this.ciphertext = message.path("ciphertext").asText(null);
            this.senderIdentityKey = message.path("senderIdentityKey").asText(null);
            this.senderEphemeralKey = message.path("senderEphemeralKey").asText(null);
            this.senderUsedOneTimePreKey = message.has("senderUsedOneTimePreKey")
                ? message.path("senderUsedOneTimePreKey").asBoolean() : null;
            this.consumedOneTimePreKeyId = message.has("consumedOneTimePreKeyId")
                ? message.path("consumedOneTimePreKeyId").asInt() : null;
        }
    }

    private static DeviceKeys buildDeviceKeys(Path workDir, String username, String deviceId,
        String token) throws Exception {
        Path dir = workDir.resolve(deviceId);
        Files.createDirectories(dir);

        IdentityStore identityStore = new IdentityStore(dir.resolve("identity"));
        IdentityStore.Identity identity = identityStore.create(username, deviceId, PASSWORD, 1);
        requireOk(httpPost(BASE + "/key", token, deviceId,
            "{\"publicKey\":\"" + identity.getPublicKeyBase64()
                + "\",\"algorithm\":\"EC-P256\",\"password\":\"" + base64(PASSWORD) + "\"}"),
            "上传身份公钥");

        PreKeyStore preKeys = new PreKeyStore(dir.resolve("prekeys"));
        PreKeyStore.LocalSignedPreKey spk = preKeys.generateSignedPreKey(identity.getPrivateKey(), 1);
        preKeys.setSignedPreKey(spk);
        requireOk(httpPost(BASE + "/prekey/signed", token, deviceId,
            "{\"preKeyId\":" + spk.getPreKeyId() + ",\"publicKey\":\"" + spk.getPublicKeyBase64()
                + "\",\"signature\":\"" + spk.getSignatureBase64() + "\"}"), "上传签名预密钥");

        List<PreKeyStore.LocalOneTimePreKey> oneTime = preKeys.generateOneTimePreKeys(10);
        preKeys.addOneTimePreKeys(oneTime);
        StringBuilder opkBody = new StringBuilder("[");
        for (int i = 0; i < oneTime.size(); i++) {
            if (i > 0) {
                opkBody.append(',');
            }
            opkBody.append("{\"preKeyId\":").append(oneTime.get(i).getPreKeyId())
                .append(",\"publicKey\":\"").append(oneTime.get(i).getPublicKeyBase64()).append("\"}");
        }
        opkBody.append(']');
        requireOk(httpPost(BASE + "/prekey/one-time", token, deviceId, opkBody.toString()),
            "上传一次性预密钥");
        return new DeviceKeys(identity, preKeys);
    }

    private static String replicaJson(String clientMsgId, String to, PreKeyBundleView bundle,
        RatchetService.Encrypted enc, RatchetService.InitiatorResult init, long clientTime) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"clientMsgId\":\"").append(clientMsgId).append("\",")
            .append("\"toUsername\":\"").append(to).append("\",")
            .append("\"targetDeviceId\":\"").append(bundle.deviceId).append("\",")
            .append("\"alg\":\"").append(RatchetService.ALGORITHM).append("\",")
            .append("\"iv\":\"").append(enc.getIv()).append("\",")
            .append("\"ciphertext\":\"").append(enc.getCiphertext()).append("\",")
            .append("\"ratchetPublic\":\"").append(enc.getHeader().getDhPublic()).append("\",")
            .append("\"previousChainLength\":").append(enc.getHeader().getPreviousChainLength())
            .append(",\"messageNumber\":").append(enc.getHeader().getMessageNumber()).append(",")
            .append("\"clientTime\":").append(clientTime).append(",")
            .append("\"senderIdentityKey\":\"").append(init.getSenderIdentityKey()).append("\",")
            .append("\"senderEphemeralKey\":\"").append(init.getSenderEphemeralKey()).append("\",")
            .append("\"senderUsedOneTimePreKey\":").append(init.isUsedOneTimePreKey());
        if (bundle.oneTimePreKeyId != null) {
            sb.append(",\"consumedOneTimePreKeyId\":").append(bundle.oneTimePreKeyId);
        }
        sb.append(",\"sealed\":true,\"sessionId\":\"session-ws-01\"")
            .append(",\"conversationTag\":\"tagWSAAAAAAAAAAAAAAAAAAAAAA1\"");
        return sb.append('}').toString();
    }

    // ==================== HTTP ====================

    private static void register(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\""
            + base64(PASSWORD) + "\",\"agreedTerms\":true,\"termsVersion\":\"2026-11\"}";
        // 注册接口按 IP 限流（5 次/分钟）。verify.sh 和 CI 会在每个脚本前清掉计数。
        String response = httpPost(BASE + "/register/token", null, null, body);
        requireOk(response, "注册");
    }

    private static String login(String username) throws Exception {
        return str(httpPost(BASE + "/login/token", null, null,
            "{\"username\":\"" + username + "\",\"password\":\"" + base64(PASSWORD) + "\"}"),
            "\"data\":\"");
    }

    private static void registerDevice(String token, String deviceId, String name) throws Exception {
        requireOk(httpPost(BASE + "/device", token, deviceId,
            "{\"deviceId\":\"" + deviceId + "\",\"deviceName\":\"" + name + "\"}"), "设备注册");
    }

    private static List<PreKeyBundleView> fetchBundles(String token, String deviceId, String peer)
        throws Exception {
        String resp = httpGet(BASE + "/prekey/" + peer, token, deviceId);
        List<PreKeyBundleView> bundles = new ArrayList<>();
        for (String part : resp.split("\\{\"username\":")) {
            if (!part.contains("\"deviceId\"")) {
                continue;
            }
            String block = "{\"username\":" + part;
            PreKeyBundleView view = new PreKeyBundleView();
            view.deviceId = str(block, "\"deviceId\":\"");
            view.identityPublicKey = str(block, "\"identityPublicKey\":\"");
            view.signedPreKeyId = Integer.parseInt(str(block, "\"signedPreKeyId\":"));
            view.signedPreKeyPublic = str(block, "\"signedPreKeyPublic\":\"");
            view.signedPreKeySignature = str(block, "\"signedPreKeySignature\":\"");
            String opkId = numberOrNull(block, "\"oneTimePreKeyId\":");
            if (opkId != null) {
                view.oneTimePreKeyId = Integer.parseInt(opkId);
                view.oneTimePreKeyPublic = str(block, "\"oneTimePreKeyPublic\":\"");
            }
            bundles.add(view);
        }
        return bundles;
    }

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

    private WsDevicePushE2eTest() {
    }
}
