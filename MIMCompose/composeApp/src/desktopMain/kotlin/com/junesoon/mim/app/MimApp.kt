package com.junesoon.mim.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.junesoon.mim.app.data.DemoBackend
import com.junesoon.mim.app.data.MessagingBackend
import com.junesoon.mim.app.data.SecureBackend
import com.junesoon.mim.app.state.AppStore
import com.junesoon.mim.app.state.Screen
import com.junesoon.mim.app.ui.AddContactScreen
import com.junesoon.mim.app.ui.AuthScreen
import com.junesoon.mim.app.ui.ChatScreen
import com.junesoon.mim.app.ui.ContactsScreen
import com.junesoon.mim.app.ui.DeviceScreen
import com.junesoon.mim.app.ui.SearchScreen
import com.junesoon.mim.app.ui.SecurityScreen
import com.junesoon.mim.app.ui.MimTheme
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
        Surface(modifier = Modifier.fillMaxSize()) {
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

                Screen.Contacts -> ContactsScreen(
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
                    notice = state.notice,
                    error = state.error,
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

                is Screen.Chat -> {
                    val contact = state.activeContact
                    if (contact == null) {
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
                        )
                    } else {
                        ChatScreen(
                            contact = contact,
                            messages = state.messages[screen.contactId].orEmpty(),
                            onBack = store::backToContacts,
                            onSend = store::send,
                            onSendImage = store::sendImage,
                            onSendVoice = store::sendVoice,
                            loadMedia = { message -> store.loadMedia(message) },
                            scrollToMessageId = state.pendingScrollMessageId,
                            onScrolled = store::consumeScrollTarget,
                            onRecall = { messageId -> store.recall(screen.contactId, messageId) },
                            onClear = store::clearConversation,
                            onRename = { label -> store.renameContact(screen.contactId, label) },
                            onToggleBlock = { store.toggleBlock(screen.contactId) },
                            onRemove = { store.removeContact(screen.contactId) },
                            safetyNumbers = state.safetyNumbers,
                            error = state.error,
                            notice = state.notice,
                        )
                    }
                }
            }
        }
    }
}
