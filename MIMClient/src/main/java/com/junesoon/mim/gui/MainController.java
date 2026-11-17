package com.junesoon.mim.gui;

import com.junesoon.mim.components.ApplicatonStore;
import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.components.Request;
import com.junesoon.mim.feign.AddContactRequest;
import com.junesoon.mim.feign.ChatFeign;
import com.junesoon.mim.feign.RenameRequest;
import com.junesoon.mim.model.ChatContact;
import com.junesoon.mim.model.ChatUser;
import com.junesoon.mim.model.FriendRequest;
import com.junesoon.mim.model.UserSummary;
import com.junesoon.mim.service.MessageService;
import com.junesoon.mim.service.SessionManager;
import com.junesoon.mim.utils.AlertUtil;
import com.junesoon.mim.crypto.Hkdf;
import com.junesoon.mim.crypto.TrustStore;
import com.junesoon.mim.service.KeyManager;
import com.junesoon.mim.websocket.WSClient;
import io.datafx.controller.ViewController;
import io.datafx.controller.flow.Flow;
import io.datafx.controller.flow.FlowException;
import io.datafx.controller.flow.FlowHandler;
import io.datafx.controller.flow.container.AnimatedFlowContainer;
import io.datafx.controller.flow.container.ContainerAnimations;
import io.datafx.controller.flow.context.ActionHandler;
import io.datafx.controller.flow.context.FlowActionHandler;
import io.datafx.controller.util.VetoException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 主界面：左侧会话栏 + 右侧当前会话。
 *
 * <p>这是"用户能不能用起来"的核心页面，承载三件事：
 * <ol>
 *   <li>看到自己的好友列表与在线状态；</li>
 *   <li>搜索用户并申请好友，对方同意后才会出现在列表里；</li>
 *   <li>点开某个好友开始加密聊天。</li>
 * </ol>
 *
 * <p>界面用代码构建而非 FXML：会话条目需要按未读数、在线状态动态变化，
 * 用 FXML 会变成大量 fx:id + 手动绑定，反而更啰嗦。
 */
@Slf4j
@ViewController("/fxml/main.fxml")
public class MainController {

    @ActionHandler
    private FlowActionHandler actionHandler;

    @FXML
    private StackPane rootPane;
    @FXML
    private Label meAvatar;
    @FXML
    private Label meName;
    @FXML
    private Label meStatus;
    @FXML
    private Button addFriendButton;
    @FXML
    private Button accountMenuButton;    @FXML
    private TextField searchField;
    @FXML
    private HBox requestBar;
    @FXML
    private Label requestBadge;
    @FXML
    private VBox conversationList;
    @FXML
    private VBox emptyState;
    @FXML
    private StackPane contentArea;
    @FXML
    private VBox welcomeState;
    @FXML
    private StackPane overlayLayer;

    /**
     * 好友列表（平铺，分组暂不展开——只有"我的好友"一个分组时分组是噪音）。
     */
    private final List<ChatUser> contacts = new ArrayList<>();

    /**
     * 已打开的会话视图。key 为对方用户名，避免重复点击时重建。
     */
    private final Map<String, StackPane> openViews = new HashMap<>();

    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "contact-poller");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 搜索结果面板是否打开（用于 ESC 关闭）。
     */
    private StackPane searchPanel;

    /**
     * 好友申请面板里的列表。非空表示面板正打开，轮询到新申请时就地刷新。
     */
    private VBox requestListBox;

    /**
     * 我发出、尚未被同意的用户名。搜索结果里这些人显示「已申请」。
     */
    private final Set<String> outgoingPending = new HashSet<>();

    /**
     * 当前打开的会话，用来给左侧条目一个选中底色。
     */
    private String selectedPeer;

    /**
     * WebSocket 推送监听器。必须在 destroy 里移除，否则切账号后会泄漏。
     */
    private java.util.function.Consumer<com.junesoon.mim.model.BaseNotify<?>> pushListener;

    @PostConstruct
    public void init() {
        renderMe();
        bindEvents();
        loadContacts();
        loadRequests();
        startPolling();
        subscribePush();
        updateEmptyState();
    }

    /**
     * 订阅 WebSocket 推送，实现<b>即时</b>新消息提醒。
     *
     * <p>不能只依赖 5 秒轮询：轮询有延迟，而且窗口在后台时用户根本注意不到。
     * 这里做两层提醒：
     * <ul>
     *   <li>会话列表红点立即更新（即使当前没打开该会话）；</li>
     *   <li>窗口不在前台、且不是当前会话时，闪一下任务栏图标（Dock 跳动），
     *       这是 macOS 上成本最低、最不打扰的提醒方式。</li>
     * </ul>
     */
    private void subscribePush() {
        pushListener = notify -> {
            if (notify.getType() == null) {
                return;
            }
            int type = notify.getType();
            if (type == WSClient.NOTIFY_FRIEND_REQUEST
                || type == WSClient.NOTIFY_FRIEND_ACCEPTED
                || type == WSClient.NOTIFY_FRIEND_REJECTED) {
                Platform.runLater(() -> {
                    loadContacts();
                    loadRequests();
                });
                return;
            }
            if (type != WSClient.NOTIFY_NEW_MESSAGE) {
                return;
            }
            if (!(notify.getData() instanceof com.junesoon.mim.model.CipherMessage)) {
                return;
            }
            com.junesoon.mim.model.CipherMessage raw =
                (com.junesoon.mim.model.CipherMessage) notify.getData();
            String self = ApplicatonStore.getUsername();
            // 自己发出的多端同步副本不提醒
            String peer = self != null && self.equals(raw.getFromUsername())
                ? raw.getToUsername() : raw.getFromUsername();
            if (peer == null || peer.equals(self)) {
                return;
            }
            Platform.runLater(() -> {
                int before = unreadOf(peer);
                // 立刻刷新红点，不等下一轮轮询
                loadContacts();
                // 只有当这条会话没被打开时才提示，避免打扰正在聊天的人
                if (!isConversationOpen(peer)) {
                    requestAttention(peer);
                }
                log.debug("收到 {} 的新消息，未读 {} -> {}", peer, before, before + 1);
            });
        };
        WSClient.getInstance().addListener(pushListener);
    }

    /**
     * 该会话是否正在前台显示。
     */
    private boolean isConversationOpen(String peer) {
        StackPane view = openViews.get(peer);
        if (view == null) {
            return false;
        }
        return contentArea.getChildren().contains(view) && windowIsFocused();
    }

    private boolean windowIsFocused() {
        javafx.stage.Window window = rootPane.getScene() == null
            ? null : rootPane.getScene().getWindow();
        return window != null && window.isFocused();
    }

    /**
     * 用 Dock 图标跳动提醒。比弹系统通知更轻，也无需申请通知权限。
     */
    private void requestAttention(String peer) {
        javafx.stage.Window window = rootPane.getScene() == null
            ? null : rootPane.getScene().getWindow();
        if (window != null) {
            window.requestFocus();
        }
        // JavaFX 没有直接的 Dock 跳动 API，这里退化为标题栏提示，
        // 用户回到窗口就能看到是谁发来的。
        updateWindowTitle();
    }

    /**
     * 标题栏显示总未读数，任务栏/Dock 悬浮时也能看到。
     */
    private void updateWindowTitle() {
        javafx.stage.Window window = rootPane.getScene() == null
            ? null : rootPane.getScene().getWindow();
        // setTitle 只在 Stage 上，Window 没有；用类型判断避免强转异常
        if (!(window instanceof javafx.stage.Stage)) {
            return;
        }
        int total = 0;
        for (ChatUser user : contacts) {
            if (Boolean.TRUE.equals(user.getHasUnread())) {
                Integer count = user.getUnreadCount();
                total += count == null ? 1 : Math.max(count, 1);
            }
        }
        ((javafx.stage.Stage) window).setTitle(total > 0
            ? "MIM · 端到端加密即时通讯（" + total + " 条未读）"
            : "MIM · 端到端加密即时通讯");
    }

    private int unreadOf(String peer) {
        for (ChatUser user : contacts) {
            if (peer.equals(user.getUsername())) {
                return user.getUnreadCount() == null ? 0 : user.getUnreadCount();
            }
        }
        return 0;
    }

    @PreDestroy
    public void destroy() {
        // @PreDestroy 在「离开本控制器」时调用，可能是用户点登出，也可能是
        // 其它导航。这里只做资源清理，<b>绝不主动 navigate</b>——
        // 否则会与调用方（doLogout）的 navigate 形成递归导航，界面直接卡死。
        if (pushListener != null) {
            try {
                WSClient.getInstance().removeListener(pushListener);
            } catch (Exception e) {
                log.debug("移除推送监听失败：{}", e.getMessage());
            }
            pushListener = null;
        }
        poller.shutdownNow();
        SessionManager.logout();
    }

    // ==================== 顶部我的信息 ====================

    private void renderMe() {
        String username = ApplicatonStore.getUsername();
        if (username == null) {
            return;
        }
        meName.setText(username);
        meAvatar.setText(initialOf(username));
        meAvatar.setStyle("-fx-background-color: " + colorOf(username) + ";");
        meStatus.setText("端到端加密");
    }

    /**
     * 头像首字：取第一个字符，中文也可用。
     */
    private String initialOf(String name) {
        return name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
    }

    /**
     * 按用户名稳定生成头像底色。同一用户每次登录颜色一致，便于辨认。
     */
    private String colorOf(String name) {
        String[] palette = {"#243056", "#3d4c6e", "#2f4a4a", "#3e3a55", "#2c4558", "#3a3f4b"};
        int hash = Math.abs(name == null ? 0 : name.hashCode());
        return palette[hash % palette.length];
    }

    // ==================== 事件绑定 ====================

    private void bindEvents() {
        Tooltip.install(addFriendButton, new Tooltip("添加好友"));
        Tooltip.install(accountMenuButton, new Tooltip("账号"));
        addFriendButton.setOnMouseClicked(event -> showSearchPanel());
        requestBar.setOnMouseClicked(event -> showRequestPanel());
        accountMenuButton.setOnMouseClicked(this::showAccountMenu);

        // 输入即本地筛选；回车则搜索服务器上的新用户
        searchField.textProperty().addListener((obs, oldValue, newValue) -> filterLocal(newValue));
        searchField.setOnAction(event -> {
            String keyword = searchField.getText();
            if (keyword != null && !keyword.trim().isEmpty()) {
                searchUsers(keyword.trim());
            }
        });
    }

    /**
     * 账号菜单：切换账号 / 退出登录。
     *
     * <p>「切换账号」与「退出登录」行为一致（都回登录页），
     * 但文案分开是因为用户的心理模型不同：切换是"我要用另一个号"，
     * 退出是"我用完了"。给同一个动作两种入口，比强迫用户理解术语更友好。
     */
    private void showAccountMenu(javafx.scene.input.MouseEvent event) {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();

        javafx.scene.control.MenuItem switchAccount =
            new javafx.scene.control.MenuItem("切换账号");
        switchAccount.setOnAction(e -> confirmLogout("切换账号", true));

        javafx.scene.control.MenuItem deviceCode =
            new javafx.scene.control.MenuItem("本机设备安全码");
        deviceCode.setOnAction(e -> showOwnDeviceCode());

        javafx.scene.control.MenuItem changePassword =
            new javafx.scene.control.MenuItem("修改登录密码");
        changePassword.setOnAction(e -> showChangePasswordDialog());

        javafx.scene.control.MenuItem logout =
            new javafx.scene.control.MenuItem("退出登录");
        logout.setOnAction(e -> confirmLogout("退出登录", false));

        menu.getItems().addAll(deviceCode, changePassword, switchAccount, logout);
        menu.show(accountMenuButton, event.getScreenX(), event.getScreenY());
    }

    /**
     * 修改登录密码：服务端校验并让其它设备下线，本地用新口令重新加密密钥。
     */
    private void showChangePasswordDialog() {
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog =
            new javafx.scene.control.Dialog<>();
        dialog.setTitle("修改登录密码");
        dialog.setHeaderText("修改后其它设备需要重新登录；本机将用新口令重新加密本地密钥。");

        javafx.scene.control.PasswordField oldField =
            new javafx.scene.control.PasswordField();
        javafx.scene.control.PasswordField newField =
            new javafx.scene.control.PasswordField();
        javafx.scene.control.PasswordField confirmField =
            new javafx.scene.control.PasswordField();
        oldField.setPromptText("当前密码");
        newField.setPromptText("新密码（至少 6 位）");
        confirmField.setPromptText("再次输入新密码");

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.add(new javafx.scene.control.Label("当前密码"), 0, 0);
        grid.add(oldField, 1, 0);
        grid.add(new javafx.scene.control.Label("新密码"), 0, 1);
        grid.add(newField, 1, 1);
        grid.add(new javafx.scene.control.Label("确认新密码"), 0, 2);
        grid.add(confirmField, 1, 2);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(
            javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);

        java.util.Optional<javafx.scene.control.ButtonType> result = dialog.showAndWait();
        if (!result.isPresent() || result.get() != javafx.scene.control.ButtonType.OK) {
            return;
        }
        String oldPassword = oldField.getText();
        String newPassword = newField.getText();
        String confirm = confirmField.getText();
        if (newPassword == null || newPassword.length() < 6) {
            AlertUtil.show("不能修改", "新密码至少 6 位。");
            return;
        }
        if (!newPassword.equals(confirm)) {
            AlertUtil.show("不能修改", "两次输入的新密码不一致。");
            return;
        }
        if (newPassword.equals(oldPassword)) {
            AlertUtil.show("不能修改", "新密码不能与当前密码相同。");
            return;
        }
        new Thread(() -> {
            try {
                com.junesoon.mim.service.PasswordChangeService.change(oldPassword, newPassword);
                Platform.runLater(() -> AlertUtil.show("修改成功",
                    "登录密码已更新；其它设备需要重新登录。"));
            } catch (Exception e) {
                log.error("修改密码失败", e);
                Platform.runLater(() -> AlertUtil.show("修改失败", String.valueOf(e.getMessage())));
            }
        }, "password-change").start();
    }

    /**
     * 展示本机设备标识与身份公钥指纹，供另一台设备转发历史前逐字核对。
     */
    private void showOwnDeviceCode() {
        com.junesoon.mim.crypto.IdentityStore.Identity identity =
            KeyManager.getInstance().getIdentity();
        if (identity == null) {
            AlertUtil.show("无法显示", "本地加密身份尚未初始化，请重新登录。");
            return;
        }
        String fingerprint;
        try {
            fingerprint = TrustStore.shortFingerprint(Hkdf.sha256Hex(
                java.util.Base64.getDecoder().decode(identity.getPublicKeyBase64())));
        } catch (IllegalArgumentException e) {
            AlertUtil.show("无法显示", "本机身份公钥格式异常，请重新生成设备密钥。");
            return;
        }
        AlertUtil.show("本机设备安全码",
            "设备标识：\n" + KeyManager.getInstance().deviceId()
                + "\n\n安全码：\n" + fingerprint
                + "\n\n另一台设备向你请求同步历史时，应显示相同安全码。"
                + "\n不一致请立即拒绝。");
    }

    /**
     * 登出前确认。切换账号时额外说明密钥处理方式，避免用户误以为会丢历史消息。
     */
    private void confirmLogout(String title, boolean switching) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(switching
            ? "将退出 " + ApplicatonStore.getUsername() + "，返回登录页选择另一个账号。\n\n"
                + "本机密钥仍会保留，切回来时可以继续查看历史消息。"
            : "确定退出当前账号吗？\n\n本机密钥仍会保留，下次登录可继续查看历史消息。");
        alert.showAndWait().ifPresent(button -> {
            if (button == javafx.scene.control.ButtonType.OK) {
                doLogout();
            }
        });
    }

    /**
     * 执行登出并回到登录页。
     *
     * <p>导航放在这里而不是 {@code destroy()}：登出是「主动离开」的发起方，
     * 由它负责跳转；destroy 只做清理，避免两边都 navigate 造成递归卡死。
     */
    private void doLogout() {
        log.info("用户 {} 主动登出", ApplicatonStore.getUsername());
        // 先回到登录页，再清理：navigate 会触发本控制器的 destroy，
        // 若先清理，导航时读到的状态已是空的，容易报错。
        try {
            actionHandler.navigate(LoginController.class);
        } catch (VetoException | FlowException e) {
            log.error("返回登录页失败", e);
            AlertUtil.show("登出异常", "无法返回登录页，请重启客户端。");
        }
    }

    // ==================== 联系人加载 ====================

    private void loadContacts() {
        poller.execute(() -> {
            try {
                List<ChatContact> groups = Request.connector(ChatFeign.class).getContacts();
                List<ChatUser> flat = new ArrayList<>();
                if (groups != null) {
                    for (ChatContact group : groups) {
                        if (group.getUsers() != null) {
                            flat.addAll(group.getUsers());
                        }
                    }
                }
                Platform.runLater(() -> {
                    contacts.clear();
                    contacts.addAll(flat);
                    renderContacts(contacts);
                    updateEmptyState();
                });
            } catch (Exception e) {
                log.warn("加载联系人失败：{}", e.getMessage());
            }
        });
    }

    /**
     * 定时刷新在线状态与未读数。间隔 5 秒：比秒级轮询省资源，
     * 对"看到对方在线"这个需求足够及时。
     */
    private void startPolling() {
        poller.scheduleWithFixedDelay(() -> {
            try {
                List<ChatContact> groups = Request.connector(ChatFeign.class).getContacts();
                List<ChatUser> flat = new ArrayList<>();
                if (groups != null) {
                    for (ChatContact group : groups) {
                        if (group.getUsers() != null) {
                            flat.addAll(group.getUsers());
                        }
                    }
                }
                // 未读数逐个查；好友数量通常在几十的量级，可接受
                for (ChatUser user : flat) {
                    try {
                        long unread = MessageService.getInstance().unread(user.getUsername());
                        user.setUnreadCount((int) Math.min(unread, 999));
                        user.setHasUnread(unread > 0);
                    } catch (Exception ignored) {
                        user.setHasUnread(false);
                        user.setUnreadCount(0);
                    }
                }
                Platform.runLater(() -> {
                    boolean changed = !sameContacts(contacts, flat);
                    if (changed) {
                        contacts.clear();
                        contacts.addAll(flat);
                        renderContacts(contacts);
                        updateEmptyState();
                    }
                });
            } catch (Exception e) {
                log.debug("刷新联系人失败：{}", e.getMessage());
            }
            fetchRequests();
        }, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * 结构相同就跳过重绘，避免每 5 秒闪一次。
     */
    private boolean sameContacts(List<ChatUser> a, List<ChatUser> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            ChatUser left = a.get(i);
            ChatUser right = b.get(i);
            if (!safeEquals(left.getUsername(), right.getUsername())
                || !safeEquals(left.getOnline(), right.getOnline())
                || !safeEquals(left.getHasUnread(), right.getHasUnread())
                // 未读条数变化也要重绘，否则红点数字不会更新
                || !safeEquals(left.getUnreadCount(), right.getUnreadCount())
                || !safeEquals(left.getLabelName(), right.getLabelName())) {
                return false;
            }
        }
        return true;
    }

    private boolean safeEquals(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    // ==================== 渲染会话列表 ====================

    private void renderContacts(List<ChatUser> users) {
        conversationList.getChildren().clear();
        for (ChatUser user : users) {
            conversationList.getChildren().add(buildContactRow(user));
        }
        // 列表变了顺带更新标题栏未读数，让窗口在后台时也能从任务栏看到
        updateWindowTitle();
    }

    /**
     * 构造一条好友条目：头像 + 名字/状态 + 未读徽标。
     */
    private HBox buildContactRow(ChatUser user) {
        HBox row = new HBox();
        row.getStyleClass().add("conv-row");
        row.setUserData(user.getUsername());
        if (user.getUsername().equals(selectedPeer)) {
            row.getStyleClass().add("conv-row-selected");
        }
        row.setAlignment(Pos.CENTER_LEFT);

        Label avatar = new Label(initialOf(user.displayName()));
        avatar.getStyleClass().add("avatar-sm");
        avatar.setStyle("-fx-background-color: " + colorOf(user.getUsername()) + ";");

        VBox textBox = new VBox(2);
        HBox.setHgrow(textBox, Priority.ALWAYS);
        Label name = new Label(user.displayName());
        name.getStyleClass().add("conv-name");

        HBox statusLine = new HBox(6);
        statusLine.setAlignment(Pos.CENTER_LEFT);
        Region dot = new Region();
        dot.getStyleClass().addAll("presence-dot",
            Boolean.TRUE.equals(user.getOnline()) ? "presence-online" : "presence-offline");
        Label status = new Label(Boolean.TRUE.equals(user.getOnline()) ? "在线" : "离线");
        status.getStyleClass().add("conv-preview");
        statusLine.getChildren().addAll(dot, status);

        textBox.getChildren().addAll(name, statusLine);
        row.getChildren().addAll(avatar, textBox);

        if (Boolean.TRUE.equals(user.getHasUnread())) {
            // 显示具体条数而不是一个圆点：条数能让用户判断"是否值得现在点开"
            int unread = user.getUnreadCount();
            Label badge = new Label(unread > 99 ? "99+" : String.valueOf(Math.max(unread, 1)));
            badge.getStyleClass().add("unread-badge");
            row.getChildren().add(badge);
        }

        row.setOnMouseClicked(event -> openConversation(user));

        // 右键菜单：管理好友关系。没有它，加错了也删不掉。
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem rename = new javafx.scene.control.MenuItem("修改备注");
        rename.setOnAction(event -> promptRename(user));
        javafx.scene.control.MenuItem remove = new javafx.scene.control.MenuItem("删除好友");
        remove.setOnAction(event -> confirmRemove(user));
        menu.getItems().addAll(rename, remove);
        row.setOnContextMenuRequested(event -> menu.show(row, event.getScreenX(), event.getScreenY()));

        return row;
    }

    /**
     * 给当前会话一条选中底色。选中只靠底色，不加左侧色条。
     */
    private void markSelected(String username) {
        for (javafx.scene.Node node : conversationList.getChildren()) {
            if (!(node instanceof HBox)) {
                continue;
            }
            HBox row = (HBox) node;
            boolean on = username != null && username.equals(row.getUserData());
            if (on && !row.getStyleClass().contains("conv-row-selected")) {
                row.getStyleClass().add("conv-row-selected");
            } else if (!on) {
                row.getStyleClass().remove("conv-row-selected");
            }
        }
    }

    /**
     * 修改备注名：输入框预填当前名字，确定后刷新列表。
     */
    private void promptRename(ChatUser user) {
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(
            user.displayName());
        dialog.setTitle("修改备注");
        dialog.setHeaderText(null);
        dialog.setContentText(user.getUsername() + " 的备注名：");
        dialog.showAndWait().ifPresent(label -> {
            String trimmed = label == null ? "" : label.trim();
            poller.execute(() -> {
                try {
                    Request.connector(ChatFeign.class).renameContact(user.getUsername(),
                        new RenameRequest(trimmed));
                    Platform.runLater(this::loadContacts);
                } catch (Exception e) {
                    Platform.runLater(() -> AlertUtil.show("修改失败", e.getMessage()));
                }
            });
        });
    }

    /**
     * 删除好友：这是不可撤销的，必须二次确认。
     */
    private void confirmRemove(ChatUser user) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.CONFIRMATION);
        alert.setTitle("删除好友");
        alert.setHeaderText(null);
        alert.setContentText("确定删除好友 " + user.displayName()
            + " 吗？\n双方都会从对方的好友列表中移除，历史消息仍会保留在你的设备上。");
        alert.showAndWait().ifPresent(button -> {
            if (button == javafx.scene.control.ButtonType.OK) {
                poller.execute(() -> {
                    try {
                        Request.connector(ChatFeign.class).removeContact(user.getUsername());
                        Platform.runLater(() -> {
                            hideConversation(user.getUsername());
                            loadContacts();
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> AlertUtil.show("删除失败", e.getMessage()));
                    }
                });
            }
        });
    }

    /**
     * 删除好友时同步关掉已打开的会话，避免留下孤立视图。
     */
    private void hideConversation(String peer) {
        StackPane view = openViews.remove(peer);
        if (view != null) {
            contentArea.getChildren().remove(view);
        }
        if (peer.equals(selectedPeer)) {
            selectedPeer = null;
        }
        if (openViews.isEmpty()) {
            welcomeState.setVisible(true);
            welcomeState.setManaged(true);
        }
    }

    private void updateEmptyState() {
        boolean empty = contacts.isEmpty();
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
    }

    /**
     * 本地筛选：已有好友里按名字过滤，不发请求，输入即响应。
     */
    private void filterLocal(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            renderContacts(contacts);
            return;
        }
        String needle = keyword.trim().toLowerCase();
        List<ChatUser> matched = new ArrayList<>();
        for (ChatUser user : contacts) {
            String name = user.displayName();
            if (name != null && name.toLowerCase().contains(needle)) {
                matched.add(user);
            }
        }
        renderContacts(matched);
    }

    // ==================== 打开会话 ====================

    private void openConversation(ChatUser user) {
        if (user == null || user.getUsername() == null) {
            return;
        }
        hideOverlay();
        selectedPeer = user.getUsername();
        markSelected(selectedPeer);
        StackPane existing = openViews.get(user.getUsername());
        if (existing != null) {
            contentArea.getChildren().setAll(existing);
            welcomeState.setVisible(false);
            welcomeState.setManaged(false);
            user.setHasUnread(false);
            return;
        }

        try {
            Flow flow = new Flow(ChatController.class);
            FlowHandler handler = flow.createHandler();
            // 把对端信息传给 ChatController。
            // 用 flow 上下文而不是"父容器的 userData"：后者受 DataFX 内部布局层次影响，
            // 一旦中间多包了一层就会取到 null，导致发送按钮静默失效（已实际踩过）。
            handler.getFlowContext().register(user, ChatUser.class);
            StackPane view = handler.start(
                new AnimatedFlowContainer(Duration.millis(180), ContainerAnimations.FADE));
            view.getStyleClass().add("chat-view");
            openViews.put(user.getUsername(), view);
            contentArea.getChildren().setAll(view);
            welcomeState.setVisible(false);
            welcomeState.setManaged(false);
            user.setHasUnread(false);
            log.info("打开与 {} 的会话", user.getUsername());
        } catch (FlowException e) {
            log.error("打开会话失败", e);
            AlertUtil.show("打开会话失败", e.getMessage());
        }
    }

    // ==================== 搜索并添加好友 ====================

    @FXML
    private void showSearchPanel() {
        boolean searchOpen = requestListBox == null && searchPanel != null
            && overlayLayer.getChildren().contains(searchPanel);
        if (searchOpen) {
            hideOverlay();
            return;
        }
        requestListBox = null;
        VBox panel = new VBox();
        panel.getStyleClass().add("search-panel");
        panel.setMaxWidth(360);
        panel.setMaxHeight(Region.USE_PREF_SIZE);

        Label title = new Label("添加好友");
        title.getStyleClass().add("panel-title");

        TextField input = new TextField();
        input.getStyleClass().add("search-field");
        input.setPromptText("输入对方的用户名");

        VBox resultBox = new VBox(4);
        Label hint = new Label("输入用户名后回车搜索");
        hint.getStyleClass().add("empty-hint");
        resultBox.getChildren().add(hint);

        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);
        Button cancel = new Button("取消");
        cancel.getStyleClass().add("btn-quiet");
        cancel.setOnAction(event -> hideOverlay());
        actions.getChildren().add(cancel);

        panel.getChildren().addAll(title, input, resultBox, actions);

        StackPane wrap = new StackPane(panel);
        wrap.setPickOnBounds(true);
        wrap.setOnMouseClicked(event -> {
            if (event.getTarget() == wrap) {
                hideOverlay();
            }
        });

        input.setOnAction(event -> {
            String keyword = input.getText();
            if (keyword == null || keyword.trim().isEmpty()) {
                return;
            }
            renderSearchResults(resultBox, keyword.trim());
        });

        overlayLayer.getChildren().setAll(wrap);
        overlayLayer.setVisible(true);
        overlayLayer.setManaged(true);
        searchPanel = wrap;
        Platform.runLater(input::requestFocus);
    }

    private void hideOverlay() {
        overlayLayer.setVisible(false);
        overlayLayer.setManaged(false);
        overlayLayer.getChildren().clear();
        searchPanel = null;
        requestListBox = null;
    }

    /**
     * 搜索并渲染结果。每个结果带「申请」，对方同意后才会成为好友。
     */
    private void renderSearchResults(VBox resultBox, String keyword) {
        resultBox.getChildren().clear();
        Label loading = new Label("搜索中…");
        loading.getStyleClass().add("empty-hint");
        resultBox.getChildren().add(loading);

        poller.execute(() -> {
            try {
                ObjectRestResponse<List<UserSummary>> response =
                    Request.connector(ChatFeign.class).search(keyword);
                List<UserSummary> results = (response == null || response.getData() == null)
                    ? new ArrayList<>() : response.getData();
                Platform.runLater(() -> {
                    resultBox.getChildren().clear();
                    if (results.isEmpty()) {
                        Label none = new Label("没有找到可添加的用户");
                        none.getStyleClass().add("empty-hint");
                        resultBox.getChildren().add(none);
                        return;
                    }
                    for (UserSummary summary : results) {
                        resultBox.getChildren().add(buildResultRow(summary));
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    resultBox.getChildren().clear();
                    Label error = new Label("搜索失败：" + e.getMessage());
                    error.getStyleClass().add("empty-hint");
                    resultBox.getChildren().add(error);
                });
            }
        });
    }

    private HBox buildResultRow(UserSummary summary) {
        HBox row = new HBox();
        row.getStyleClass().add("result-row");

        Label avatar = new Label(initialOf(summary.getUsername()));
        avatar.getStyleClass().add("avatar-sm");
        avatar.setStyle("-fx-background-color: " + colorOf(summary.getUsername()) + ";");

        VBox text = new VBox(2);
        HBox.setHgrow(text, Priority.ALWAYS);
        Label name = new Label(summary.getUsername());
        name.getStyleClass().add("result-name");
        boolean alreadyAsked = outgoingPending.contains(summary.getUsername());
        Label meta = new Label(alreadyAsked
            ? "已发送，等待对方同意"
            : (summary.isCanReceive()
                ? "对方同意后成为好友"
                : "对方还没登录过，同意后暂时也收不到消息"));
        meta.getStyleClass().add("result-meta");
        text.getChildren().addAll(name, meta);

        Button add = new Button(alreadyAsked ? "已申请" : "申请");
        add.getStyleClass().add("btn-primary");
        add.setDisable(alreadyAsked);
        add.setOnAction(event -> {
            add.setDisable(true);
            add.setText("发送中");
            requestFriend(summary.getUsername(), add, meta);
        });

        row.getChildren().addAll(avatar, text, add);
        return row;
    }

    private void requestFriend(String peer, Button trigger, Label meta) {
        poller.execute(() -> {
            try {
                ObjectRestResponse<FriendRequest> response =
                    Request.connector(ChatFeign.class).requestFriend(
                        new AddContactRequest(peer, null));
                if (response != null && response.getStatus() == 200) {
                    Platform.runLater(() -> {
                        outgoingPending.add(peer);
                        if (trigger != null) {
                            trigger.setText("已申请");
                            trigger.setDisable(true);
                        }
                        if (meta != null) {
                            meta.setText("已发送，等待对方同意");
                        }
                        loadRequests();
                    });
                } else {
                    String message = response == null ? "服务端无响应" : response.getMessage();
                    Platform.runLater(() -> {
                        if (trigger != null) {
                            trigger.setDisable(false);
                            trigger.setText("申请");
                        }
                        AlertUtil.show("申请失败", message);
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (trigger != null) {
                        trigger.setDisable(false);
                        trigger.setText("申请");
                    }
                    AlertUtil.show("申请失败", e.getMessage());
                });
            }
        });
    }

    private void loadRequests() {
        poller.execute(this::fetchRequests);
    }

    /**
     * 拉取待处理申请。可在轮询线程上调用，界面更新回到 JavaFX 线程。
     */
    private void fetchRequests() {
        try {
            ObjectRestResponse<List<FriendRequest>> response =
                Request.connector(ChatFeign.class).listRequests();
            if (response == null || response.getStatus() != 200 || response.getData() == null) {
                return;
            }
            List<FriendRequest> latest = response.getData();
            Platform.runLater(() -> applyRequests(latest));
        } catch (Exception e) {
            log.debug("刷新好友申请失败：{}", e.getMessage());
        }
    }

    private void applyRequests(List<FriendRequest> latest) {
        outgoingPending.clear();
        int incoming = 0;
        for (FriendRequest request : latest) {
            if ("out".equals(request.getDirection())) {
                outgoingPending.add(request.getUsername());
            } else if ("in".equals(request.getDirection())) {
                incoming++;
            }
        }
        if (requestBadge != null) {
            boolean show = incoming > 0;
            requestBadge.setVisible(show);
            requestBadge.setManaged(show);
            requestBadge.setText(incoming > 99 ? "99+" : String.valueOf(incoming));
        }
        if (requestListBox != null) {
            renderRequestList(requestListBox, latest);
        }
    }

    private void showRequestPanel() {
        if (requestListBox != null) {
            hideOverlay();
            return;
        }
        VBox panel = new VBox();
        panel.getStyleClass().add("search-panel");
        panel.setMaxWidth(380);
        panel.setPrefWidth(360);

        Label title = new Label("好友申请");
        title.getStyleClass().add("panel-title");
        Label hint = new Label("对方同意后，才会出现在好友列表里。");
        hint.getStyleClass().add("result-meta");

        VBox list = new VBox(4);
        requestListBox = list;
        Label loading = new Label("加载中");
        loading.getStyleClass().add("empty-hint");
        list.getChildren().add(loading);

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefHeight(280);
        scroll.setStyle("-fx-background-color: transparent;");

        HBox actions = new HBox();
        actions.setAlignment(Pos.CENTER_RIGHT);
        Button close = new Button("关闭");
        close.getStyleClass().add("btn-quiet");
        close.setOnAction(event -> hideOverlay());
        actions.getChildren().add(close);

        panel.getChildren().addAll(title, hint, scroll, actions);

        StackPane wrap = new StackPane(panel);
        wrap.setPickOnBounds(true);
        wrap.setOnMouseClicked(event -> {
            if (event.getTarget() == wrap) {
                hideOverlay();
            }
        });
        overlayLayer.getChildren().setAll(wrap);
        overlayLayer.setVisible(true);
        overlayLayer.setManaged(true);
        searchPanel = wrap;
        loadRequests();
    }

    private void renderRequestList(VBox list, List<FriendRequest> latest) {
        list.getChildren().clear();
        List<FriendRequest> incoming = new ArrayList<>();
        List<FriendRequest> outgoing = new ArrayList<>();
        for (FriendRequest request : latest) {
            if ("in".equals(request.getDirection())) {
                incoming.add(request);
            } else if ("out".equals(request.getDirection())) {
                outgoing.add(request);
            }
        }
        if (incoming.isEmpty() && outgoing.isEmpty()) {
            Label none = new Label("没有待处理的申请");
            none.getStyleClass().add("empty-hint");
            list.getChildren().add(none);
            return;
        }
        if (!incoming.isEmpty()) {
            Label section = new Label("待你同意");
            section.getStyleClass().add("request-section");
            list.getChildren().add(section);
            for (FriendRequest request : incoming) {
                list.getChildren().add(buildIncomingRow(request));
            }
        }
        if (!outgoing.isEmpty()) {
            Label section = new Label("等待对方同意");
            section.getStyleClass().add("request-section");
            list.getChildren().add(section);
            for (FriendRequest request : outgoing) {
                list.getChildren().add(buildOutgoingRow(request));
            }
        }
    }

    private HBox buildIncomingRow(FriendRequest request) {
        HBox row = new HBox(8);
        row.getStyleClass().add("result-row");
        row.setAlignment(Pos.CENTER_LEFT);

        Label avatar = new Label(initialOf(request.getUsername()));
        avatar.getStyleClass().add("avatar-sm");
        avatar.setStyle("-fx-background-color: " + colorOf(request.getUsername()) + ";");

        VBox text = new VBox(2);
        HBox.setHgrow(text, Priority.ALWAYS);
        Label name = new Label(request.getUsername() == null ? "" : request.getUsername());
        name.getStyleClass().add("result-name");
        Label meta = new Label("请求加你为好友");
        meta.getStyleClass().add("result-meta");
        text.getChildren().addAll(name, meta);

        Button accept = new Button("同意");
        accept.getStyleClass().add("btn-primary");
        Button reject = new Button("拒绝");
        reject.getStyleClass().add("btn-quiet");
        accept.setOnAction(event -> decideRequest(request.getId(), true, accept, reject));
        reject.setOnAction(event -> decideRequest(request.getId(), false, accept, reject));

        row.getChildren().addAll(avatar, text, accept, reject);
        return row;
    }

    private HBox buildOutgoingRow(FriendRequest request) {
        HBox row = new HBox(8);
        row.getStyleClass().add("result-row");
        row.setAlignment(Pos.CENTER_LEFT);

        Label avatar = new Label(initialOf(request.getUsername()));
        avatar.getStyleClass().add("avatar-sm");
        avatar.setStyle("-fx-background-color: " + colorOf(request.getUsername()) + ";");

        VBox text = new VBox(2);
        HBox.setHgrow(text, Priority.ALWAYS);
        Label name = new Label(request.getUsername() == null ? "" : request.getUsername());
        name.getStyleClass().add("result-name");
        Label meta = new Label("等待对方同意");
        meta.getStyleClass().add("result-meta");
        text.getChildren().addAll(name, meta);
        row.getChildren().addAll(avatar, text);
        return row;
    }

    private void decideRequest(Long id, boolean accept, Button acceptButton, Button rejectButton) {
        if (id == null) {
            return;
        }
        acceptButton.setDisable(true);
        rejectButton.setDisable(true);
        poller.execute(() -> {
            try {
                ObjectRestResponse<FriendRequest> response = accept
                    ? Request.connector(ChatFeign.class).acceptRequest(id)
                    : Request.connector(ChatFeign.class).rejectRequest(id);
                if (response != null && response.getStatus() == 200) {
                    Platform.runLater(() -> {
                        loadContacts();
                        loadRequests();
                    });
                } else {
                    String message = response == null ? "服务端无响应" : response.getMessage();
                    Platform.runLater(() -> {
                        acceptButton.setDisable(false);
                        rejectButton.setDisable(false);
                        AlertUtil.show(accept ? "同意失败" : "拒绝失败", message);
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    acceptButton.setDisable(false);
                    rejectButton.setDisable(false);
                    AlertUtil.show(accept ? "同意失败" : "拒绝失败", e.getMessage());
                });
            }
        });
    }

    /**
     * 直接搜索用户（供外部调用，例如回车）。
     */
    private void searchUsers(String keyword) {
        showSearchPanel();
    }
}
