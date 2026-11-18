package com.junesoon.mim.gui;

import com.junesoon.mim.components.ApplicatonStore;
import com.junesoon.mim.crypto.Hkdf;
import com.junesoon.mim.crypto.IdentityException;
import com.junesoon.mim.crypto.SafetyNumber;
import com.junesoon.mim.crypto.TrustChangedException;
import com.junesoon.mim.crypto.TrustStore;
import com.junesoon.mim.model.ChatUser;
import com.junesoon.mim.service.KeyManager;
import com.junesoon.mim.service.MessageService;
import com.junesoon.mim.service.PreKeyManager;
import com.junesoon.mim.utils.AlertUtil;
import com.junesoon.mim.utils.HtmlUtil;
import com.junesoon.mim.websocket.WSClient;
import io.datafx.controller.ViewController;
import io.datafx.controller.flow.context.FXMLViewFlowContext;
import io.datafx.controller.flow.context.ViewFlowContext;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import netscape.javascript.JSObject;

/**
 * 会话窗口。
 *
 * <p>明文只在本地内存中出现，落库与传输的都是密文。
 * 消息通过 HTTP 发送（有明确回执与幂等），通过 WebSocket 接收实时推送与撤回事件。
 */
@Slf4j
@ViewController(value = "/fxml/chat.fxml")
public class ChatController {

    private static final int HISTORY_LIMIT = 50;

    /** 与服务端 message.recall-window-seconds 一致。超过这个时间不再显示撤回。 */
    private static final long RECALL_WINDOW_MS = 120_000L;

    /** 输入框 1 行起步，最多长到 8 行，再多就内部滚动。 */
    private static final int MIN_INPUT_LINES = 1;
    private static final int MAX_INPUT_LINES = 8;

    @FXML
    public StackPane chatRootPane;
    @FXML
    public WebView chatWebView;
    @FXML
    public TextArea messageTextArea;
    @FXML
    public Button sendButton;
    @FXML
    public Button safetyNumberButton;
    @FXML
    public Button clearMessagesButton;
    @FXML
    public Label peerAvatar;
    @FXML
    public Label peerName;
    @FXML
    public VBox transcriptEmpty;
    @FXML
    public VBox composerBox;

    private JSObject js;
    private String promptedRecallId;
    private long promptedRecallAt;
    private ChatUser chatUser;
    private WebEngine engine;

    /** 测量输入内容高度用，不进场景图。 */
    private final Text inputMeasure = new Text();

    /** 发送中：禁止重复提交，同时保持按钮禁用态。 */
    private boolean sending;

    private final MessageService messageService = MessageService.getInstance();

    private Consumer<com.junesoon.mim.model.BaseNotify<?>> pushListener;

    private List<MessageService.DecryptedMessage> loadedHistory = new java.util.ArrayList<>();

    @FXMLViewFlowContext
    private ViewFlowContext flowContext;

    @PostConstruct
    public void init() {
        this.chatUser = resolveChatUser();

        if (chatUser == null) {
            // 显式报错：静默失败会让用户以为"点了发送但没反应"，无从排查
            log.error("ChatController 未取到对端信息，会话不可用");
            Platform.runLater(() -> AlertUtil.show("会话打开失败",
                "未能加载好友信息，请返回列表重新打开。"));
        }
        renderHeader();
        initializeUI();
        initializeEvent();
        loadHistory();
        subscribePush();
        chatRootPane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                markReadUpToLast(loadedHistory);
            }
        });
    }

    /**
     * 取当前会话的对端。
     *
     * <p>优先用 flow 上下文（MainController 显式注册，最可靠）；
     * 退化到父容器的 userData 兼容旧路径。
     */
    private ChatUser resolveChatUser() {
        if (flowContext != null) {
            ChatUser fromContext = flowContext.getRegisteredObject(ChatUser.class);
            if (fromContext != null) {
                return fromContext;
            }
        }
        javafx.scene.Node parent = chatRootPane.getParent();
        Object userData = parent == null ? null : parent.getUserData();
        return userData instanceof ChatUser ? (ChatUser) userData : null;
    }

    @PreDestroy
    public void destroy() {
        if (pushListener != null) {
            WSClient.getInstance().removeListener(pushListener);
            pushListener = null;
        }
    }

    // ==================== 头部 ====================

    private void renderHeader() {
        if (chatUser == null) {
            peerName.setText("会话");
            return;
        }
        String display = chatUser.displayName();
        peerName.setText(display);
        peerAvatar.setText(display == null || display.isEmpty() ? "?"
            : display.substring(0, 1).toUpperCase());
        peerAvatar.setStyle("-fx-background-color: " + colorOf(chatUser.getUsername()) + ";");
        safetyNumberButton.setOnAction(event -> showSafetyNumber());
        clearMessagesButton.setOnAction(event -> confirmClear());
        Tooltip.install(clearMessagesButton, new Tooltip("清除这段对话，可以选择是否连对方一起清除"));
    }

    private String colorOf(String name) {
        String[] palette = {"#4c6ef5", "#12b886", "#f76707", "#ae3ec9", "#1098ad", "#e8590c"};
        int hash = Math.abs(name == null ? 0 : name.hashCode());
        return palette[hash % palette.length];
    }

    /**
     * 展示与对方的安全码。
     *
     * <p>直接在这里实现，不走「查找 MainController」的跨控制器调用：
     * 那种查找依赖某个控制器恰好在全局上下文里注册过，一旦漏掉就是运行时 null，
     * 且失败信息对用户毫无意义（"主界面未就绪"）。安全码只依赖 TrustStore，
     * 本控制器完全够用。
     */
    private void showSafetyNumber() {
        if (chatUser == null) {
            AlertUtil.show("无法核对", "会话信息尚未加载完成，请重新打开该会话。");
            return;
        }
        TrustStore trustStore = KeyManager.getInstance().getTrustStore();
        java.util.Map<String, String> pinned =
            trustStore.getPinnedDevices(chatUser.getUsername());
        if (pinned.isEmpty()) {
            // 还没建立过加密会话：先解释原因，再告诉用户怎么让它出现
            AlertUtil.show("尚无安全码",
                "还没有与 " + chatUser.displayName() + " 建立过加密会话。\n\n"
                    + "发送第一条消息后，对方公钥会被固定在本机，届时可以核对安全码。");
            return;
        }
        StringBuilder body = new StringBuilder("请逐台核对以下设备的安全码：\n\n");
        com.junesoon.mim.crypto.IdentityStore.Identity ownIdentity =
            KeyManager.getInstance().getIdentity();
        String ownKey = ownIdentity == null ? null : ownIdentity.getPublicKeyBase64();
        for (java.util.Map.Entry<String, String> entry : pinned.entrySet()) {
            String fingerprint;
            try {
                fingerprint = Hkdf.sha256Hex(
                    java.util.Base64.getDecoder().decode(entry.getValue()));
            } catch (IllegalArgumentException e) {
                log.warn("已固定公钥格式异常：{}", e.getMessage());
                continue;
            }
            body.append("设备 ").append(entry.getKey()).append('\n')
                .append("指纹：").append(TrustStore.shortFingerprint(fingerprint)).append('\n');
            if (ownKey != null) {
                try {
                    body.append("安全码（双方应相同）：\n")
                        .append(SafetyNumber.of(ownKey, entry.getValue())).append('\n');
                } catch (IllegalArgumentException e) {
                    log.warn("计算安全码失败：{}", e.getMessage());
                }
            }
            body.append('\n');
        }
        body.append("请通过面对面或电话等其他渠道与对方核对。\n")
            .append("任何一台设备的指纹或安全码不一致，都应立即停止沟通。");
        AlertUtil.show("与 " + chatUser.displayName() + " 的安全码", body.toString());
    }

    // ==================== 初始化 ====================

    private void initializeUI() {
        chatWebView.setContextMenuEnabled(false);
        engine = chatWebView.getEngine();
        engine.setJavaScriptEnabled(true);
        chatWebView.addEventFilter(MouseEvent.MOUSE_CLICKED, this::recallFromClick);
        engine.getLoadWorker().stateProperty().addListener((obj, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                js = (JSObject) engine.executeScript("chat");
                JSObject window = (JSObject) engine.executeScript("window");
                window.setMember("javaBridge", new JsBridge());
                renderHistoryIntoEmptyPage();
            }
        });
        String html = getClass().getResource("/html/chat.html").toExternalForm();
        engine.load(html);
        installComposer();
    }

    private void initializeEvent() {
        messageTextArea.setOnKeyPressed(event -> {
            if (event.getCode() != KeyCode.ENTER) {
                return;
            }
            // Shift / Option + Enter：换行。自己插入，不依赖 TextArea 的默认行为，
            // 保证中文输入法和 macOS 上也能稳定换行。
            if (event.isShiftDown() || event.isAltDown()) {
                event.consume();
                insertNewline();
                return;
            }
            // 单独 Enter（含 Cmd/Ctrl+Enter）发送
            event.consume();
            onSendClick();
        });
        sendButton.setOnAction(event -> onSendClick());
    }

    private void insertNewline() {
        int caret = messageTextArea.getCaretPosition();
        messageTextArea.insertText(caret, "\n");
        messageTextArea.positionCaret(caret + 1);
    }

    // ==================== 输入区 ====================

    /**
     * 输入框的现代对话行为：
     * <ul>
     *   <li>1 行起步，随内容增高，最多 8 行后内部滚动；</li>
     *   <li>聚焦时整张卡片描边，而不是给 TextArea 套一个突兀的白框；</li>
     *   <li>内容为空时发送按钮变淡，避免误点。</li>
     * </ul>
     */
    private void installComposer() {
        Tooltip.install(sendButton, new Tooltip("发送（Enter）"));

        messageTextArea.textProperty().addListener((obs, oldText, newText) -> {
            resizeInput();
            updateSendState();
        });
        messageTextArea.widthProperty().addListener((obs, oldWidth, newWidth) -> resizeInput());
        messageTextArea.fontProperty().addListener((obs, oldFont, newFont) -> resizeInput());
        messageTextArea.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (focused) {
                if (!composerBox.getStyleClass().contains("focused")) {
                    composerBox.getStyleClass().add("focused");
                }
            } else {
                composerBox.getStyleClass().remove("focused");
            }
        });

        // 首次布局完成后才拿得到真实宽度
        Platform.runLater(() -> {
            resizeInput();
            messageTextArea.requestFocus();
        });
        updateSendState();
    }

    /** 按内容行数调整输入框高度。宽度变化时也要重算，否则长文本会低估行数。 */
    private void resizeInput() {
        double available = messageTextArea.getWidth();
        if (available <= 0) {
            return;
        }
        Font font = messageTextArea.getFont() == null
            ? Font.font("Hiragino Sans GB", 13.5) : messageTextArea.getFont();
        double lineHeight = measureLineHeight(font);
        inputMeasure.setWrappingWidth(Math.max(40, available - 4));

        String text = messageTextArea.getText();
        int lines = MIN_INPUT_LINES;
        if (text != null && !text.isEmpty()) {
            lines = 0;
            for (String logicalLine : text.split("\n", -1)) {
                inputMeasure.setText(logicalLine.isEmpty() ? " " : logicalLine);
                double height = inputMeasure.getLayoutBounds().getHeight();
                lines += Math.max(1, (int) Math.ceil(height / lineHeight));
            }
            lines = Math.min(MAX_INPUT_LINES, Math.max(MIN_INPUT_LINES, lines));
        }

        // +12 是上下内边距 + 一点余量，避免刚好 8 行时冒出滚动条
        double height = lines * lineHeight + 12;
        messageTextArea.setPrefHeight(height);
        messageTextArea.setMinHeight(height);
        messageTextArea.setMaxHeight(height);
    }

    private double measureLineHeight(Font font) {
        inputMeasure.setFont(font);
        inputMeasure.setWrappingWidth(0);
        inputMeasure.setText("测Ag");
        double singleLine = inputMeasure.getLayoutBounds().getHeight();
        inputMeasure.setText("测Ag\n测Ag");
        double twoLines = inputMeasure.getLayoutBounds().getHeight();
        // 单行 bounds 只有字号那么高，真正的行距要靠两行之差算出来，
        // 否则中文行高会被低估，多行时最后一行会被裁掉。
        double spacing = twoLines - singleLine;
        return spacing > 0 ? spacing : singleLine * 1.35;
    }

    private void updateSendState() {
        String text = messageTextArea.getText();
        boolean empty = text == null || text.trim().isEmpty();
        sendButton.setDisable(empty || sending);
    }

    private void setSending(boolean value) {
        sending = value;
        updateSendState();
    }

    // ==================== 历史消息 ====================

    private void loadHistory() {
        if (chatUser == null) {
            return;
        }
        new Thread(() -> {
            List<MessageService.DecryptedMessage> history =
                messageService.history(chatUser.getUsername(), null, HISTORY_LIMIT);
            Platform.runLater(() -> {
                loadedHistory = history;
                updateTranscriptEmpty();
                renderHistoryIntoEmptyPage();
                markReadUpToLast(history);
            });
        }, "chat-history-loader").start();
    }

    private void updateTranscriptEmpty() {
        boolean empty = loadedHistory.isEmpty();
        transcriptEmpty.setVisible(empty);
        transcriptEmpty.setManaged(empty);
    }

    private void markReadUpToLast(List<MessageService.DecryptedMessage> history) {
        if (chatUser == null || history == null || history.isEmpty()) {
            return;
        }
        long lastId = 0L;
        for (MessageService.DecryptedMessage message : history) {
            Long id = message.getRaw().getId();
            if (!message.isMine() && id != null) {
                lastId = Math.max(lastId, id);
            }
        }
        if (lastId > 0) {
            final long beforeId = lastId;
            new Thread(() -> messageService.markRead(chatUser.getUsername(), beforeId),
                "chat-mark-read").start();
        }
    }

    private void renderHistoryIntoEmptyPage() {
        if (js == null) {
            return;
        }
        js.call("clear");
        for (MessageService.DecryptedMessage message : loadedHistory) {
            appendRendered(message);
        }
    }

    // ==================== 推送 ====================

    private void subscribePush() {
        if (chatUser == null) {
            return;
        }
        pushListener = notify -> {
            Integer type = notify.getType();
            if (type == null) {
                return;
            }
            if (type == WSClient.NOTIFY_NEW_MESSAGE) {
                handleIncomingMessage(notify);
            } else if (type == WSClient.NOTIFY_MESSAGE_RECALLED) {
                handleRecall(notify);
            } else if (type == WSClient.NOTIFY_CONVERSATION_CLEARED) {
                handleCleared(notify);
            } else if (type == WSClient.NOTIFY_READ_RECEIPT) {
                handleRead(notify);
            }
        };
        WSClient.getInstance().addListener(pushListener);
    }

    private void handleIncomingMessage(com.junesoon.mim.model.BaseNotify<?> notify) {
        if (!(notify.getData() instanceof com.junesoon.mim.model.CipherMessage)) {
            return;
        }
        com.junesoon.mim.model.CipherMessage raw =
            (com.junesoon.mim.model.CipherMessage) notify.getData();
        // 先解密：同步消息会在验签后用签名里的 from/to 覆盖服务端给的字段，
        // 再据此判断属于哪个会话，避免服务端把消息挂到错误的联系人下。
        MessageService.DecryptedMessage decrypted = messageService.decrypt(raw);
        if (decrypted == null) {
            return;
        }
        String self = ApplicatonStore.getUsername();
        String peer = self != null && self.equals(raw.getFromUsername())
            ? raw.getToUsername() : raw.getFromUsername();
        if (chatUser.getUsername() == null || !chatUser.getUsername().equals(peer)) {
            return;
        }
        Platform.runLater(() -> {
            loadedHistory.add(decrypted);
            updateTranscriptEmpty();
            appendRendered(decrypted);
            if (!decrypted.isMine() && chatRootPane.getScene() != null) {
                Long id = decrypted.getRaw().getId();
                if (id != null) {
                    markReadUpToLast(loadedHistory);
                }
            }
        });
    }

    private void handleRecall(com.junesoon.mim.model.BaseNotify<?> notify) {
        if (!(notify.getData() instanceof Map)) {
            return;
        }
        Map<?, ?> data = (Map<?, ?>) notify.getData();
        Object idValue = data.get("clientMsgId");
        if (idValue == null) {
            return;
        }
        String clientMsgId = idValue.toString();
        Platform.runLater(() -> markLocalRecalled(clientMsgId));
    }

    private void handleRead(com.junesoon.mim.model.BaseNotify<?> notify) {
        if (!(notify.getData() instanceof Map) || chatUser == null) {
            return;
        }
        Map<?, ?> data = (Map<?, ?>) notify.getData();
        Object peer = data.get("peer");
        if (peer == null || !chatUser.getUsername().equals(String.valueOf(peer))) {
            return;
        }
        Object before = data.get("beforeId");
        if (!(before instanceof Number)) {
            return;
        }
        long beforeId = ((Number) before).longValue();
        Platform.runLater(() -> applyReadThrough(beforeId));
    }

    private void applyReadThrough(long beforeId) {
        for (MessageService.DecryptedMessage message : loadedHistory) {
            if (!message.isMine() || message.isRecalled() || message.getRaw() == null) {
                continue;
            }
            Long id = message.getRaw().getId();
            if (id != null && id <= beforeId) {
                message.getRaw().setReadFlag(true);
            }
        }
        if (js != null) {
            js.call("markReadThrough", beforeId);
        }
    }

    private void handleCleared(com.junesoon.mim.model.BaseNotify<?> notify) {
        if (!(notify.getData() instanceof Map) || chatUser == null) {
            return;
        }
        Object peer = ((Map<?, ?>) notify.getData()).get("peer");
        if (peer == null || !chatUser.getUsername().equals(String.valueOf(peer))) {
            return;
        }
        Platform.runLater(this::wipeTranscript);
    }

    private void markLocalRecalled(String clientMsgId) {
        for (MessageService.DecryptedMessage message : loadedHistory) {
            if (clientMsgId.equals(message.getClientMsgId()) && message.getRaw() != null) {
                message.getRaw().setRecalled(true);
            }
        }
        if (js != null) {
            js.call("markRecalled", clientMsgId);
        }
    }

    private void wipeTranscript() {
        loadedHistory.clear();
        if (js != null) {
            js.call("clear");
        }
        updateTranscriptEmpty();
    }

    private void confirmClear() {
        if (chatUser == null || chatUser.getUsername() == null) {
            return;
        }
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.CONFIRMATION);
        alert.setTitle("清除消息");
        alert.setHeaderText(null);
        alert.setContentText("只清除我这边时，对方仍能看到这些消息。\n"
            + "清除双方后，你和对方的这段对话都会被清空，且无法恢复。");
        javafx.scene.control.ButtonType mineOnly = new javafx.scene.control.ButtonType("只清除我这边");
        javafx.scene.control.ButtonType both = new javafx.scene.control.ButtonType("清除双方消息");
        javafx.scene.control.ButtonType cancel = new javafx.scene.control.ButtonType(
            "取消", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(mineOnly, both, cancel);
        alert.showAndWait().ifPresent(button -> {
            if (button == cancel) {
                return;
            }
            boolean forBoth = button == both;
            String peer = chatUser.getUsername();
            new Thread(() -> {
                try {
                    messageService.clearConversation(peer, forBoth);
                    Platform.runLater(() -> {
                        if (chatUser != null && peer.equals(chatUser.getUsername())) {
                            wipeTranscript();
                        }
                    });
                } catch (IdentityException e) {
                    Platform.runLater(() -> AlertUtil.show("清除失败", e.getMessage()));
                }
            }, "chat-clear").start();
        });
    }

    // ==================== 发送 ====================

    private void onSendClick() {
        if (chatUser == null) {
            return;
        }
        String content = messageTextArea.getText();
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        messageTextArea.clear();
        // 点发送按钮会把焦点留在按钮上，主动还给输入框，便于连续输入
        messageTextArea.requestFocus();
        setSending(true);
        String toUsername = chatUser.getUsername();

        new Thread(() -> {
            try {
                MessageService.DecryptedMessage sent = messageService.send(toUsername, content);
                Platform.runLater(() -> {
                    loadedHistory.add(sent);
                    updateTranscriptEmpty();
                    appendRendered(sent);
                    setSending(false);
                });
            } catch (TrustChangedException e) {
                // 对方公钥变了：给出可操作的确认框，而不是一条"发送失败"了事。
                // 只告警不给出口，用户点掉弹窗后就永远发不出消息。
                log.warn("对方公钥变更，等待用户确认：{}", e.getPeer());
                Platform.runLater(() -> {
                    setSending(false);
                    confirmTrustChange(e, content);
                });
            } catch (IdentityException e) {
                log.warn("发送失败：{}", e.getMessage());
                Platform.runLater(() -> {
                    setSending(false);
                    AlertUtil.show("发送失败", e.getMessage());
                });
            } catch (Exception e) {
                log.error("发送异常", e);
                Platform.runLater(() -> {
                    setSending(false);
                    AlertUtil.show("发送失败", "网络异常，请稍后重试");
                });
            }
        }, "chat-sender").start();
    }

    /**
     * 撤回指定消息（由界面右键菜单触发）。
     */
    public void onRecallClick(String clientMsgId) {
        new Thread(() -> {
            try {
                messageService.recall(clientMsgId);
                Platform.runLater(() -> markLocalRecalled(clientMsgId));
            } catch (IdentityException e) {
                Platform.runLater(() -> AlertUtil.show("撤回失败", e.getMessage()));
            }
        }, "chat-recall").start();
    }

    /**
     * 对方公钥变更的确认框。
     *
     * <p>安全机制只能提示"变了"，判断"是不是真的换设备"必须靠人。
     * 因此这里展示新旧指纹供线下核对，用户确认后才更新本机固定值并重发，
     * 取消则保持原状（继续拒绝发送）。
     */
    private void confirmTrustChange(TrustChangedException e, String pendingContent) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.WARNING);
        alert.setTitle("对方设备身份需要确认");
        alert.setHeaderText("⚠ " + e.getPeer() + " 的设备身份与本机记录不一致");

        StringBuilder details = new StringBuilder();
        for (TrustChangedException.Issue issue : e.getIssues()) {
            if (issue.getReason() == TrustChangedException.Reason.NEW_DEVICE) {
                details.append("新增设备：").append(issue.getDeviceId()).append('\n');
            } else {
                details.append("设备换钥：").append(issue.getDeviceId()).append('\n');
                details.append("原指纹：")
                    .append(TrustStore.shortFingerprint(issue.getPreviousFingerprint()))
                    .append('\n');
            }
            details.append("新指纹：")
                .append(TrustStore.shortFingerprint(issue.getCurrentFingerprint()))
                .append("\n\n");
        }
        details.append("这可能是对方换了设备，也可能是中间人攻击。")
            .append("\n请通过面对面或电话等其他渠道与对方逐台核对上方「新指纹」。")
            .append("\n核对一致再点「确认并发送」，否则请点「取消」。");
        alert.setContentText(details.toString());

        javafx.scene.control.ButtonType accept = new javafx.scene.control.ButtonType(
            "确认并发送", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType cancel = new javafx.scene.control.ButtonType(
            "取消", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(accept, cancel);

        alert.showAndWait().ifPresent(button -> {
            if (button != accept) {
                return;
            }
            setSending(true);
            new Thread(() -> {
                try {
                    PreKeyManager.getInstance().acceptTrustChanges(e);
                    MessageService.DecryptedMessage sent =
                        messageService.send(chatUser.getUsername(), pendingContent);
                    Platform.runLater(() -> {
                        loadedHistory.add(sent);
                        updateTranscriptEmpty();
                        appendRendered(sent);
                        setSending(false);
                    });
                } catch (Exception ex) {
                    log.error("接受公钥变更后重发失败", ex);
                    Platform.runLater(() -> {
                        setSending(false);
                        AlertUtil.show("发送失败", ex.getMessage());
                    });
                }
            }, "chat-resend").start();
        });
    }

    private void appendRendered(MessageService.DecryptedMessage message) {
        if (js == null || chatUser == null) {
            return;
        }
        boolean mine = message.isMine();
        String labelName = mine ? "我" : chatUser.displayName();
        long timestamp = message.getTimestamp() > 0 ? message.getTimestamp() : new Date().getTime();
        boolean canRecall = mine && !message.isRecalled() && message.getTimestamp() > 0
            && System.currentTimeMillis() - message.getTimestamp() <= RECALL_WINDOW_MS;
        Long serverId = message.getRaw() == null ? null : message.getRaw().getId();
        String html = HtmlUtil.of(message.getClientMsgId(), labelName, timestamp,
            message.displayText(), !mine, message.isRecalled(), canRecall, message.isRead(),
            serverId);
        js.call("append", html);
    }

    // ==================== JS 桥接 ====================

    /**
     * 网页里的点击到不了按钮时，用 JavaFX 的鼠标坐标在页面上反查「撤回」。
     */
    private void recallFromClick(MouseEvent event) {
        if (engine == null || event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        double x = event.getX();
        double y = event.getY();
        Platform.runLater(() -> {
            Object found;
            try {
                found = engine.executeScript(String.format(Locale.US,
                    "chat.recallTargetAt(%f,%f)", x, y));
            } catch (Exception ex) {
                log.debug("定位撤回按钮失败：{}", ex.getMessage());
                return;
            }
            if (found == null) {
                return;
            }
            String clientMsgId = String.valueOf(found);
            if (clientMsgId.isEmpty() || "null".equals(clientMsgId)
                || "undefined".equals(clientMsgId)) {
                return;
            }
            promptRecall(clientMsgId);
        });
    }

    /**
     * 暴露给页面的对象。必须挂在 window.javaBridge 上，页面才能调用
     * javaBridge.onRecallRequest。嵌在空对象里时，这个调用到不了 Java。
     */
    public class JsBridge {
        public void onRecallRequest(String clientMsgId) {
            Platform.runLater(() -> promptRecall(clientMsgId));
        }

        public void onSafetyNumberRequest(String ignored) {
            Platform.runLater(ChatController.this::showSafetyNumber);
        }
    }

    private void promptRecall(String clientMsgId) {
        if (clientMsgId == null || clientMsgId.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (clientMsgId.equals(promptedRecallId) && now - promptedRecallAt < 800) {
            return;
        }
        boolean mine = false;
        for (MessageService.DecryptedMessage message : loadedHistory) {
            if (clientMsgId.equals(message.getClientMsgId())) {
                mine = message.isMine();
                break;
            }
        }
        if (!mine) {
            return;
        }
        promptedRecallId = clientMsgId;
        promptedRecallAt = now;
        confirmRecall(clientMsgId);
    }

    private void confirmRecall(String clientMsgId) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.CONFIRMATION);
        if (chatWebView.getScene() != null) {
            alert.initOwner(chatWebView.getScene().getWindow());
        }
        alert.setTitle("撤回消息");
        alert.setHeaderText(null);
        alert.setContentText("确定撤回这条消息吗？撤回后双方都无法再看到原文。");
        javafx.scene.control.ButtonType recall = new javafx.scene.control.ButtonType(
            "撤回", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType cancel = new javafx.scene.control.ButtonType(
            "取消", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(recall, cancel);
        alert.showAndWait().ifPresent(button -> {
            if (button == recall) {
                onRecallClick(clientMsgId);
            }
        });
    }
}
