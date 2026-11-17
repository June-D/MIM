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
        if (password == null || password.length() < PASSWORD_MIN) {
            throw new UserInvalidException("密码至少 " + PASSWORD_MIN + " 位");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            throw new UserInvalidException("密码过长");
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
