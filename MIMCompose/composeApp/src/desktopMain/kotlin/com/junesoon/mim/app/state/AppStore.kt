package com.junesoon.mim.app.state

import com.junesoon.mim.app.data.DemoBackend
import com.junesoon.mim.app.data.MessagingBackend
import com.junesoon.mim.app.domain.ChatMessage
import com.junesoon.mim.app.domain.Contact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 页面路由。用密封接口而不是字符串，避免拼错与漏处理。 */
sealed interface Screen {
    data object Login : Screen
    data object Contacts : Screen
    data class Chat(val contactId: String) : Screen
}

data class AppState(
    val screen: Screen = Screen.Login,
    val selfName: String = "",
    val deviceName: String = "",
    val contacts: List<Contact> = emptyList(),
    val messages: Map<String, List<ChatMessage>> = emptyMap(),
    val error: String? = null,
    val busy: Boolean = false,
) {
    val activeContact: Contact?
        get() = (screen as? Screen.Chat)?.let { chat -> contacts.firstOrNull { it.id == chat.contactId } }
}

/**
 * 唯一的状态来源：UI 只读 StateFlow，动作都从这里发出。
 *
 * 真实实现把 [MessagingBackend] 换成 REST/推送版本即可；
 * 协议与密码学由 mim-core / Rust 核心负责，不进入这一层。
 */
class AppStore(
    private val backend: MessagingBackend = DemoBackend(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var token: String? = null

    fun login(username: String, password: String, deviceName: String) {
        if (username.isBlank()) {
            _state.update { it.copy(error = "请输入用户名") }
            return
        }
        if (password.isEmpty()) {
            _state.update { it.copy(error = "请输入密码") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        scope.launch {
            runCatching { backend.login(username.trim(), password, deviceName.trim()) }
                .onSuccess { result ->
                    token = result.token
                    _state.update {
                        it.copy(
                            screen = Screen.Contacts,
                            selfName = result.displayName,
                            deviceName = deviceName.trim().ifEmpty { "desktop" },
                            contacts = result.contacts,
                            busy = false,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, error = error.message ?: "登录失败") }
                }
        }
    }

    fun openChat(contactId: String) {
        val currentToken = token ?: return
        _state.update {
            it.copy(
                screen = Screen.Chat(contactId),
                contacts = it.contacts.map { contact ->
                    if (contact.id == contactId) contact.copy(unread = 0) else contact
                },
            )
        }
        scope.launch {
            runCatching { backend.history(currentToken, contactId) }
                .onSuccess { history ->
                    _state.update { it.copy(messages = it.messages + (contactId to history)) }
                }
        }
    }

    fun backToContacts() {
        _state.update { it.copy(screen = Screen.Contacts) }
    }

    fun send(text: String) {
        val currentToken = token ?: return
        val chat = _state.value.screen as? Screen.Chat ?: return
        val body = text.trim()
        if (body.isEmpty()) {
            return
        }
        scope.launch {
            runCatching { backend.send(currentToken, chat.contactId, body) }
                .onSuccess {
                    val history = backend.history(currentToken, chat.contactId)
                    _state.update { state ->
                        state.copy(messages = state.messages + (chat.contactId to history))
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "发送失败") }
                }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
