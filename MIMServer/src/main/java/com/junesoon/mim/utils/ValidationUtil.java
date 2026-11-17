package com.junesoon.mim.utils;

import com.junesoon.mim.exception.UserInvalidException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 输入校验。所有对外接口在进入业务逻辑前先做边界检查。
 */
public final class ValidationUtil {

    /**
     * 允许中文、字母、数字、下划线、点、短横线，2~32 个字符。
     */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_.-]{2,32}$");

    /**
     * 邮箱只做基本格式检查，不验证送达性。找回密码依赖用户填写真实地址。
     */
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static final int EMAIL_MAX_LENGTH = 254;

    private static final int PASSWORD_MIN = 6;
    /**
     * BCrypt 只使用前 72 字节，超过部分会被静默丢弃，因此显式限制。
     */
    private static final int PASSWORD_MAX_BYTES = 72;

    private ValidationUtil() {
    }

    public static void validateUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new UserInvalidException("用户名需为 2~32 位字母、数字、中文、下划线、点或短横线");
        }
    }

    public static void validatePassword(String password) {
        if (password == null || password.isEmpty()) {
            throw new UserInvalidException("密码至少 " + PASSWORD_MIN + " 位");
        }
        // 客户端把原始口令做 Base64 后传输，服务端 BCrypt 的也是这段 Base64。
        // 长度校验必须看解码后的原始长度，否则 4 位口令编码成 8 个字符就能绕过
        // “至少 6 位”。非标准 Base64（例如直接用 curl 发的原始口令）按原长处理。
        int effectiveLength = password.length();
        if (password.length() % 4 == 0) {
            try {
                effectiveLength = java.util.Base64.getDecoder().decode(password).length;
            } catch (IllegalArgumentException ignored) {
                // 不是 Base64，按明文长度校验
            }
        }
        if (effectiveLength < PASSWORD_MIN) {
            throw new UserInvalidException("密码至少 " + PASSWORD_MIN + " 位");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            throw new UserInvalidException("密码过长");
        }
    }

    /**
     * 可选邮箱：为空表示不填写；非空时只做格式与长度检查。
     */
    public static void validateEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            return;
        }
        String value = email.trim();
        if (value.length() > EMAIL_MAX_LENGTH || !EMAIL_PATTERN.matcher(value).matches()) {
            throw new UserInvalidException("邮箱格式不正确");
        }
    }

    public static void requireText(String text, String field, int maxLength) {
        if (text == null || text.trim().isEmpty()) {
            throw new UserInvalidException(field + "不能为空");
        }
        if (text.length() > maxLength) {
            throw new UserInvalidException(field + "过长");
        }
    }
}
