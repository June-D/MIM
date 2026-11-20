package com.junesoon.mim.app.state

import com.junesoon.mim.app.data.CredentialStore
import com.junesoon.mim.app.data.DemoBackend
import com.junesoon.mim.app.data.IncomingMessage
import com.junesoon.mim.app.data.MessagingBackend
import com.junesoon.mim.app.data.PushEvent
import com.junesoon.mim.app.data.RememberedLogin
import com.junesoon.mim.app.data.TwoFactorRequiredException
import com.junesoon.mim.app.domain.ChatMessage
import com.junesoon.mim.app.domain.Contact
import com.junesoon.mim.app.domain.DeviceInfo
import com.junesoon.mim.app.domain.FriendRequest
import com.junesoon.mim.app.domain.MessageHit
import com.junesoon.mim.app.domain.SafetyEntry
import com.junesoon.mim.app.domain.SafetyNumber
import com.junesoon.mim.app.domain.TwoFactorSetup
import com.junesoon.mim.app.domain.UserSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** 页面路由。用密封接口而不是字符串，避免拼错与漏处理。 */
sealed interface Screen {
    data object Login : Screen
    data object Contacts : Screen
    data object Devices : Screen
    data object AddContact : Screen
    data object Security : Screen
    data object Search : Screen
    data class Chat(val contactId: String) : Screen
}

/** 登录页的三种形态。 */
enum class AuthMode { Login, Register, Recover }

data class AppState(
    val screen: Screen = Screen.Login,
    val authMode: AuthMode = AuthMode.Login,
    val selfName: String = "",
    val deviceName: String = "",
    val contacts: List<Contact> = emptyList(),
    val devices: List<DeviceInfo> = emptyList(),
    val safetyNumbers: List<SafetyEntry> = emptyList(),
    val friendRequests: List<FriendRequest> = emptyList(),
    val searchResults: List<UserSummary> = emptyList(),
    val searched: Boolean = false,
    val messages: Map<String, List<ChatMessage>> = emptyMap(),
    val blockedPeers: Set<String> = emptySet(),
    /** 置顶的会话；只在本机生效。 */
    val pinnedPeers: Set<String> = emptySet(),
    /** 记住的账号，用于表单预填与自动登录。 */
    val rememberedUsername: String = "",
    val rememberedPassword: String = "",
    val rememberedDeviceName: String = "",
    val rememberMe: Boolean = false,
    /** 账号开启了二次验证，登录页需要补验证码输入框。 */
    val needsTwoFactor: Boolean = false,
    val twoFactorEnabled: Boolean = false,
    /** 尚未启用的共享密钥；只在开启流程里展示一次。 */
    val twoFactorSetup: TwoFactorSetup? = null,
    /** 一次性恢复码；展示后由用户自行保存。 */
    val recoveryCodes: List<String> = emptyList(),
    val securityBusy: Boolean = false,
    val messageHits: List<MessageHit> = emptyList(),
    val messageQuery: String = "",
    val messageSearched: Boolean = false,
    /** 进入聊天后要定位到的消息；消费后清空。 */
    val pendingScrollMessageId: String? = null,
    /** 本次打开会话时的第一条未读；用于渲染「以下为新消息」分隔。 */
    val firstUnreadMessageId: String? = null,
    /** 对端设备的安全码与上次记录不一致的设备 id；需要用户重新核对。 */
    val safetyChanges: Set<String> = emptySet(),
    val error: String? = null,
    /** 非错误提示，例如「验证码已发送」。 */
    val notice: String? = null,
    val busy: Boolean = false,
) {
    val activeContact: Contact?
        get() = (screen as? Screen.Chat)?.let { chat -> contacts.firstOrNull { it.id == chat.contactId } }

    val pendingRequestCount: Int
        get() = friendRequests.count { it.incoming && it.pending }
}

/**
 * 唯一的状态来源：UI 只读 StateFlow，动作都从这里发出。
 *
 * 协议与密码学由 Rust 核心负责，这里只表达业务语义：
 * 谁在登录、和谁说话、哪些消息已读、哪些联系人被屏蔽。
 */
class AppStore(
    private val backend: MessagingBackend = DemoBackend(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val credentials: CredentialStore = CredentialStore(),
    /** 启动时用记住的账号自动登录；测试里默认关闭，避免依赖本机状态。 */
    private val autoRestore: Boolean = false,
) {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var token: String? = null

    init {
        val saved = runCatching { credentials.load() }.getOrNull()
        if (saved != null) {
            _state.update {
                it.copy(
                    rememberedUsername = saved.username,
                    rememberedPassword = saved.password,
                    rememberedDeviceName = saved.deviceName,
                    rememberMe = true,
                )
            }
            if (autoRestore && saved.autoLogin) {
                login(saved.username, saved.password, saved.deviceName, remember = true, silent = true)
            }
        }
    }

    // ---------------- 账号 ----------------

    fun setAuthMode(mode: AuthMode) {
        _state.update {
            it.copy(authMode = mode, error = null, notice = null, needsTwoFactor = false)
        }
    }

    fun login(
        username: String,
        password: String,
        deviceName: String,
        remember: Boolean = false,
        silent: Boolean = false,
        code: String = "",
    ) {
        if (username.isBlank()) {
            _state.update { it.copy(error = "请输入用户名") }
            return
        }
        if (password.isEmpty()) {
            _state.update { it.copy(error = "请输入密码") }
            return
        }
        val device = deviceName.trim().ifEmpty { "desktop" }
        _state.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            runCatching { backend.login(username.trim(), password, device, code) }
                .onSuccess { result ->
                    token = result.token
                    blockedPeers = backend.blockedContacts().toMutableSet()
                    pinnedPeers = backend.pinnedContacts().toMutableSet()
                    _state.update { current ->
                        val next = current.copy(
                            screen = Screen.Contacts,
                            authMode = AuthMode.Login,
                            selfName = result.displayName,
                            deviceName = device,
                            messages = current.messages,
                            busy = false,
                            error = null,
                            notice = null,
                            blockedPeers = blockedPeers.toSet(),
                            pinnedPeers = pinnedPeers.toSet(),
                            rememberedUsername = if (remember) username.trim() else "",
                            rememberedPassword = if (remember) password else "",
                            rememberedDeviceName = if (remember) device else "",
                            rememberMe = remember,
                            needsTwoFactor = false,
                        )
                        next.copy(
                            contacts = enrich(
                                result.contacts.map { it.copy(unread = 0) },
                                next,
                            ),
                        )
                    }
                    if (remember) {
                        runCatching {
                            credentials.save(
                                RememberedLogin(
                                    username = username.trim(),
                                    password = password,
                                    deviceName = device,
                                    autoLogin = true,
                                ),
                            )
                        }
                    } else {
                        runCatching { credentials.clear() }
                    }
                    startPolling()
                    refreshUnread(result.token, force = true)
                    refreshFriendRequests(result.token)
                    // 断线期间对方清过会话的话，登录时先把本机缓存对齐再展示。
                    reconcileCleared(result.token, result.contacts.map { it.id })
                }
                .onFailure { error ->
                    val needsCode = error is TwoFactorRequiredException
                    _state.update { current ->
                        current.copy(
                            busy = false,
                            error = error.message ?: "登录失败",
                            // 验证码输错时服务端会返回普通业务错误：此时必须保持输入框，
                            // 否则用户刚看到的「验证码」字段会在报错后消失。
                            needsTwoFactor = current.needsTwoFactor || needsCode,
                            authMode = if (silent) AuthMode.Login else current.authMode,
                        )
                    }
                }
        }
    }

    fun register(username: String, password: String, email: String) {
        if (username.isBlank() || password.isEmpty()) {
            _state.update { it.copy(error = "请填写用户名与密码") }
            return
        }
        _state.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            runCatching { backend.register(username.trim(), password, email.trim().ifBlank { null }) }
                .onSuccess {
                    // 注册只负责建账号：回到登录页，由用户自己登录。
                    _state.update {
                        it.copy(
                            busy = false,
                            authMode = AuthMode.Login,
                            rememberedUsername = username.trim(),
                            rememberedPassword = "",
                            rememberMe = false,
                            notice = "注册成功，请用刚才的密码登录。",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, error = error.message ?: "注册失败") }
                }
        }
    }

    fun sendRecoveryCode(username: String) {
        if (username.isBlank()) {
            _state.update { it.copy(error = "请输入用户名") }
            return
        }
        _state.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            runCatching { backend.sendRecoveryCode(username.trim()) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            busy = false,
                            notice = "如果账号绑定了邮箱，验证码已发送，10 分钟内有效。",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, error = error.message ?: "验证码发送失败") }
                }
        }
    }

    fun resetPassword(username: String, code: String, newPassword: String) {
        if (username.isBlank() || code.isBlank() || newPassword.isEmpty()) {
            _state.update { it.copy(error = "请填写用户名、验证码与新密码") }
            return
        }
        _state.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch {
            runCatching { backend.resetPassword(username.trim(), code.trim(), newPassword) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            busy = false,
                            authMode = AuthMode.Login,
                            rememberedUsername = username.trim(),
                            rememberedPassword = "",
                            notice = "密码已重置，请用新密码登录。",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, error = error.message ?: "密码重置失败") }
                }
        }
    }

    /**
     * 退出登录：回到登录页，关闭自动登录。
     *
     * 勾选过「记住账号密码」时保留凭据用于表单预填——否则退出一次就要
     * 重新输入，与「记住」的预期相矛盾；但 `autoLogin` 置为 false，
     * 不会再自动把用户登录回去。
     */
    // ---------------- 搜索 ----------------

    fun openSearch() {
        _state.update {
            it.copy(
                screen = Screen.Search,
                messageHits = emptyList(),
                messageQuery = "",
                messageSearched = false,
                error = null,
                notice = null,
            )
        }
    }

    fun searchMessages(query: String) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            return
        }
        val names = _state.value.contacts.associate { it.id to it.name }
        val hits = backend.searchLocal(keyword).map { hit ->
            MessageHit(
                contactId = hit.contactId,
                contactName = names[hit.contactId] ?: hit.contactId,
                messageId = hit.messageId,
                text = hit.text,
                time = hit.time,
                epochMs = hit.epochMs,
                fromMe = hit.fromMe,
                kind = hit.kind,
            )
        }
        _state.update {
            it.copy(messageHits = hits, messageQuery = keyword, messageSearched = true)
        }
    }

    /** 从搜索结果进入聊天，并在打开后定位到那条消息。 */
    fun openSearchHit(contactId: String, messageId: String) {
        _state.update { it.copy(pendingScrollMessageId = messageId) }
        openChat(contactId)
    }

    fun consumeScrollTarget() {
        _state.update { it.copy(pendingScrollMessageId = null) }
    }

    fun logout() {
        token = null
        pushJob?.cancel()
        loopJob?.cancel()
        pushJob = null
        loopJob = null
        polling = false
        val current = _state.value
        val saved = runCatching { credentials.load() }.getOrNull()
        val remembered = if (current.rememberedUsername.isNotBlank()) {
            RememberedLogin(
                username = current.rememberedUsername,
                password = current.rememberedPassword,
                deviceName = current.rememberedDeviceName.ifBlank { current.deviceName },
                autoLogin = false,
            )
        } else {
            saved?.copy(autoLogin = false)
        }
        if (remembered != null) {
            runCatching { credentials.save(remembered) }
        } else {
            runCatching { credentials.clear() }
        }
        _state.update {
            AppState(
                screen = Screen.Login,
                authMode = AuthMode.Login,
                rememberedUsername = remembered?.username.orEmpty(),
                rememberedPassword = remembered?.password.orEmpty(),
                rememberedDeviceName = remembered?.deviceName.orEmpty(),
                rememberMe = remembered != null,
                notice = "已退出登录",
            )
        }
    }

    // ---------------- 会话 ----------------

    fun openChat(contactId: String) {
        val currentToken = token ?: return
        // 打开前先记下未读数与本地已读水位：进入会话后角标会被清零。
        val hadUnread = _state.value.contacts.firstOrNull { it.id == contactId }?.unread?.let { it > 0 } ?: false
        val readMark = runCatching { backend.readMark(contactId) }.getOrDefault(0L)
        _state.update { current ->
            val next = current.copy(
                screen = Screen.Chat(contactId),
                error = null,
                notice = null,
                firstUnreadMessageId = null,
                safetyChanges = emptySet(),
                contacts = current.contacts.map { contact ->
                    if (contact.id == contactId) contact.copy(unread = 0) else contact
                },
            )
            next.copy(contacts = enrich(next.contacts, next))
        }
        scope.launch {
            // 先对账：对方可能在我们离线时清除了这段会话。
            runCatching { reconcileCleared(currentToken, listOf(contactId)) }
            runCatching { backend.history(currentToken, contactId) }
                .onSuccess { history ->
                    _state.update { current ->
                        // 搜索结果已经指定了定位目标时，不让未读起点覆盖它。
                        val anchor = if (hadUnread && readMark > 0 && current.pendingScrollMessageId == null) {
                            history.firstOrNull { !it.fromMe && it.epochMs > readMark }?.id
                        } else {
                            null
                        }
                        val next = current.copy(
                            messages = current.messages + (contactId to history),
                            pendingScrollMessageId = anchor ?: current.pendingScrollMessageId,
                            firstUnreadMessageId = anchor,
                        )
                        next.copy(contacts = enrich(next.contacts, next))
                    }
                    val newest = history.maxOfOrNull { it.epochMs } ?: 0L
                    runCatching { backend.setReadMark(contactId, newest) }
                }
            runCatching { backend.markRead(currentToken, contactId) }
            refreshStates(currentToken, contactId)
            loadSafetyNumbers(currentToken, contactId)
        }
    }

    fun send(text: String) {
        val currentToken = token ?: return
        val chat = _state.value.screen as? Screen.Chat ?: return
        if (chat.contactId in _state.value.blockedPeers) {
            _state.update { it.copy(error = "已屏蔽该联系人，解除屏蔽后才能发送") }
            return
        }
        val body = text.trim()
        if (body.isEmpty()) {
            return
        }
        scope.launch {
            runCatching { backend.send(currentToken, chat.contactId, body) }
                .onSuccess {
                    val history = backend.history(currentToken, chat.contactId)
                    _state.update { current ->
                        val next = current.copy(messages = current.messages + (chat.contactId to history))
                        next.copy(contacts = enrich(next.contacts, next))
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "发送失败") }
                }
        }
    }

    /** 发送图片；媒体文件先加密再上传，消息里只带引用与密钥。 */
    fun sendImage(bytes: ByteArray, mime: String, name: String?) {
        val currentToken = token ?: return
        val chat = _state.value.screen as? Screen.Chat ?: return
        if (chat.contactId in _state.value.blockedPeers) {
            _state.update { it.copy(error = "已屏蔽该联系人，解除屏蔽后才能发送") }
            return
        }
        scope.launch {
            runCatching { backend.sendImage(currentToken, chat.contactId, bytes, mime, name, "") }
                .onSuccess { refreshHistory(currentToken, chat.contactId) }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "图片发送失败") }
                }
        }
    }

    fun sendVoice(bytes: ByteArray, durationMs: Long) {
        val currentToken = token ?: return
        val chat = _state.value.screen as? Screen.Chat ?: return
        if (chat.contactId in _state.value.blockedPeers) {
            _state.update { it.copy(error = "已屏蔽该联系人，解除屏蔽后才能发送") }
            return
        }
        scope.launch {
            runCatching { backend.sendVoice(currentToken, chat.contactId, bytes, durationMs, "") }
                .onSuccess { refreshHistory(currentToken, chat.contactId) }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "语音发送失败") }
                }
        }
    }

    /** 发送任意文件；媒体文件先加密再上传，消息里只带引用与密钥。 */
    fun sendFile(bytes: ByteArray, name: String, mime: String) {
        val currentToken = token ?: return
        val chat = _state.value.screen as? Screen.Chat ?: return
        if (chat.contactId in _state.value.blockedPeers) {
            _state.update { it.copy(error = "已屏蔽该联系人，解除屏蔽后才能发送") }
            return
        }
        scope.launch {
            runCatching { backend.sendFile(currentToken, chat.contactId, bytes, mime, name) }
                .onSuccess { refreshHistory(currentToken, chat.contactId) }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "文件发送失败") }
                }
        }
    }

    /** 读取并解密媒体明文；在 IO 线程做，避免解密大文件卡住界面。 */
    suspend fun loadMedia(message: ChatMessage): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { backend.mediaBytes(message) }.getOrNull()
    }

    private suspend fun refreshHistory(currentToken: String, contactId: String) {
        runCatching { backend.history(currentToken, contactId) }
            .onSuccess { history ->
                _state.update { current ->
                    val next = current.copy(messages = current.messages + (contactId to history))
                    next.copy(contacts = enrich(next.contacts, next))
                }
            }
    }

    fun recall(contactId: String, messageId: String) {
        val currentToken = token ?: return
        scope.launch {
            runCatching { backend.recall(currentToken, contactId, messageId) }
                .onSuccess { updated ->
                    if (updated != null) {
                        _state.update { current ->
                            val next = current.copy(
                                messages = current.messages.mapValues { (id, list) ->
                                    if (id != contactId) {
                                        list
                                    } else {
                                        list.map { if (it.id == messageId) updated else it }
                                    }
                                },
                            )
                            next.copy(contacts = enrich(next.contacts, next))
                        }
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "撤回失败") }
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
                    _state.update { current ->
                        val next = current.copy(
                            messages = current.messages - chat.contactId,
                            notice = if (forBoth) "已清除双方的这段会话" else "已清除本机的这段会话",
                        )
                        next.copy(contacts = enrich(next.contacts, next))
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "清除会话失败") }
                }
        }
    }

    // ---------------- 联系人 ----------------

    fun openAddContact() {
        val currentToken = token ?: return
        _state.update {
            it.copy(screen = Screen.AddContact, searched = false, searchResults = emptyList())
        }
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
                .onSuccess {
                    _state.update { it.copy(notice = "申请已发送，等待对方同意") }
                    refreshFriendRequests(currentToken)
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "发送申请失败") }
                }
        }
    }

    fun respondFriendRequest(requestId: Long, accept: Boolean) {
        val currentToken = token ?: return
        scope.launch {
            runCatching { backend.respondRequest(currentToken, requestId, accept) }
                .onSuccess {
                    refreshFriendRequests(currentToken)
                    if (accept) {
                        refreshContacts(currentToken, force = true)
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "处理申请失败") }
                }
        }
    }

    /** 修改好友备注名；空字符串表示清除备注。 */
    fun renameContact(peer: String, label: String) {
        val currentToken = token ?: return
        scope.launch {
            runCatching { backend.renameContact(currentToken, peer, label.trim()) }
                .onSuccess {
                    _state.update { it.copy(notice = if (label.isBlank()) "已清除备注" else "备注已更新") }
                    refreshContacts(currentToken, force = true)
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "修改备注失败") }
                }
        }
    }

    /**
     * 屏蔽 / 解除屏蔽。
     *
     * 屏蔽是本地行为：不解密失败、不断棘轮，只是丢弃明文并不再提示；
     * 对方不会收到任何通知。
     */
    fun toggleBlock(peer: String) {
        val blocking = peer !in _state.value.blockedPeers
        blockedPeers = (if (blocking) blockedPeers + peer else blockedPeers - peer).toMutableSet()
        runCatching { backend.setBlocked(peer, blocking) }
            .onFailure { error ->
                _state.update { it.copy(error = error.message ?: "操作失败") }
                return
            }
        _state.update { current ->
            val next = current.copy(
                blockedPeers = blockedPeers.toSet(),
                notice = if (blocking) "已屏蔽 $peer，对方的消息不再显示" else "已解除屏蔽",
            )
            next.copy(contacts = enrich(next.contacts, next))
        }
    }

    /**
     * 置顶 / 取消置顶。
     *
     * 纯本机偏好：不通知服务端，也不改变任何收发行为，只是列表排序。
     */
    fun togglePin(peer: String) {
        val pinning = peer !in _state.value.pinnedPeers
        pinnedPeers = (if (pinning) pinnedPeers + peer else pinnedPeers - peer).toMutableSet()
        runCatching { backend.setPinned(peer, pinning) }
            .onFailure { error ->
                _state.update { it.copy(error = error.message ?: "操作失败") }
                return
            }
        _state.update { current ->
            val next = current.copy(
                pinnedPeers = pinnedPeers.toSet(),
                notice = if (pinning) "已置顶该会话" else "已取消置顶",
            )
            next.copy(contacts = enrich(next.contacts, next))
        }
    }

    /** 移除好友：服务端双向解除关系，本机历史保留。 */
    fun removeContact(peer: String) {
        val currentToken = token ?: return
        scope.launch {
            runCatching { backend.removeContact(currentToken, peer) }
                .onSuccess {
                    if (peer in blockedPeers) {
                        blockedPeers = (blockedPeers - peer).toMutableSet()
                        runCatching { backend.setBlocked(peer, false) }
                    }
                    if (peer in pinnedPeers) {
                        pinnedPeers = (pinnedPeers - peer).toMutableSet()
                        runCatching { backend.setPinned(peer, false) }
                    }
                    _state.update { current ->
                        current.copy(
                            screen = Screen.Contacts,
                            contacts = current.contacts.filterNot { it.id == peer },
                            blockedPeers = blockedPeers.toSet(),
                            pinnedPeers = pinnedPeers.toSet(),
                            firstUnreadMessageId = null,
                            notice = "已移除 $peer，本机历史仍保留",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "移除联系人失败") }
                }
        }
    }

    fun backToContacts() {
        _state.update {
            it.copy(
                screen = Screen.Contacts,
                notice = null,
                firstUnreadMessageId = null,
                safetyChanges = emptySet(),
            )
        }
        token?.let { currentToken ->
            scope.launch {
                refreshContacts(currentToken, force = true)
                refreshUnread(currentToken, force = true)
                refreshFriendRequests(currentToken)
            }
        }
    }

    // ---------------- 设备 ----------------

    // ---------------- 安全设置 ----------------

    fun openSecurity() {
        val currentToken = token ?: return
        _state.update {
            it.copy(
                screen = Screen.Security,
                twoFactorSetup = null,
                recoveryCodes = emptyList(),
                error = null,
                notice = null,
            )
        }
        scope.launch {
            runCatching { backend.twoFactorEnabled(currentToken) }
                .onSuccess { enabled -> _state.update { it.copy(twoFactorEnabled = enabled) } }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "查询二次验证失败") }
                }
        }
    }

    fun startTwoFactorSetup() {
        val currentToken = token ?: return
        _state.update { it.copy(securityBusy = true, error = null, notice = null) }
        scope.launch {
            runCatching { backend.setupTwoFactor(currentToken) }
                .onSuccess { setup ->
                    _state.update { it.copy(securityBusy = false, twoFactorSetup = setup) }
                }
                .onFailure { error ->
                    _state.update { it.copy(securityBusy = false, error = error.message ?: "生成密钥失败") }
                }
        }
    }

    fun confirmTwoFactor(code: String) {
        val currentToken = token ?: return
        _state.update { it.copy(securityBusy = true, error = null) }
        scope.launch {
            runCatching { backend.enableTwoFactor(currentToken, code.trim()) }
                .onSuccess { result ->
                    adoptToken(result.token)
                    _state.update {
                        it.copy(
                            securityBusy = false,
                            twoFactorEnabled = true,
                            twoFactorSetup = null,
                            recoveryCodes = result.recoveryCodes,
                            notice = "二次验证已开启，其它设备需要重新登录",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(securityBusy = false, error = error.message ?: "开启失败") }
                }
        }
    }

    fun disableTwoFactor(password: String, code: String) {
        val currentToken = token ?: return
        _state.update { it.copy(securityBusy = true, error = null) }
        scope.launch {
            runCatching { backend.disableTwoFactor(currentToken, password, code.trim()) }
                .onSuccess { newToken ->
                    adoptToken(newToken)
                    _state.update {
                        it.copy(
                            securityBusy = false,
                            twoFactorEnabled = false,
                            recoveryCodes = emptyList(),
                            notice = "二次验证已关闭",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(securityBusy = false, error = error.message ?: "关闭失败") }
                }
        }
    }

    fun regenerateRecoveryCodes(password: String, code: String) {
        val currentToken = token ?: return
        _state.update { it.copy(securityBusy = true, error = null) }
        scope.launch {
            runCatching { backend.regenerateRecoveryCodes(currentToken, password, code.trim()) }
                .onSuccess { codes ->
                    _state.update {
                        it.copy(
                            securityBusy = false,
                            recoveryCodes = codes,
                            notice = "已生成新的恢复码，旧恢复码全部失效",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(securityBusy = false, error = error.message ?: "生成恢复码失败") }
                }
        }
    }

    fun dismissRecoveryCodes() {
        _state.update { it.copy(recoveryCodes = emptyList()) }
    }

    fun cancelTwoFactorSetup() {
        _state.update { it.copy(twoFactorSetup = null) }
    }

    /** 启用/关闭二次验证会换发令牌，本机必须立刻用新令牌。 */
    private fun adoptToken(newToken: String) {
        if (newToken.isNotBlank()) {
            token = newToken
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

    fun revokeDevice(deviceId: String) {
        val currentToken = token ?: return
        scope.launch {
            runCatching { backend.revokeDevice(currentToken, deviceId) }
                .onSuccess {
                    val devices = backend.devices(currentToken)
                    _state.update { it.copy(devices = devices, notice = "设备已注销") }
                }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "注销设备失败") } }
        }
    }

    /**
     * 计算安全码，并与上次见到的值比对。
     *
     * 首次见到的设备按 TOFU 直接记为基准（不打扰用户）；只有「见过但值变了」
     * 才置为变更提醒——那可能是对方换了密钥，也可能是本机重装，
     * 两种都需要重新线下核对，不能静默接受。
     */
    private suspend fun loadSafetyNumbers(currentToken: String, contactId: String) {
        val own = backend.ownIdentityKeyBase64() ?: return
        runCatching { backend.peerIdentityKeys(currentToken, contactId) }
            .onSuccess { keys ->
                val entries = keys.map { key ->
                    SafetyEntry(key.deviceId, SafetyNumber.of(own, key.publicKeyBase64))
                }
                val known = runCatching { backend.safetyCodes(contactId) }.getOrDefault(emptyMap())
                val fresh = entries.filter { it.deviceId !in known }
                runCatching {
                    backend.rememberSafetyCodes(contactId, fresh.associate { it.deviceId to it.code })
                }
                val changed = entries
                    .filter { entry -> known[entry.deviceId]?.let { it != entry.code } == true }
                    .map { it.deviceId }
                    .toSet()
                _state.update { it.copy(safetyNumbers = entries, safetyChanges = changed) }
            }
    }

    /** 用户已经线下核对过：把当前安全码记为基准，提醒消失，直到下一次变更。 */
    fun acknowledgeSafetyChange() {
        val contactId = (_state.value.screen as? Screen.Chat)?.contactId ?: return
        val entries = _state.value.safetyNumbers
        if (entries.isEmpty()) {
            _state.update { it.copy(safetyChanges = emptySet()) }
            return
        }
        runCatching {
            backend.rememberSafetyCodes(contactId, entries.associate { it.deviceId to it.code })
        }.onFailure { error ->
            _state.update { it.copy(error = error.message ?: "记录安全码失败") }
            return
        }
        _state.update {
            it.copy(
                safetyChanges = emptySet(),
                notice = "已记录当前安全码，密钥再次变更时会重新提醒",
            )
        }
    }

    // ---------------- 同步 ----------------

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    /**
     * 把本机缓存对齐到服务端的清除点。
     *
     * 对方执行「双方清除」时，如果这台设备不在线就收不到推送，
     * 只靠推送会让本地加密缓存一直留着本该被抹掉的内容（重新登录后照旧显示）。
     * 登录、刷新联系人和打开会话这三个时机各对一次账。
     */
    private suspend fun reconcileCleared(currentToken: String, peers: Collection<String>) {
        if (peers.isEmpty()) {
            return
        }
        val points = runCatching { backend.clearPoints(currentToken, peers.toList()) }
            .getOrDefault(emptyMap())
        if (points.isEmpty()) {
            return
        }
        val dropped = points.filter { (peer, point) ->
            runCatching {
                backend.dropClearedMessages(peer, point.clearedBeforeId, point.updatedAt)
            }.getOrDefault(false)
        }
        if (dropped.isEmpty()) {
            return
        }
        // 缓存已经变了，重新读一遍本地历史，界面才会同步（预览、未读分界都跟着走）。
        val refreshed = dropped.keys.associateWith { peer ->
            runCatching { backend.history(currentToken, peer) }.getOrDefault(emptyList())
        }
        _state.update { current ->
            val next = current.copy(messages = current.messages + refreshed)
            next.copy(contacts = enrich(next.contacts, next))
        }
    }

    /** 已读 / 撤回状态靠历史轮询补偿，推送只是叫醒。 */
    private suspend fun refreshStates(currentToken: String, contactId: String) {
        runCatching { backend.historyStates(currentToken, contactId) }
            .onSuccess { states ->
                if (states.isEmpty()) {
                    return@onSuccess
                }
                _state.update { current ->
                    val updated = current.messages[contactId]?.map { message ->
                        val fresh = states.firstOrNull { it.id == message.id } ?: return@map message
                        message.copy(
                            read = fresh.read,
                            recalled = fresh.recalled,
                            pending = fresh.pending || message.pending,
                            text = if (fresh.recalled) "已撤回" else message.text,
                            canRecall = if (fresh.recalled) false else message.canRecall,
                        )
                    }
                    val next = current.copy(
                        messages = current.messages + (contactId to (updated ?: emptyList())),
                    )
                    next.copy(contacts = enrich(next.contacts, next))
                }
            }
    }

    private fun startPolling() {
        if (polling) {
            return
        }
        polling = true
        pushJob = scope.launch {
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
        loopJob = scope.launch {
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
                    val screen = _state.value.screen
                    if (screen is Screen.Chat) {
                        refreshStates(currentToken, screen.contactId)
                    } else if (screen is Screen.Contacts) {
                        refreshContacts(currentToken)
                        refreshUnread(currentToken)
                    } else if (screen is Screen.AddContact) {
                        refreshFriendRequests(currentToken)
                    }
                }
        } finally {
            syncLock.unlock()
        }
    }

    private fun mergeIncoming(incoming: IncomingMessage) {
        _state.update { current ->
            val existing = current.messages[incoming.contactId].orEmpty()
            if (existing.any { it.id == incoming.message.id }) {
                return@update current
            }
            val chatOpen = (current.screen as? Screen.Chat)?.contactId == incoming.contactId
            val blocked = incoming.contactId in current.blockedPeers
            val next = current.copy(
                messages = current.messages +
                    (incoming.contactId to (existing + incoming.message)),
                contacts = current.contacts.map { contact ->
                    if (contact.id != incoming.contactId) {
                        contact
                    } else if (chatOpen || blocked) {
                        contact
                    } else {
                        contact.copy(unread = contact.unread + 1)
                    }
                },
            )
            next.copy(contacts = enrich(next.contacts, next))
        }
    }

    private fun dropLocal(contactId: String) {
        _state.update { current ->
            val next = current.copy(messages = current.messages - contactId)
            next.copy(contacts = enrich(next.contacts, next))
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
                _state.update { current ->
                    val merged = fresh.map { contact ->
                        contact.copy(
                            unread = current.contacts.firstOrNull { it.id == contact.id }
                                ?.unread ?: 0,
                        )
                    }
                    current.copy(contacts = enrich(merged, current))
                }
                // 顺带对账：对方在线时靠推送通知，离线时只能靠这一次查询。
                reconcileCleared(currentToken, fresh.map { it.id })
            }
    }

    /**
     * 未读数以服务端为准；已屏蔽的联系人固定显示 0。
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
            val count = if (contact.blocked || contact.id in _state.value.blockedPeers) {
                0
            } else {
                runCatching { backend.unread(currentToken, contact.id) }.getOrNull()
            }
            contact.id to count
        }
        _state.update { current ->
            current.copy(
                contacts = current.contacts.map { contact ->
                    val fresh = counts[contact.id] ?: return@map contact
                    contact.copy(unread = fresh)
                },
            )
        }
    }

    private suspend fun refreshFriendRequests(currentToken: String) {
        runCatching { backend.friendRequests(currentToken) }
            .onSuccess { requests -> _state.update { it.copy(friendRequests = requests) } }
    }

    /** 把缓存里的最后一条消息与屏蔽状态合并进联系人视图。 */
    private fun enrich(contacts: List<Contact>, current: AppState): List<Contact> =
        contacts.map { contact ->
            val last = current.messages[contact.id]?.lastOrNull()
            val blocked = contact.id in current.blockedPeers
            contact.copy(
                blocked = blocked,
                pinned = contact.id in current.pinnedPeers,
                preview = when {
                    blocked -> "已屏蔽，不再接收此人消息"
                    last != null -> if (last.fromMe) "我：${last.text}" else last.text
                    else -> contact.subtitle.ifBlank { "开始一段加密对话" }
                },
                time = last?.time.orEmpty(),
                lastEpochMs = last?.epochMs ?: 0,
            )
        }

    private var blockedPeers: MutableSet<String> = mutableSetOf()
    private var pinnedPeers: MutableSet<String> = mutableSetOf()
    private var polling = false
    private var pushJob: Job? = null
    private var loopJob: Job? = null
    private var lastUnreadAt = 0L
    private var lastContactsAt = 0L
    private val syncLock = Mutex()
}
