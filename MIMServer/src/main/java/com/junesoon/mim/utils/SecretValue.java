package com.junesoon.mim.utils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.springframework.util.StringUtils;

/**
 * 读取内联或文件形式提供的密钥。环境变量优先，文件用于 Docker/K8s Secret 挂载。
 */
public final class SecretValue {

    private SecretValue() {
    }

    public static String load(String inline, String file) {
        if (StringUtils.hasText(inline)) {
            return inline.trim();
        }
        if (!StringUtils.hasText(file)) {
            return null;
        }
        try {
            String value = new String(Files.readAllBytes(Paths.get(file.trim())),
                StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            throw new IllegalStateException("读取密钥文件失败：" + file, e);
        }
    }
}
