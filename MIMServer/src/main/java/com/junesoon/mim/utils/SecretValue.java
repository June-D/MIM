package com.junesoon.mim.utils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.util.StringUtils;

/**
 * 读取密钥：内联值 > 文件 > 外部命令。
 *
 * <p>外部命令用于接入 Vault/AWS/GCP 等 Secret Manager，例如：
 * {@code vault kv get -field=key secret/mim/master}。命令只在启动读取密钥时执行，
 * stdout 必须是去掉空白后的 32 字节 Base64。
 */
public final class SecretValue {

    private static final int COMMAND_TIMEOUT_SECONDS = 15;

    private SecretValue() {
    }

    public static String load(String inline, String file) {
        return load(inline, file, null);
    }

    public static String load(String inline, String file, String command) {
        if (StringUtils.hasText(inline)) {
            return inline.trim();
        }
        if (StringUtils.hasText(file)) {
            try {
                String value = new String(Files.readAllBytes(Paths.get(file.trim())),
                    StandardCharsets.UTF_8).trim();
                return value.isEmpty() ? null : value;
            } catch (Exception e) {
                throw new IllegalStateException("读取密钥文件失败：" + file, e);
            }
        }
        if (!StringUtils.hasText(command)) {
            return null;
        }
        return runCommand(command.trim());
    }

    private static String runCommand(String command) {
        try {
            boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).startsWith("windows");
            ProcessBuilder builder = windows
                ? new ProcessBuilder("cmd", "/c", command)
                : new ProcessBuilder("/bin/sh", "-c", command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(
                    "密钥命令超时（" + COMMAND_TIMEOUT_SECONDS + "s）");
            }
            String output;
            try (InputStream input = process.getInputStream()) {
                output = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (process.exitValue() != 0) {
                // 不把 stdout 放进异常，避免把密钥/凭据写进日志
                throw new IllegalStateException(
                    "密钥命令执行失败（退出码 " + process.exitValue() + "）");
            }
            if (output.isEmpty()) {
                throw new IllegalStateException("密钥命令没有输出");
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("密钥命令被中断", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("执行密钥命令失败：" + e.getMessage(), e);
        }
    }
}
