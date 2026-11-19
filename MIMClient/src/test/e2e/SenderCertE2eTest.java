package com.junesoon.mim.crypto;

import com.junesoon.mim.model.SenderCertificateView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/**
 * 发送者证书端到端回归（Phase 2b）。
 *
 * <p>覆盖服务端能验证的部分：能力位、证书签发与独立验签、首条 sealed 消息
 * 不落 from_user、收信端能按证书还原归属、投递后握手参数被擦除、
 * 以及「首条 sealed 消息在收信端历史里可查」。
 *
 * <p>本脚本不走 {@code MessageService}（它需要完整应用状态），
 * 用真实的双棘轮 + 真实的证书信封模拟两端；UI 层仍需人工验证。
 *
 * <p>服务端默认关闭该功能。未启用时打印 SKIP 并以 0 退出，
 * 启用方式：{@code MIM_SENDER_CERT_ENABLED=true} 启动服务端。
 *
 * [INPUT]: 依赖运行中的服务端、客户端加解密类与 /cert/*、/message/* 接口
 * [OUTPUT]: 通过 9 项检查或明确跳过
 * [POS]: Phase 2b 的服务端契约回归；与 MultiDeviceE2eTest 互补
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
public final class SenderCertE2eTest {

    private static final String BASE = System.getenv().getOrDefault("MIM_BASE",
        "http://127.0.0.1:8080");
    private static final String PASSWORD = "pass1234";

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        String suffix = String.valueOf(System.currentTimeMillis() % 100000);
        String alice = "certAlice" + suffix;
        String bob = "certBob" + suffix;
        String aliceDevice = "cert-alice-dev";
        String bobDevice = "cert-bob-dev";

        // 功能未启用时明确跳过，不伪装成通过
        String certKeyResponse = httpGet(BASE + "/cert/key", null, null);
        if (certKeyResponse.contains("\"status\":40001")) {
            System.out.println("SKIP: 发送者证书未启用（MIM_SENDER_CERT_ENABLED=false）");
            return;
        }

        register(alice);
        register(bob);
        String aliceToken = login(alice, aliceDevice);
        String bobToken = login(bob, bobDevice);
        registerDevice(aliceToken, aliceDevice);
        registerDevice(bobToken, bobDevice);

        Path workDir = Files.createTempDirectory("mim-cert-e2e");
        IdentityStore aliceStore = new IdentityStore(workDir.resolve("alice-identity"));
        IdentityStore.Identity aliceIdentity =
            aliceStore.create(alice, aliceDevice, PASSWORD, 1);
        uploadIdentityKey(aliceToken, aliceDevice, aliceIdentity.getPublicKeyBase64());

        // bob 的预密钥只在本机生成，用于真实的双棘轮往返（不需要上传）
        IdentityStore bobStore = new IdentityStore(workDir.resolve("bob-identity"));
        IdentityStore.Identity bobIdentity = bobStore.create(bob, bobDevice, PASSWORD, 1);
        PreKeyStore bobPreKeys = new PreKeyStore(workDir.resolve("bob-prekeys"));
        PreKeyStore.LocalSignedPreKey bobSigned =
            bobPreKeys.generateSignedPreKey(bobIdentity.getPrivateKey(), 1);
        List<PreKeyStore.LocalOneTimePreKey> bobOneTime = bobPreKeys.generateOneTimePreKeys(1);
        PreKeyStore.LocalOneTimePreKey bobOpk = bobOneTime.get(0);

        // ---- 证书签发与独立验签 ----
        String certResponse = httpPost(BASE + "/cert/sender", aliceToken, aliceDevice, null);
        requireOk(certResponse, "证书签发");
        SenderCertificateView cert = parseCertificate(certResponse);
        check("证书归属正确（username/device/identityKey）",
            alice.equals(cert.getUsername()) && aliceDevice.equals(cert.getDeviceId())
                && aliceIdentity.getPublicKeyBase64().equals(cert.getIdentityKey()));

        String keyJson = httpGet(BASE + "/cert/key", null, null);
        String serverPub = str(keyJson, "\"publicKey\":\"");
        String serverKeyId = str(keyJson, "\"keyId\":\"");
        check("证书 keyId 与分发的公钥一致", serverKeyId.equals(cert.getKeyId()));
        check("证书签名可用分发公钥验证", SenderCertVerifier.verify(cert, serverPub));
        check("证书有效期为 24 小时",
            cert.getExpiresAt() - cert.getIssuedAt() == 24L * 60 * 60 * 1000);

        // ---- 首条消息：真实双棘轮 + 证书信封 ----
        String plaintext = "首条 sealed 消息";
        String conversationTag = "tagE2EAAAAAAAAAAAAAAAAAAAA1";
        String recallToken = "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        String payload = SenderCertEnvelope.pack(cert, conversationTag, plaintext);
        RatchetService ratchet = new RatchetService();
        RatchetService.InitiatorResult init = ratchet.initiatorSession(bob, 
            aliceIdentity.getPrivateKey(), aliceIdentity.getPublicKeyBase64(),
            bobIdentity.getPublicKeyBase64(), bobSigned.getPublicKeyBase64(),
            bobOpk.getPublicKeyBase64());
        RatchetService.Encrypted encrypted = ratchet.encrypt(init.getState(), payload);

        String clientMsgId = "cert-first-" + suffix;
        String replica = "{"
            + "\"clientMsgId\":\"" + clientMsgId + "\","
            + "\"toUsername\":\"" + bob + "\","
            + "\"targetDeviceId\":\"" + bobDevice + "\","
            + "\"alg\":\"" + RatchetService.ALGORITHM + "\","
            + "\"iv\":\"" + encrypted.getIv() + "\","
            + "\"ciphertext\":\"" + encrypted.getCiphertext() + "\","
            + "\"ratchetPublic\":\"" + encrypted.getHeader().getDhPublic() + "\","
            + "\"previousChainLength\":" + encrypted.getHeader().getPreviousChainLength() + ","
            + "\"messageNumber\":" + encrypted.getHeader().getMessageNumber() + ","
            + "\"clientTime\":" + System.currentTimeMillis() + ","
            + "\"conversationTag\":\"" + conversationTag + "\","
            + "\"recallToken\":\"" + recallToken + "\","
            + "\"sealed\":true,\"sessionId\":\"session-cert-01\","
            + "\"senderIdentityKey\":\"" + aliceIdentity.getPublicKeyBase64() + "\","
            + "\"senderEphemeralKey\":\"" + init.getSenderEphemeralKey() + "\","
            + "\"senderUsedOneTimePreKey\":true,"
            + "\"consumedOneTimePreKeyId\":" + bobOpk.getPreKeyId() + ","
            + "\"recipientSignedPreKeyId\":1}";

        String sendResponse = httpPost(BASE + "/message", aliceToken, aliceDevice,
            "[" + replica + "]");
        requireOk(sendResponse, "发送首条 sealed 消息");
        check("首条 sealed 消息不落明文发送者", sendResponse.contains("\"fromUsername\":null"));

        // ---- 收信端：拉取、还原、解密 ----
        String inbox = httpGet(BASE + "/message/undelivered?limit=50", bobToken, bobDevice);
        int block = inbox.indexOf(clientMsgId);
        check("收信端拿到首条消息", block >= 0);
        check("收信端看到的发送者为空、握手参数完整",
            inbox.contains("\"fromUsername\":null")
                && inbox.contains("\"senderIdentityKey\":\""));

        String senderIdentity = str(inbox, "\"senderIdentityKey\":\"");
        String senderEphemeral = str(inbox, "\"senderEphemeralKey\":\"");
        String ratchetPublic = lastStr(inbox, "\"ratchetPublic\":\"");
        String iv = lastStr(inbox, "\"iv\":\"");
        String ciphertext = lastStr(inbox, "\"ciphertext\":\"");
        int previousChainLength = Integer.parseInt(lastNumber(inbox, "\"previousChainLength\":"));
        int messageNumber = Integer.parseInt(lastNumber(inbox, "\"messageNumber\":"));

        check("证书身份公钥与握手参数一致",
            SenderCertVerifier.matchesHandshake(cert, senderIdentity));

        SessionState bobSession = ratchet.responderSession(alice, bobIdentity.getPrivateKey(),
            bobIdentity.getPublicKeyBase64(), bobSigned.getPrivateKey(),
            bobSigned.getPublicKeyBase64(), bobOpk.getPrivateKey(), senderIdentity,
            senderEphemeral, true);
        String decrypted = ratchet.decrypt(bobSession,
            new RatchetService.Header(ratchetPublic, previousChainLength, messageNumber), iv,
            ciphertext);
        SenderCertEnvelope.Parsed parsed = SenderCertEnvelope.unpack(decrypted);
        check("收信端解出证书与正文",
            parsed != null && plaintext.equals(parsed.getText())
                && alice.equals(parsed.getCertificate().getUsername()));
        check("收信端从信封学到会话标签",
            parsed != null && conversationTag.equals(parsed.getConversationTag()));

        // ---- 投递确认后握手参数被擦除，且历史仍可查 ----
        String deliveredId = lastNumberOrNull(inbox, "\"id\":");
        requireOk(httpPost(BASE + "/message/delivered?beforeId=" + deliveredId, bobToken,
            bobDevice, null), "投递确认");
        // 停写盲引用后，历史必须带会话标签；不带标签只对旧消息有效
        String history = httpGet(BASE + "/message/history/" + alice + "?limit=50&tags="
            + conversationTag, bobToken, bobDevice);
        check("首条 sealed 消息在收信端历史里可查", history.contains(clientMsgId));
        check("投递后握手参数已从历史中擦除",
            !history.contains("\"senderIdentityKey\":\"")
                && !history.contains("\"senderEphemeralKey\":\""));

        // ---- 按会话标签查历史：不依赖从用户名推导的盲引用 ----
        String tagHistory = httpGet(BASE + "/message/history/" + alice
            + "?limit=50&tags=" + conversationTag, bobToken, bobDevice);
        check("服务端可按会话标签返回历史", tagHistory.contains(clientMsgId));
        check("历史响应不回传会话标签", !tagHistory.contains("\"conversationTag\""));

        // ---- 未读/已读/清除按标签工作 ----
        String unread = httpGet(BASE + "/message/unread/" + alice + "?tags=" + conversationTag,
            bobToken, bobDevice);
        check("按标签统计未读为 1", unread.contains("\"data\":1"));
        requireOk(httpPost(BASE + "/message/read", bobToken, bobDevice,
            "{\"peer\":\"" + alice + "\",\"beforeId\":999999999,\"tags\":[\""
                + conversationTag + "\"]}"), "按标签标记已读");
        String unreadAfter = httpGet(BASE + "/message/unread/" + alice + "?tags="
            + conversationTag, bobToken, bobDevice);
        check("按标签标记已读后未读归零", unreadAfter.contains("\"data\":0"));

        requireOk(httpPost(BASE + "/message/clear", bobToken, bobDevice,
            "{\"peer\":\"" + alice + "\",\"forBoth\":false,\"tags\":[\""
                + conversationTag + "\"]}"), "按标签清除会话");
        String cleared = httpGet(BASE + "/message/history/" + alice + "?limit=50&tags="
            + conversationTag, bobToken, bobDevice);
        check("按标签清除后历史为空", !cleared.contains(clientMsgId));

        // ---- 无盲引用时的撤回：靠标签定位副本 + token 授权 ----
        String recallResponse = httpPost(BASE + "/message/recall/" + clientMsgId, aliceToken,
            aliceDevice, "{\"recallToken\":\"" + recallToken + "\",\"tags\":[\""
                + conversationTag + "\"]}");
        requireOk(recallResponse, "按标签撤回");
        check("停写盲引用后仍可凭标签 + token 撤回",
            recallResponse.contains("\"recalled\":true"));

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static SenderCertificateView parseCertificate(String response) {
        SenderCertificateView cert = new SenderCertificateView();
        cert.setUsername(str(response, "\"username\":\""));
        cert.setDeviceId(str(response, "\"deviceId\":\""));
        cert.setIdentityKey(str(response, "\"identityKey\":\""));
        cert.setIssuedAt(Long.parseLong(str(response, "\"issuedAt\":")));
        cert.setExpiresAt(Long.parseLong(str(response, "\"expiresAt\":")));
        cert.setKeyId(str(response, "\"keyId\":\""));
        cert.setSignature(str(response, "\"signature\":\""));
        return cert;
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

    private static void uploadIdentityKey(String token, String deviceId, String publicKey)
        throws Exception {
        String body = "{\"publicKey\":\"" + publicKey
            + "\",\"algorithm\":\"EC-P256\",\"password\":\"" + base64(PASSWORD)
            + "\",\"supportsPadding\":true,\"supportsRecallToken\":true"
            + ",\"supportsSenderCert\":true}";
        requireOk(httpPost(BASE + "/key", token, deviceId, body), "上传身份公钥");
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
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
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
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
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
        int idx = json.indexOf(prefix);
        if (idx < 0) {
            throw new IllegalStateException("缺少字段 " + prefix + "：" + json);
        }
        int start = idx + prefix.length();
        if (prefix.endsWith("\"")) {
            return json.substring(start, json.indexOf('"', start));
        }
        int end = start;
        while (end < json.length()
            && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return json.substring(start, end);
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
        while (end < json.length()
            && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return end == start ? null : json.substring(start, end);
    }
}
