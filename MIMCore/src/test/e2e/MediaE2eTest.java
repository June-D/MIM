package com.junesoon.mim.crypto;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

/**
 * 加密媒体接口回归：上传 → 下载字节一致 → 他人可下载但不可删除 → 本人删除后 404。
 *
 * <p>服务端把媒体当不透明密文处理；这里验证的只是存储与访问控制，
 * 加密与解密由客户端内核负责（另见 Compose 联调）。
 */
public final class MediaE2eTest {

    private static final String BASE = System.getenv().getOrDefault("MIM_BASE",
        "http://127.0.0.1:8080");
    private static final String PASSWORD = "pass1234";

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        String suffix = String.valueOf(System.currentTimeMillis() % 100000);
        String owner = "mediaa" + suffix;
        String other = "mediab" + suffix;

        register(owner);
        register(other);
        String ownerToken = login(owner);
        String otherToken = login(other);

        byte[] payload = new byte[8192];
        new Random(42).nextBytes(payload);

        String uploaded = request("POST", BASE + "/media", ownerToken, payload, "application/octet-stream");
        String id = extract(uploaded, "id");
        check("上传成功并返回 32 字节随机 id", id != null && id.length() >= 32);

        byte[] downloaded = bytes("GET", BASE + "/media/" + id, ownerToken);
        check("上传者下载的字节完全一致", Arrays.equals(payload, downloaded));

        byte[] byOther = bytes("GET", BASE + "/media/" + id, otherToken);
        check("其它登录用户可下载密文（但仍无密钥）", Arrays.equals(payload, byOther));

        String otherDelete = request("DELETE", BASE + "/media/" + id, otherToken, null, null);
        check("非上传者不能删除", otherDelete.contains("\"data\":false"));

        String ownerDelete = request("DELETE", BASE + "/media/" + id, ownerToken, null, null);
        check("上传者可以删除", ownerDelete.contains("\"data\":true"));

        String afterDelete = request("GET", BASE + "/media/" + id, ownerToken, null, null);
        check("删除后下载返回明确错误", afterDelete.contains("媒体不存在"));

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

    private static String login(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
        String response = request("POST", BASE + "/login/token", null, body.getBytes(StandardCharsets.UTF_8),
            "application/json");
        String token = extract(response, "data");
        if (token == null) {
            throw new IllegalStateException("登录失败：" + response);
        }
        return token;
    }

    private static void register(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD
            + "\",\"agreedTerms\":true,\"termsVersion\":\"2026-12\"}";
        String response = request("POST", BASE + "/register/token", null,
            body.getBytes(StandardCharsets.UTF_8), "application/json");
        if (!response.contains("\"status\":200")) {
            throw new IllegalStateException("注册失败：" + response);
        }
    }

    private static byte[] bytes(String method, String url, String token) throws Exception {
        HttpURLConnection connection = open(method, url, token, null, null);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String request(String method, String url, String token, byte[] body, String type)
        throws Exception {
        HttpURLConnection connection = open(method, url, token, body, type);
        InputStream stream = connection.getResponseCode() >= 400
            ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static HttpURLConnection open(String method, String url, String token, byte[] body,
        String type) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", type);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
        }
        return connection;
    }

    private static String extract(String json, String field) {
        int index = json.indexOf("\"" + field + "\"");
        if (index < 0) {
            return null;
        }
        int colon = json.indexOf(':', index);
        if (colon < 0) {
            return null;
        }
        int start = json.indexOf('"', colon + 1);
        if (start < 0) {
            return null;
        }
        int end = json.indexOf('"', start + 1);
        return end < 0 ? null : json.substring(start + 1, end);
    }
}
