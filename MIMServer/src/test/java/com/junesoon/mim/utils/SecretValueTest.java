package com.junesoon.mim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretValueTest {

    @Test
    @DisplayName("内联值优先，文件作为 Docker/K8s Secret 挂载的备选")
    void inlineAndFile(@TempDir Path dir) throws Exception {
        Path keyFile = dir.resolve("key");
        Files.write(keyFile, "from-file\n".getBytes(StandardCharsets.UTF_8));

        assertEquals("inline", SecretValue.load(" inline ", keyFile.toString()));
        assertEquals("from-file", SecretValue.load("", keyFile.toString()));
        assertNull(SecretValue.load("", ""));
    }

    @Test
    @DisplayName("外部命令作为 Secret Manager 读取方式，内联/文件优先")
    void commandFallback(@TempDir Path dir) throws Exception {
        Path keyFile = dir.resolve("key");
        Files.write(keyFile, "from-file\n".getBytes(StandardCharsets.UTF_8));

        assertEquals("from-command", SecretValue.load("", "", "printf 'from-command\\n'"));
        assertEquals("inline", SecretValue.load("inline", "", "printf 'from-command'"));
        assertEquals("from-file", SecretValue.load("", keyFile.toString(),
            "printf 'from-command'"));
        assertNull(SecretValue.load("", "", ""));
        assertThrows(IllegalStateException.class,
            () -> SecretValue.load("", "", "exit 7"));
    }

    @Test
    @DisplayName("密钥文件不可读时明确失败，而不是静默降级")
    void unreadableFileFails(@TempDir Path dir) {
        assertThrows(IllegalStateException.class,
            () -> SecretValue.load("", dir.resolve("missing").toString()));
    }
}
