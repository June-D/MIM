package com.junesoon.mim.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * 一次性撤回凭证的哈希。
 *
 * <p>撤回不再依赖「JWT 用户名 → 盲引用」比对：客户端在发送时生成随机
 * recallToken，服务端只保存 SHA-256，撤回时必须提交同一 token。这样即使
 * 攻击者拿到 JWT 和数据库，也无法仅凭发信人引用撤回别人的消息。
 *
 * [INPUT]: 只依赖 JDK 的 MessageDigest，不接触数据库
 * [OUTPUT]: 提供 hash / isValid 两个静态方法
 * [POS]: service/impl/MessageServiceImpl 的撤回授权原语；客户端负责生成随机 token
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
public final class RecallToken {

    /**
     * Base64 或 URL-safe Base64 字符集，长度至少 16。
     * 客户端约定为 32 随机字节 → 44 字符，这里放宽到 128 以兼容其它实现。
     */
    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9+/=_-]{16,128}$");

    private RecallToken() {
    }

    public static boolean isValid(String token) {
        return token != null && FORMAT.matcher(token).matches();
    }

    public static String hash(String token) {
        if (!isValid(token)) {
            throw new IllegalArgumentException("撤回凭证格式不合法");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(value.length * 2);
            for (byte b : value) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
