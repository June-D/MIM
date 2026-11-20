package com.junesoon.mim.app

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.junesoon.mim.app.ui.AddContactScreen
import com.junesoon.mim.app.ui.AuthScreen
import com.junesoon.mim.app.ui.ChatScreen
import com.junesoon.mim.app.ui.ContactsScreen
import com.junesoon.mim.app.ui.DeviceScreen
import com.junesoon.mim.app.ui.MimIcon
import com.junesoon.mim.app.ui.MimPalette
import com.junesoon.mim.app.ui.MimTheme
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
    val store = remember {
        val server = System.getenv("MIM_SERVER_URL")
        val backend: MessagingBackend =
            if (server.isNullOrBlank()) DemoBackend() else SecureBackend(server.trim())
        AppStore(backend, autoRestore = !server.isNullOrBlank())
    }
    val state by store.state.collectAsState()

    // 提示类消息 4 秒后自动消失，错误则留到用户下次操作。
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(4_000)
            store.clearNotice()
        }
    }

    MimTheme {
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
                Screen.Contacts -> ConversationWorkspace(state, store, selectedId = null)

                is Screen.Chat -> ConversationWorkspace(
                    state = state,
                    store = store,
                    selectedId = screen.contactId,
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
                    notice = state.notice,
                    error = state.error,
                )
            }
        }
    }
}

/** 会话列表 + 聊天详情。宽窗口下的主工作区。 */
@Composable
private fun ConversationWorkspace(state: AppState, store: AppStore, selectedId: String?) {
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
            selectedId = selectedId,
            modifier = Modifier.width(312.dp),
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
    Box(
        modifier = Modifier.fillMaxSize().background(MimPalette.Paper),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Box(
                modifier = Modifier.size(52.dp).clip(CircleShape).background(MimPalette.TealSoft),
                contentAlignment = Alignment.Center,
            ) {
                MimIcon(MimIcon.Lock, size = 22.dp, tint = MimPalette.Teal)
            }
            Text("选择一个会话", style = MaterialTheme.typography.titleMedium)
            Text(
                "左边的对话都是端到端加密的，只有双方设备能解开。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
