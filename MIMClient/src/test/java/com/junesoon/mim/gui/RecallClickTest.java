package com.junesoon.mim.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.utils.HtmlUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 点在「撤回」两个字上时，事件目标是文字节点，没有 class。
 * 按钮自己的点击处理必须从按钮元素往上找消息，不能只认 event.target。
 */
class RecallClickTest {

    @Test
    @DisplayName("撤回按钮把自身交给脚本，而不是依赖点击目标恰好是按钮")
    void recallButtonInvokesElementHandler() throws Exception {
        String html = HtmlUtil.of("msg-1", "我", System.currentTimeMillis(), "你好", false, false, true);
        assertTrue(html.contains("chat.requestRecall(this)"), html);
        String js = new String(Files.readAllBytes(
            Paths.get("src/main/resources/html/chat.js")), StandardCharsets.UTF_8);
        int handler = js.indexOf("chat.requestRecall = function");
        assertTrue(handler >= 0, js);
        int walk = js.indexOf("parentNode", handler);
        int readId = js.indexOf("dataset", handler);
        assertTrue(walk >= 0 && readId >= 0 && walk < readId, js.substring(handler));
        assertTrue(js.contains("chat.recallTargetAt = function"), js);
        assertTrue(js.contains("elementFromPoint"), js);

        String controller = new String(Files.readAllBytes(Paths.get(
            "src/main/java/com/junesoon/mim/gui/ChatController.java")), StandardCharsets.UTF_8);
        assertFalse(controller.contains("window.javaBridge = {}"), controller);
        assertTrue(controller.contains("setMember(\"javaBridge\""), controller);
        assertTrue(controller.contains("chat.recallTargetAt("), controller);
    }
}
