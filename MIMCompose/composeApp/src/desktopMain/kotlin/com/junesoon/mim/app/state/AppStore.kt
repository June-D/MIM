package com.junesoon.mim.app.state

import com.junesoon.mim.app.data.DemoBackend
import com.junesoon.mim.app.data.MessagingBackend
import com.junesoon.mim.app.data.IncomingMessage
import com.junesoon.mim.app.data.PushEvent
import com.junesoon.mim.app.domain.ChatMessage
import com.junesoon.mim.app.domain.DeviceInfo
import com.junesoon.mim.app.domain.FriendRequest
import com.junesoon.mim.app.domain.SafetyEntry
import com.junesoon.mim.app.domain.SafetyNumber
import com.junesoon.mim.app.domain.UserSummary
import com.junesoon.mim.app.domain.Contact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
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
    data object Devices : Screen
    data object AddContact : Screen
    data class Chat(val contactId: String) : Screen
}

data class AppState(
    val screen: Screen = Screen.Login,
    val selfName: String = "",
    val deviceName: String = "",
    val contacts: List<Contact> = emptyList(),
    val devices: List<DeviceInfo> = emptyList(),
    val safetyNumbers: List<SafetyEntry> = emptyList(),
    val friendRequests: List<FriendRequest> = emptyList(),
    val searchResults: List<UserSummary> = emptyList(),
    val searched: Boolean = false,
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
                    startPolling()
                    refreshUnread(result.token, force = true)
                    refreshFriendRequests(result.token)
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
            runCatching { backend.markRead(currentToken, contactId) }
            refreshStates(currentToken, contactId)
            loadSafetyNumbers(currentToken, contactId)
        }
    }

    fun openDevices() {
        val currentToken = token ?: return
        _state.update { it.copy(screen = Screen.Devices) }
        scope.launch {
            runCatching { backend.devices(currentToken) }
                .onSuccess { devices -> _state.update { it.copy(devices = devices) } }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "查询设备失败") } }
        }
    }

    fun openAddContact() {
        val currentToken = token ?: return
        _state.update { it.copy(screen = Screen.AddContact, searched = false, searchResults = emptyList()) }
        scope.launch { refreshFriendRequests(currentToken) }
    }

    fun searchUsers(keyword: String) {
        val currentToken = token ?: return
        val query = keyword.trim()
        if (query.isEmpty()) {
            return
        }
        scope.launch {
            runCatching { backend.searchUsers(currentToken, query) }
                .onSuccess { users ->
                    _state.update { it.copy(searchResults = users, searched = true) }
                }
                .onFailure { error ->
                    _state.update { it.copy(searched = true, error = error.message ?: "搜索失败") }
                }
        }
    }

    fun sendFriendRequest(username: String) {
        val currentToken = token ?: return
        scope.launch {
            runCatching { backend.requestFriend(currentToken, username) }
                .onSuccess { refreshFriendRequests(currentToken) }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "发送申请失败") } }
        }
    }

    fun respondFriendRequest(requestId: Long, accept: Boolean) {
        val currentToken = token ?: return
        scope.launch {
            runCatching { backend.respondRequest(currentToken, requestId, accept) }
                .onSuccess {
                    refreshFriendRequests(currentToken)
                    if (accept) {
                        runCatching { backend.contacts(currentToken) }
                            .onSuccess { contacts -> _state.update { it.copy(contacts = contacts) } }
                    }
                }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "处理申请失败") } }
        }
    }

    private suspend fun refreshFriendRequests(currentToken: String) {
        runCatching { backend.friendRequests(currentToken) }
            .onSuccess { requests -> _state.update { it.copy(friendRequests = requests) } }
    }

    fun revokeDevice(deviceId: String) {
        val currentToken = token ?: return
        scope.launch {
            runCatching { backend.revokeDevice(currentToken, deviceId) }
                .onSuccess {
                    val devices = backend.devices(currentToken)
                    _state.update { it.copy(devices = devices) }
                }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "注销设备失败") } }
        }
    }

    private suspend fun loadSafetyNumbers(currentToken: String, contactId: String) {
        val own = backend.ownIdentityKeyBase64() ?: return
        runCatching { backend.peerIdentityKeys(currentToken, contactId) }
            .onSuccess { keys ->
                val entries = keys.map { key ->
                    SafetyEntry(key.deviceId, SafetyNumber.of(own, key.publicKeyBase64))
                }
                _state.update { it.copy(safetyNumbers = entries) }
            }
    }

    fun backToContacts() {
        _state.update { it.copy(screen = Screen.Contacts) }
        token?.let { currentToken ->
            scope.launch {
                refreshContacts(currentToken, force = true)
                refreshUnread(currentToken, force = true)
                refreshFriendRequests(currentToken)
            }
        }
    }

    /**
     * 清除当前会话。
     *
     * 服务端按清除点过滤历史，本地缓存必须一起丢掉，
     * 否则下次打开会看到服务端已经抹掉的旧消息。
     */
    fun clearConversation(forBoth: Boolean) {
        val currentToken = token ?: return
        val chat = _state.value.screen as? Screen.Chat ?: return
        scope.launch {
            runCatching { backend.clearConversation(currentToken, chat.contactId, forBoth) }
                .onSuccess {
                    dropLocal(chat.contactId)
                    _state.update { state ->
                        state.copy(
                            contacts = state.contacts.map { contact ->
                                if (contact.id == chat.contactId) {
                                    contact.copy(subtitle = "", unread = 0)
                                } else {
                                    contact
                                }
                            },
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "清除会话失败") }
                }
        }
    }

    /**
     * 重新拉联系人列表。
     *
     * 服务端的联系人视图不带未读数，因此按 id 保留本地角标，
     * 随后由 refreshUnread 校正；推送丢失时靠轮询在 15 秒内补上。
     */
    private suspend fun refreshContacts(currentToken: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastContactsAt < 15_000) {
            return
        }
        lastContactsAt = now
        runCatching { backend.contacts(currentToken) }
            .onSuccess { fresh ->
                _state.update { state ->
                    state.copy(
                        contacts = fresh.map { contact ->
                            contact.copy(
                                unread = state.contacts.firstOrNull { it.id == contact.id }
                                    ?.unread ?: 0,
                            )
                        },
                    )
                }
            }
    }

    /**
     * 未读数以服务端为准；查不到时保留当前值，不显示假数字。
     *
     * 每个联系人一次请求，因此节流到 5 秒一次，避免轮询把请求放大。
     */
    private suspend fun refreshUnread(currentToken: String, force: Boolean = false) {
        val contacts = _state.value.contacts
        if (contacts.isEmpty()) {
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastUnreadAt < 5_000) {
            return
        }
        lastUnreadAt = now
        val counts = contacts.associate { contact ->
            contact.id to runCatching { backend.unread(currentToken, contact.id) }.getOrNull()
        }
        _state.update { state ->
            state.copy(
                contacts = state.contacts.map { contact ->
                    val fresh = counts[contact.id] ?: return@map contact
                    contact.copy(unread = fresh)
                },
            )
        }
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

    fun recall(contactId: String, messageId: String) {
        val currentToken = token ?: return
        scope.launch {
            runCatching { backend.recall(currentToken, contactId, messageId) }
                .onSuccess { updated ->
                    if (updated != null) {
                        _state.update { state ->
                            state.copy(
                                messages = state.messages.mapValues { (id, list) ->
                                    if (id != contactId) list else list.map {
                                        if (it.id == messageId) updated else it
                                    }
                                },
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "撤回失败") }
                }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** 已读 / 撤回状态缺少推送，靠轮询历史补偿。 */
    private suspend fun refreshStates(currentToken: String, contactId: String) {
        runCatching { backend.historyStates(currentToken, contactId) }
            .onSuccess { states ->
                if (states.isEmpty()) {
                    return@onSuccess
                }
                _state.update { state ->
                    val updated = state.messages[contactId]?.map { message ->
                        val fresh = states.firstOrNull { it.id == message.id } ?: return@map message
                        message.copy(
                            read = fresh.read,
                            recalled = fresh.recalled,
                            pending = fresh.pending || message.pending,
                            text = if (fresh.recalled) "已撤回" else message.text,
                            canRecall = if (fresh.recalled) false else message.canRecall,
                        )
                    }
                    state.copy(messages = state.messages + (contactId to (updated ?: emptyList())))
                }
            }
    }

    /**
     * 轮询新消息：真实后端会解密并落缓存，演示后端返回空。
     *
     * 每 3 秒一次；页面离开登录态后不退出循环，因为断线重连仍需要它。
     */
    private fun startPolling() {
        if (polling) {
            return
        }
        polling = true
        // 推送只用来「叫醒」同步；HTTP 仍是一致性来源。
        scope.launch {
            runCatching {
                backend.listenPush { event ->
                    when (event) {
                        PushEvent.NewData -> scope.launch { syncOnce() }
                        PushEvent.FriendsChanged -> scope.launch {
                            val current = token ?: return@launch
                            refreshFriendRequests(current)
                            refreshContacts(current, force = true)
                            refreshUnread(current, force = true)
                        }
                        is PushEvent.Cleared -> {
                            backend.forgetLocalConversation(event.peer)
                            dropLocal(event.peer)
                        }
                    }
                }
            }
        }
        // 轮询兜底：推送断开或丢失时最多延迟 3 秒。
        scope.launch {
            while (scope.isActive) {
                delay(3_000)
                syncOnce()
            }
        }
    }

    private suspend fun syncOnce() {
        val currentToken = token ?: return
        if (!syncLock.tryLock()) {
            return
        }
        try {
            runCatching { backend.poll(currentToken) }
                .onSuccess { incoming ->
                    incoming.forEach(::mergeIncoming)
                    val open = (_state.value.screen as? Screen.Chat)?.contactId
                    if (open != null) {
                        refreshStates(currentToken, open)
                    } else if (_state.value.screen is Screen.Contacts) {
                        refreshContacts(currentToken)
                        refreshUnread(currentToken)
                    } else if (_state.value.screen is Screen.AddContact) {
                        refreshFriendRequests(currentToken)
                    }
                }
        } finally {
            syncLock.unlock()
        }
    }

    /** 只丢本地消息缓存；安全码不受清除影响，身份密钥没有变化。 */
    private fun dropLocal(contactId: String) {
        _state.update { state -> state.copy(messages = state.messages - contactId) }
    }

    private fun mergeIncoming(incoming: IncomingMessage) {
        _state.update { state ->
            val existing = state.messages[incoming.contactId].orEmpty()
            if (existing.any { it.id == incoming.message.id }) {
                return@update state
            }
            val chatOpen = (state.screen as? Screen.Chat)?.contactId == incoming.contactId
            state.copy(
                messages = state.messages + (incoming.contactId to (existing + incoming.message)),
                contacts = state.contacts.map { contact ->
                    when {
                        contact.id != incoming.contactId -> contact
                        chatOpen -> contact.copy(subtitle = incoming.message.text)
                        else -> contact.copy(
                            subtitle = incoming.message.text,
                            unread = contact.unread + 1,
                        )
                    }
                },
            )
        }
    }

    private var polling = false
    private var lastUnreadAt = 0L
    private var lastContactsAt = 0L
    private val syncLock = Mutex()
}
