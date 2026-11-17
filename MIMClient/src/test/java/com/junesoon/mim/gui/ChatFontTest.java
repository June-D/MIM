package com.junesoon.mim.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 消息气泡走 WebView。JavaFX 8 的 WebKit 读 PingFang SC 会把中文画成乱码，
 * 必须只用冬青黑体。
 */
class ChatFontTest {

    @Test
    @DisplayName("消息区样式不使用苹方")
    void messagePaneDoesNotUsePingFang() throws Exception {
        String css = new String(Files.readAllBytes(
            Paths.get("src/main/resources/html/chat.css")), StandardCharsets.UTF_8);
        Matcher fonts = Pattern.compile("font-family:\\s*([^;]+);").matcher(css);
        assertTrue(fonts.find(), css);
        assertFalse(fonts.group(1).contains("PingFang"), fonts.group(1));
        assertTrue(fonts.group(1).contains("Hiragino Sans GB"), fonts.group(1));
    }
}
