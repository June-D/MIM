package com.junesoon.mim.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.junesoon.mim.app.data.DemoBackend
import com.junesoon.mim.app.data.MessagingBackend
import com.junesoon.mim.app.data.SecureBackend
import com.junesoon.mim.app.state.AppState
import com.junesoon.mim.app.state.AppStore
import com.junesoon.mim.app.state.Screen
import com.junesoon.mim.app.domain.SyncRequest
import com.junesoon.mim.app.ui.AddContactScreen
import com.junesoon.mim.app.ui.AuthScreen
import com.junesoon.mim.app.ui.ChatScreen
import com.junesoon.mim.app.ui.ContactsScreen
import com.junesoon.mim.app.ui.DeviceScreen
import com.junesoon.mim.app.ui.MimIcon
import com.junesoon.mim.app.data.UiPrefs
import com.junesoon.mim.app.ui.MediaTools
import com.junesoon.mim.app.ui.MimEmptyState
import com.junesoon.mim.app.ui.MimPalette
import com.junesoon.mim.app.ui.MimTheme
import com.junesoon.mim.app.ui.ThemeMode
import com.junesoon.mim.app.ui.SearchScreen
import com.junesoon.mim.app.ui.SecurityScreen
import kotlinx.coroutines.delay

/**
 * 顶层组装：只做路由与状态分发。
 *
 * 设置 MIM_SERVER_URL 时连真实服务端，并用本机记住的账号自动登录；
 * 未设置时用内存演示后端，方便在没有服务端的情况下看界面。
 */
@Composable
fun MimApp() {
    // 外观：默认跟随系统，也可以在「更多」里手动切换；选择记在本机 ui.prefs。
    var themeMode by remember {
        mutableStateOf(
            runCatching { ThemeMode.valueOf(UiPrefs.theme() ?: "") }
                .getOrDefault(ThemeMode.System),
        )
    }
    val cycleTheme: () -> Unit = {
        themeMode = themeMode.next()
        runCatching { UiPrefs.saveTheme(themeMode.name) }
    }
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val started = remember {
        runCatching {
            val server = System.getenv("MIM_SERVER_URL")
            val backend: MessagingBackend =
                if (server.isNullOrBlank()) DemoBackend() else SecureBackend(server.trim())
            AppStore(backend, autoRestore = !server.isNullOrBlank())
        }
    }
    val store = started.getOrNull()
    if (store == null) {
        // 地址被拒（例如非回环的 http）时不要静默降级成演示后端，那样等于假装连上了。
        val reason = started.exceptionOrNull()?.message ?: "启动失败"
        MimTheme(dark = darkTheme) {
            Surface(modifier = Modifier.fillMaxSize()) { StartupError(reason) }
        }
        return
    }
    val state by store.state.collectAsState()

    // 提示类消息 4 秒后自动消失，错误则留到用户下次操作。
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(4_000)
            store.clearNotice()
        }
    }

    MimTheme(dark = darkTheme) {
        // 调色板是进程级单例（非 Compose 状态），换主题时用 key 重建界面子树，
        // 保证每个读 MimPalette 的地方都拿到新值；后端与账号状态在外层，不受影响。
        key(darkTheme) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // 桌面习惯：⌘/Ctrl + K 直接进搜索，不用先把鼠标挪到放大镜上。
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        return@onPreviewKeyEvent false
                    }
                    val command = event.isMetaPressed || event.isCtrlPressed
                    if (command && event.key == Key.K && state.screen !is Screen.Login) {
                        store.openSearch()
                        true
                    } else {
                        false
                    }
                },
        ) {
            when (val screen = state.screen) {
                Screen.Login -> AuthScreen(
                    mode = state.authMode,
                    busy = state.busy,
                    error = state.error,
                    notice = state.notice,
                    initialUsername = state.rememberedUsername,
                    initialPassword = state.rememberedPassword,
                    initialDeviceName = state.rememberedDeviceName,
                    rememberMe = state.rememberMe,
                    needsTwoFactor = state.needsTwoFactor,
                    onModeChange = store::setAuthMode,
                    onLogin = { username, password, device, remember, code ->
                        store.login(
                            username = username,
                            password = password,
                            deviceName = device,
                            remember = remember,
                            code = code,
                        )
                    },
                    onRegister = store::register,
                    onSendCode = store::sendRecoveryCode,
                    onReset = store::resetPassword,
                    onClearFeedback = {
                        store.clearError()
                        store.clearNotice()
                    },
                )

                // 列表与聊天并排：桌面上不用来回切换，点左边就换右边。
                Screen.Contacts -> ConversationWorkspace(
                    state = state,
                    store = store,
                    selectedId = null,
                    themeMode = themeMode,
                    onCycleTheme = cycleTheme,
                )

                is Screen.Chat -> ConversationWorkspace(
                    state = state,
                    store = store,
                    selectedId = screen.contactId,
                    themeMode = themeMode,
                    onCycleTheme = cycleTheme,
                )

                Screen.Search -> SearchScreen(
                    hits = state.messageHits,
                    query = state.messageQuery,
                    searched = state.messageSearched,
                    onBack = store::backToContacts,
                    onSearch = store::searchMessages,
                    onOpenHit = store::openSearchHit,
                )

                Screen.Security -> SecurityScreen(
                    enabled = state.twoFactorEnabled,
                    setup = state.twoFactorSetup,
                    recoveryCodes = state.recoveryCodes,
                    busy = state.securityBusy,
                    error = state.error,
                    notice = state.notice,
                    onBack = store::backToContacts,
                    onStartSetup = store::startTwoFactorSetup,
                    onConfirmSetup = store::confirmTwoFactor,
                    onCancelSetup = store::cancelTwoFactorSetup,
                    onDisable = store::disableTwoFactor,
                    onRegenerate = store::regenerateRecoveryCodes,
                    onDismissCodes = store::dismissRecoveryCodes,
                    email = state.email,
                    onBindEmail = store::bindEmail,
                )

                Screen.AddContact -> AddContactScreen(
                    results = state.searchResults,
                    searched = state.searched,
                    requests = state.friendRequests,
                    onBack = store::backToContacts,
                    onSearch = store::searchUsers,
                    onRequest = store::sendFriendRequest,
                    onRespond = store::respondFriendRequest,
                    error = state.error,
                    notice = state.notice,
                )

                Screen.Devices -> DeviceScreen(
                    devices = state.devices,
                    onBack = store::backToContacts,
                    onRevoke = store::revokeDevice,
                    trustedDevices = state.trustedDevices,
                    onRevokeTrust = store::revokeTrustedDevice,
                    notice = state.notice,
                    error = state.error,
                )
            }

            // 历史同步确认：这是唯一一次由本机用户决定「要不要把历史交给另一台设备」。
            state.pendingSyncRequest?.let { request ->
                SyncRequestDialog(
                    request = request,
                    busy = state.syncBusy,
                    onApprove = store::approveSyncRequest,
                    onReject = store::rejectSyncRequest,
                )
            }
        }
        }
    }
}

/**
 * 「另一台设备请求同步历史」的确认框。
 *
 * 显示设备 id 与身份指纹：指纹要能和那台设备「我的设备」页上的值对上，
 * 对不上说明不是同一台机器，应当拒绝。
 */
@Composable
private fun SyncRequestDialog(
    request: SyncRequest,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onReject() },
        title = { Text("另一台设备请求同步历史") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "设备 ${request.deviceId} 想获取最近的聊天记录。同意后，本机会把" +
                        "能解密的历史（最近 30 天、最多 50 条文本）重新加密发给它。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MimPalette.Ink,
                )
                if (request.fingerprint.isNotBlank()) {
                    Text(
                        "对方身份指纹",
                        style = MaterialTheme.typography.labelSmall,
                        color = MimPalette.InkSoft,
                    )
                    Text(
                        request.fingerprint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MimPalette.TealDeep,
                    )
                    Text(
                        "请和那台设备「我的设备」页显示的指纹比对；不一致说明不是同一台机器。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MimPalette.InkSoft,
                    )
                }
                Text(
                    "如果不是你本人发起的，请拒绝。拒绝后它仍能正常收发消息，但看不到历史。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MimPalette.InkSoft,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onApprove, enabled = !busy) {
                Text(if (busy) "同步中…" else "同意并同步")
            }
        },
        dismissButton = {
            TextButton(onClick = onReject, enabled = !busy) { Text("拒绝") }
        },
        containerColor = MimPalette.Card,
    )
}

/** 会话列表 + 聊天详情。宽窗口下的主工作区。 */
@Composable
private fun ConversationWorkspace(
    state: AppState,
    store: AppStore,
    selectedId: String?,
    themeMode: ThemeMode,
    onCycleTheme: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        ContactsScreen(
            selfName = state.selfName,
            deviceName = state.deviceName,
            contacts = state.contacts,
            pendingRequestCount = state.pendingRequestCount,
            onOpenChat = store::openChat,
            onShowDevices = store::openDevices,
            onAddContact = store::openAddContact,
            onOpenSearch = store::openSearch,
            onOpenSecurity = store::openSecurity,
            onLogout = store::logout,
            onTogglePin = store::togglePin,
            avatars = state.avatars,
            onPickAvatar = {
                // 选图与重编码都在 UI 线程：弹系统对话框只能是同步的。
                // 失败必须有反馈，否则用户看到的就是「点了没反应」。
                runCatching { MediaTools.pickAvatarPng() }
                    .onSuccess { png -> if (png != null) store.uploadAvatar(png) }
                    .onFailure { error ->
                        store.reportError(error.message ?: "无法读取这张图片")
                    }
            },
            onRemoveAvatar = store::removeAvatar,
            selectedId = selectedId,
            modifier = Modifier.width(312.dp),
            themeLabel = themeMode.label,
            onCycleTheme = onCycleTheme,
            notice = state.notice,
            error = state.error,
        )
        VerticalDivider(color = MimPalette.Line)
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val contact = state.activeContact
            if (contact == null || selectedId == null) {
                EmptyConversation()
            } else {
                ChatScreen(
                    contact = contact,
                    messages = state.messages[selectedId].orEmpty(),
                    onBack = store::backToContacts,
                    onSend = store::send,
                    onSendImage = store::sendImage,
                    onSendVoice = store::sendVoice,
                    onSendFile = store::sendFile,
                    loadMedia = { message -> store.loadMedia(message) },
                    scrollToMessageId = state.pendingScrollMessageId,
                    onScrolled = store::consumeScrollTarget,
                    pinned = contact.pinned,
                    onTogglePin = { store.togglePin(selectedId) },
                    onRecall = { messageId -> store.recall(selectedId, messageId) },
                    onClear = store::clearConversation,
                    onRename = { label -> store.renameContact(selectedId, label) },
                    onToggleBlock = { store.toggleBlock(selectedId) },
                    onRemove = { store.removeContact(selectedId) },
                    safetyNumbers = state.safetyNumbers,
                    peerAvatar = state.avatars[selectedId],
                    firstUnreadId = state.firstUnreadMessageId,
                    safetyChanged = state.safetyChanges,
                    onAcknowledgeSafety = store::acknowledgeSafetyChange,
                    showBack = false,
                    error = state.error,
                    notice = state.notice,
                )
            }
        }
    }
}

/** 右侧空态：没选会话时也不留一块空白。 */
@Composable
private fun EmptyConversation() {
    Box(modifier = Modifier.fillMaxSize().background(MimPalette.Paper)) {
        MimEmptyState(
            icon = MimIcon.Lock,
            title = "选择一个会话",
            hint = "左边的对话都是端到端加密的，只有双方设备能解开。",
        )
    }
}

/** 启动即被拒（地址不合法 / 拒绝明文）时的落地页：说明原因与出路，不假装能用。 */
@Composable
private fun StartupError(reason: String) {
    Box(modifier = Modifier.fillMaxSize().background(MimPalette.Paper)) {
        MimEmptyState(
            icon = MimIcon.Lock,
            title = "无法连接到服务端",
            hint = "$reason\n\n地址由环境变量 MIM_SERVER_URL 指定；" +
                "只接受 https://，本机回环地址可以用 http://。",
        )
    }
}
