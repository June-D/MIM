package com.junesoon.mim.utils;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Pattern;

/**
 * 把接口失败收成用户能直接看懂的一句话。不带 HTTP 状态码，也不带原始 JSON。
 */
public final class UserVisibleError {

    public static final String USERNAME_TAKEN = "用户名已存在";

    private static final Pattern REGISTER_USERNAME = Pattern.compile("^[a-zA-Z0-9_-]{4,16}$");

    private UserVisibleError() {
    }

    /**
     * 注册页的用户名格式。只有通过这一关才值得去问服务器是否占用。
     */
    public static boolean readyToCheckUsername(String username) {
        return username != null && REGISTER_USERNAME.matcher(username).matches();
    }

    /**
     * HTTP 非 2xx 的响应体。能认出用户名重复时，只返回 {@link #USERNAME_TAKEN}。
     */
    public static String fromTransport(int httpStatus, String body) {
        String message = readMessage(body);
        if (message != null) {
            return humanize(message);
        }
        if (httpStatus == 429) {
            return "操作太频繁，请稍后再试";
        }
        return "请求没有成功，请稍后再试";
    }

    public static String humanize(String message) {
        if (message == null) {
            return "";
        }
        String text = message.trim();
        if (text.contains("用户名重复") || text.contains("用户名已存在")) {
            return USERNAME_TAKEN;
        }
        if (looksTechnical(text)) {
            String nested = readMessage(text);
            if (nested != null && !nested.equals(text)) {
                return humanize(nested);
            }
            return "请求没有成功，请稍后再试";
        }
        return text;
    }

    private static boolean looksTechnical(String text) {
        return text.indexOf('{') >= 0
            || text.indexOf('}') >= 0
            || text.contains("\"status\"")
            || text.matches("\\d{3}\\s.*");
    }

    private static String readMessage(String body) {
        if (body == null) {
            return null;
        }
        int jsonStart = body.indexOf('{');
        if (jsonStart < 0) {
            return null;
        }
        JsonNode node = JacksonUtil.strToJsonNode(body.substring(jsonStart));
        if (node == null || node.get("message") == null || node.get("message").isNull()) {
            return null;
        }
        String message = node.get("message").asText();
        if (message == null || message.isEmpty() || "null".equals(message)) {
            return null;
        }
        return message;
    }
}
