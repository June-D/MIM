package com.junesoon.mim.app.data

import com.junesoon.mim.app.data.messaging.ApiEnvelope
import com.junesoon.mim.app.data.messaging.CertDto
import com.junesoon.mim.app.data.messaging.CertJson
import com.junesoon.mim.app.data.messaging.AddContactRequest
import com.junesoon.mim.app.data.messaging.ClearRequest
import com.junesoon.mim.app.data.messaging.ClearResultDto
import com.junesoon.mim.app.data.messaging.ChatContactGroup
import com.junesoon.mim.app.data.messaging.CipherMessageDto
import com.junesoon.mim.app.data.messaging.CipherMessageOut
import com.junesoon.mim.app.data.messaging.ComposeRequestJson
import com.junesoon.mim.app.data.messaging.ComposeResponseJson
import com.junesoon.mim.app.data.messaging.ComposeSealedRequestJson
import com.junesoon.mim.app.data.messaging.ComposeSealedResponseJson
import com.junesoon.mim.app.data.messaging.DeviceKeyDto
import com.junesoon.mim.app.data.messaging.FriendRequestDto
import com.junesoon.mim.app.data.messaging.UserSummaryDto
import com.junesoon.mim.app.data.messaging.DeviceListDto
import com.junesoon.mim.app.data.messaging.InsertRequestJson
import com.junesoon.mim.app.data.messaging.LoginRequest
import com.junesoon.mim.app.data.messaging.MediaPayload
import com.junesoon.mim.app.data.messaging.MediaRefJson
import com.junesoon.mim.app.data.messaging.MediaUploadDto
import com.junesoon.mim.app.data.messaging.OpenFirstRequestJson
import com.junesoon.mim.app.data.messaging.OpenFirstResponseJson
import com.junesoon.mim.app.data.messaging.OpenSealedRequestJson
import com.junesoon.mim.app.data.messaging.OpenSealedResponseJson
import com.junesoon.mim.app.data.messaging.PreKeyBundleDto
import com.junesoon.mim.app.data.messaging.RecoverEmailRequest
import com.junesoon.mim.app.data.messaging.RecoveryCodesDto
import com.junesoon.mim.app.data.messaging.RecoverResetRequest
import com.junesoon.mim.app.data.messaging.RegisterRequest
import com.junesoon.mim.app.data.messaging.TokenOnlyDto
import com.junesoon.mim.app.data.messaging.TwoFactorCodeRequest
import com.junesoon.mim.app.data.messaging.TwoFactorEnableDto
import com.junesoon.mim.app.data.messaging.TwoFactorSetupDto
import com.junesoon.mim.app.data.messaging.TwoFactorStatusDto
import com.junesoon.mim.app.data.messaging.ReadRequest
import com.junesoon.mim.app.data.messaging.RecallRequest
import com.junesoon.mim.app.data.messaging.SessionMeta
import com.junesoon.mim.app.data.messaging.StatusEnvelope
import com.junesoon.mim.app.data.messaging.StoreJson
import com.junesoon.mim.app.data.messaging.StoreResponseJson
import com.junesoon.mim.app.data.messaging.WireJson
import com.junesoon.mim.app.domain.ChatMessage
import com.junesoon.mim.app.domain.Contact
import com.junesoon.mim.app.domain.DeviceInfo
import com.junesoon.mim.app.domain.FriendRequest
import com.junesoon.mim.app.domain.LocalMessageHit
import com.junesoon.mim.app.domain.MediaAttachment
import com.junesoon.mim.app.domain.UserSummary
import com.junesoon.mim.app.domain.PeerIdentityKey
import com.junesoon.mim.app.domain.TwoFactorSetup
import com.junesoon.mim.core.MimCoreNative
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 真实收发后端：登录 → 设备密钥 → 首条消息 → sealed 收发。
 *
 * <p>私钥、会话与消息缓存都在本地保险库（加密落盘）；服务端只看到密文。
 * 协议计算全部交给 Rust 核心（JNI），Kotlin 只负责传输入库与 UI 语义。
 *
 * <p>限制：历史消息只来自本地缓存；换设备或清空保险库后无法回放旧消息。
 */
class SecureBackend(
    private val server: String,
    private val vaultRoot: Path = LocalVault.defaultRoot(),
    private val client: HttpClient = HttpClient(CIO) {
        // 服务端要求 sealed 等字段显式出现，默认值必须参与序列化。
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
        install(WebSockets)
    },
) : MessagingBackend {

    private lateinit var vault: LocalVault
    private lateinit var bundle: RustKeyBundle
    private var storeJson: String = LocalVault.EMPTY_STORE
    private var messages: MutableList<CachedMessage> = mutableListOf()
    private var serverIds: MutableMap<String, Long> = mutableMapOf()
    private var blockedPeers: MutableSet<String> = mutableSetOf()
    private var pinnedPeers: MutableSet<String> = mutableSetOf()
    private var readMarks: MutableMap<String, Long> = mutableMapOf()

    /** key 形如 `联系人|设备`，值是上次见到的安全码。 */
    private var seenSafetyCodes: MutableMap<String, String> = mutableMapOf()
    private val mediaCache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {}
    private var mediaCacheSize = 0L
    private var outbox: MutableList<OutboxEntry> = mutableListOf()
    private var cachedCert: CertJson? = null
    private var cachedToken: String = ""
    private var cachedDeviceId: String = ""
    private var cachedUsername: String = ""

    /** 当前登录的设备与用户名（测试与设备管理界面用）。 */
    val currentDeviceId: String get() = cachedDeviceId

    /**
     * 下行推送：只用来「叫醒」HTTP 同步，不承载业务判断。
     *
     * 连接断开后每 3 秒重连；25 秒没收到任何帧就发一次文本心跳
     * （服务端按报文里是否含 `"ping"` 识别）。
     */
    override suspend fun listenPush(onSignal: (PushEvent) -> Unit) {
        val wsBase = server.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        while (currentCoroutineContext().isActive) {
            val connected = runCatching {
                client.webSocket(
                    request = {
                        url("$wsBase/websocket/compose")
                        header("Authorization", "Bearer $cachedToken")
                        header("X-Device-Id", cachedDeviceId)
                    },
                ) {
                    while (isActive) {
                        val frame = withTimeoutOrNull(25_000) { incoming.receive() }
                        if (frame == null) {
                            send(Frame.Text("{\"ping\":true}"))
                            continue
                        }
                        if (frame is Frame.Text) {
                            toEvent(frame.data.decodeToString())?.let(onSignal)
                        }
                    }
                }
            }.isSuccess
            if (!connected) {
                delay(3_000)
            }
        }
    }

    private fun toEvent(payload: String): PushEvent? {
        val envelope = runCatching { JsonCodec.decode<PushEnvelope>(payload) }.getOrNull()
            ?: return null
        return when (envelope.type) {
            TYPE_NEW_MESSAGE, TYPE_READ_RECEIPT, TYPE_RECALLED -> PushEvent.NewData
            TYPE_FRIEND_REQUEST, TYPE_FRIEND_ACCEPTED, TYPE_FRIEND_REJECTED ->
                PushEvent.FriendsChanged
            TYPE_CONVERSATION_CLEARED -> {
                val peer = runCatching {
                    envelope.data?.jsonObject?.get("peer")?.jsonPrimitive?.content
                }.getOrNull()
                peer?.takeIf { it.isNotBlank() }?.let { PushEvent.Cleared(it) }
            }
            else -> null
        }
    }

    override suspend fun register(username: String, password: String, email: String?) {
        // 显式的 @Serializable DTO：buildMap 的运行时类型是 MapBuilder，
        // kotlinx.serialization 会拒绝为它查找序列化器。
        val body = RegisterRequest(
            username = username,
            password = password,
            email = email?.trim()?.takeIf { it.isNotEmpty() },
            agreedTerms = true,
            termsVersion = TERMS_VERSION,
        )
        val response: StatusEnvelope = client.post("$server/register/token") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
        require(response.status == 200) { response.message ?: "注册失败" }
    }

    override suspend fun sendRecoveryCode(username: String) {
        val response: StatusEnvelope = client.post("$server/recover/email") {
            contentType(ContentType.Application.Json)
            setBody(RecoverEmailRequest(username))
        }.body()
        require(response.status == 200) { response.message ?: "验证码发送失败" }
    }

    override suspend fun resetPassword(username: String, code: String, newPassword: String) {
        val response: StatusEnvelope = client.post("$server/recover/reset") {
            contentType(ContentType.Application.Json)
            setBody(RecoverResetRequest(username, code, newPassword))
        }.body()
        require(response.status == 200) { response.message ?: "密码重置失败" }
    }

    override suspend fun renameContact(token: String, peer: String, label: String) {
        val response: StatusEnvelope = client.post("$server/chat/contacts/$peer/label") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(AddContactRequest(peer, label.ifBlank { null }))
        }.body()
        require(response.status == 200) { response.message ?: "修改备注失败" }
    }

    override fun setBlocked(contactId: String, blocked: Boolean) {
        if (blocked) blockedPeers += contactId else blockedPeers -= contactId
        vault.saveBlocked(blockedPeers)
    }

    override fun blockedContacts(): Set<String> = blockedPeers.toSet()

    override fun pinnedContacts(): Set<String> = pinnedPeers.toSet()

    override fun setPinned(contactId: String, pinned: Boolean) {
        if (pinned) pinnedPeers += contactId else pinnedPeers -= contactId
        vault.savePinned(pinnedPeers)
    }

    override fun readMark(contactId: String): Long = readMarks[contactId] ?: 0L

    override fun setReadMark(contactId: String, epochMs: Long) {
        if (epochMs <= 0) {
            return
        }
        // 水位只前进不后退：乱序到达的旧消息不能把已读位置往回拨。
        if (epochMs <= readMark(contactId)) {
            return
        }
        readMarks[contactId] = epochMs
        vault.saveReadMarks(readMarks)
    }

    override fun safetyCodes(contactId: String): Map<String, String> =
        seenSafetyCodes
            .filterKeys { it.startsWith("$contactId|") }
            .mapKeys { (key, _) -> key.substringAfter('|') }

    override fun rememberSafetyCodes(contactId: String, codes: Map<String, String>) {
        if (codes.isEmpty()) {
            return
        }
        codes.forEach { (deviceId, code) -> seenSafetyCodes["$contactId|$deviceId"] = code }
        vault.saveSafetyCodes(seenSafetyCodes)
    }

    override suspend fun twoFactorEnabled(token: String): Boolean {
        val status: TwoFactorStatusDto = getEnvelope("$server/user/2fa/status", token)
        return status.enabled
    }

    override suspend fun setupTwoFactor(token: String): TwoFactorSetup {
        val setup: TwoFactorSetupDto = postEnvelope("$server/user/2fa/setup", token)
        return TwoFactorSetup(
            secret = setup.secret.orEmpty(),
            otpauthUri = setup.otpauthUri.orEmpty(),
        )
    }

    override suspend fun enableTwoFactor(token: String, code: String): TwoFactorResult {
        val result: TwoFactorEnableDto = client.post("$server/user/2fa/enable") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(TwoFactorCodeRequest(code))
        }.body<ApiEnvelope<TwoFactorEnableDto>>().data
            ?: throw IllegalStateException("启用二次验证失败")
        // 服务端会作废其它设备并给本设备换发令牌，必须立刻换用新令牌。
        result.token?.takeIf { it.isNotBlank() }?.let { cachedToken = it }
        return TwoFactorResult(result.token.orEmpty(), result.recoveryCodes)
    }

    override suspend fun disableTwoFactor(
        token: String,
        password: String,
        code: String,
    ): String {
        val result: TokenOnlyDto = client.post("$server/user/2fa/disable") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(TwoFactorCodeRequest(code, password))
        }.body<ApiEnvelope<TokenOnlyDto>>().data
            ?: throw IllegalStateException("关闭二次验证失败")
        result.token?.takeIf { it.isNotBlank() }?.let { cachedToken = it }
        return result.token.orEmpty()
    }

    override suspend fun regenerateRecoveryCodes(
        token: String,
        password: String,
        code: String,
    ): List<String> {
        val result: RecoveryCodesDto = client.post("$server/user/2fa/recovery-codes") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(TwoFactorCodeRequest(code, password))
        }.body<ApiEnvelope<RecoveryCodesDto>>().data
            ?: throw IllegalStateException("生成恢复码失败")
        return result.recoveryCodes
    }

    override suspend fun searchUsers(token: String, keyword: String): List<UserSummary> {
        val users: List<UserSummaryDto> =
            getEnvelope("$server/chat/search?keyword=${encodeQuery(keyword)}", token)
        return users.mapNotNull { user ->
            val username = user.username ?: return@mapNotNull null
            UserSummary(username, user.nickname ?: username, user.canReceive)
        }
    }

    override suspend fun friendRequests(token: String): List<FriendRequest> {
        val requests: List<FriendRequestDto> = getEnvelope("$server/chat/requests", token)
        return requests.map { request ->
            FriendRequest(
                id = request.id,
                username = request.username.orEmpty(),
                incoming = request.direction == "in",
                status = request.status.orEmpty(),
            )
        }
    }

    override suspend fun requestFriend(token: String, username: String) {
        val response: ApiEnvelope<FriendRequestDto> = client.post("$server/chat/requests") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(AddContactRequest(username))
        }.body()
        require(response.status == 200) { response.message ?: "发送申请失败" }
    }

    override suspend fun respondRequest(token: String, requestId: Long, accept: Boolean) {
        val action = if (accept) "accept" else "reject"
        val response: ApiEnvelope<FriendRequestDto> =
            client.post("$server/chat/requests/$requestId/$action") {
                header("Authorization", "Bearer $token")
            }.body()
        require(response.status == 200) { response.message ?: "处理申请失败" }
    }

    override suspend fun removeContact(token: String, peer: String) {
        val response: StatusEnvelope = client.delete("$server/chat/contacts/$peer") {
            header("Authorization", "Bearer $token")
        }.body()
        require(response.status == 200) { response.message ?: "删除联系人失败" }
    }

    override suspend fun unread(token: String, contactId: String): Int {
        val tags = tagsFor(contactId)
        val query = if (tags.isEmpty()) "" else "?tags=" + tags.joinToString(",")
        val response: ApiEnvelope<Long> = client.get("$server/message/unread/$contactId$query") {
            header("Authorization", "Bearer $token")
        }.body()
        // 查不到时返回 0：角标宁可不显示，也不要显示假数字。
        return if (response.status == 200) (response.data ?: 0L).toInt() else 0
    }

    override suspend fun clearConversation(token: String, contactId: String, forBoth: Boolean) {
        val response: ApiEnvelope<ClearResultDto> = client.post("$server/message/clear") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(ClearRequest(peer = contactId, forBoth = forBoth, tags = tagsFor(contactId)))
        }.body()
        require(response.status == 200) { response.message ?: "清除会话失败" }
        forgetLocalConversation(contactId)
    }

    override fun forgetLocalConversation(contactId: String) {
        val mediaIds = messages
            .filter { it.contactId == contactId }
            .mapNotNull { it.payload?.let(::parsePayload)?.media?.id }
        messages.removeAll { it.contactId == contactId }
        vault.deleteMedia(mediaIds)
        serverIds.remove(contactId)
        vault.saveMessages(messages)
        vault.saveServerIds(serverIds)
    }

    override suspend fun login(
        username: String,
        password: String,
        deviceName: String,
        code: String?,
    ): LoginResult {
        cachedUsername = username
        cachedDeviceId = LocalVault.deviceIdFor(vaultRoot, username)
        val token = loginToken(username, password, cachedDeviceId, code)
        cachedToken = token

        vault = LocalVault(vaultRoot, username, cachedDeviceId)
        val registry = KeyRegistry(server)
        val stored = vault.loadBundle()
        bundle = if (stored == null) {
            registry
                .provision(token, password, cachedDeviceId, deviceName.ifBlank { "Compose Desktop" })
                .also { vault.saveBundle(it) }
        } else {
            // 本地私钥还在，但服务端可能没有这台设备（库被清理或换机）。
            // 设备行缺失时用同一份密钥重新登记；已注销的设备令牌会被拒，只能换设备。
            when (registry.deviceState(token, cachedDeviceId)) {
                DeviceState.UNREGISTERED -> registry.republish(
                    token, password, cachedDeviceId,
                    deviceName.ifBlank { "Compose Desktop" }, stored,
                )
                DeviceState.REVOKED -> throw IllegalStateException(
                    "本设备已被注销。请删除 ~/.mim/$cachedUsername 后重新登录" +
                        "（旧消息将无法解密），或换一台设备登录。",
                )
                DeviceState.ACTIVE, DeviceState.UNKNOWN -> Unit
            }
            stored
        }

        storeJson = vault.loadStore()
        messages = vault.loadMessages().distinctBy { it.id }.toMutableList()
        serverIds = vault.loadServerIds().toMutableMap()
        outbox = vault.loadOutbox().toMutableList()
        blockedPeers = vault.loadBlocked().toMutableSet()
        pinnedPeers = vault.loadPinned().toMutableSet()
        readMarks = vault.loadReadMarks().toMutableMap()
        seenSafetyCodes = vault.loadSafetyCodes().toMutableMap()
        return LoginResult(token, username, contacts(token))
    }

    override suspend fun contacts(token: String): List<Contact> {
        val groups: List<ChatContactGroup> = client.get("$server/chat/contacts") {
            header("Authorization", "Bearer $token")
        }.body()
        return groups.flatMap { group ->
            group.users.map { user ->
                val name = user.username ?: "未命名"
                Contact(
                    id = name,
                    name = user.labelName?.takeIf { it.isNotBlank() }
                        ?: user.nickname ?: name,
                    subtitle = if (user.online == true) "在线" else group.groupName.orEmpty(),
                    online = user.online == true,
                )
            }
        }
    }

    override suspend fun history(token: String, contactId: String): List<ChatMessage> =
        messages.filter { it.contactId == contactId }
            .sortedBy { it.epochMs }
            .map {
                val parsed = it.payload?.let(::parsePayload)
                val attachment = parsed?.let(::attachmentOf)
                ChatMessage(
                    id = it.id,
                    fromMe = it.fromMe,
                    text = if (it.recalled) "已撤回" else it.text,
                    time = it.time,
                    pending = it.pending,
                    read = it.read,
                    recalled = it.recalled,
                    canRecall = it.fromMe && it.recallToken != null && !it.recalled,
                    epochMs = it.epochMs,
                    kind = if (it.recalled) "text" else parsed?.kind ?: "text",
                    media = if (it.recalled) null else attachment,
                )
            }

    override suspend fun recall(token: String, contactId: String, messageId: String): ChatMessage? {
        val index = messages.indexOfFirst { it.id == messageId && it.fromMe }
        if (index < 0) {
            return null
        }
        val cached = messages[index]
        val recallToken = cached.recallToken ?: return null
        val response: ApiEnvelope<CipherMessageDto> = client.post("$server/message/recall/$messageId") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(RecallRequest(recallToken, tagsFor(contactId)))
        }.body()
        require(response.status == 200) { response.message ?: "撤回失败" }

        val updated = cached.copy(recalled = true, text = "已撤回", payload = null)
        messages[index] = updated
        vault.saveMessages(messages)
        return ChatMessage(
            id = updated.id,
            fromMe = true,
            text = "已撤回",
            time = updated.time,
            recalled = true,
        )
    }

    override suspend fun markRead(token: String, contactId: String) {
        val tags = tagsFor(contactId)
        if (tags.isEmpty()) {
            return
        }
        val beforeId = serverIds[contactId] ?: return
        val response: StatusEnvelope = client.post("$server/message/read") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(ReadRequest(peer = contactId, beforeId = beforeId, tags = tags))
        }.body()
        require(response.status == 200) { response.message ?: "标记已读失败" }
    }

    override suspend fun historyStates(token: String, contactId: String): List<MessageState> {
        val tags = tagsFor(contactId)
        if (tags.isEmpty()) {
            return emptyList()
        }
        val query = tags.joinToString(",")
        val history: List<CipherMessageDto> =
            getEnvelope("$server/message/history/$contactId?limit=50&tags=$query", token)
        val states = history.mapNotNull { message ->
            message.clientMsgId?.let {
                MessageState(it, message.readFlag == true, message.recalled == true)
            }
        }.toMutableList()
        // 队列里的消息服务端还看不到，pending 只能从本地补。
        messages.filter { it.contactId == contactId && it.pending }.forEach { cached ->
            if (states.none { it.id == cached.id }) {
                states += MessageState(cached.id, pending = true)
            }
        }
        return states
    }

    override suspend fun send(token: String, contactId: String, text: String): ChatMessage =
        sendEncoded(token, contactId, text, text, kind = "text", media = null, payloadJson = null)

    private suspend fun sendEncoded(
        token: String,
        contactId: String,
        wireText: String,
        displayText: String,
        kind: String,
        media: MediaAttachment?,
        payloadJson: String?,
    ): ChatMessage {
        val devices = peerDevices(contactId, token)
        require(devices.isNotEmpty()) { "对方还没有可用设备" }

        var workingStore = storeJson
        val peerTag = anyTag(workingStore, contactId)
        var certificate: CertJson? = null
        val clientMsgId = UUID.randomUUID().toString()
        val recallToken = newRecallToken()
        val replicas = mutableListOf<CipherMessageOut>()

        for (device in devices) {
            val deviceId = device.deviceId ?: continue
            val existing = findSession(workingStore, contactId, deviceId)
            if (existing != null && existing.tags.isNotEmpty()) {
                val composed = call(MimCoreNative::composeSealed) { json ->
                    json.encodeToString(
                        ComposeSealedRequestJson.serializer(),
                        ComposeSealedRequestJson(jsonOf(workingStore), existing.sessionId, wireText, null),
                    )
                }.let { JsonCodec.decode<ComposeSealedResponseJson>(it) }
                workingStore = composed.store.toString()
                replicas += CipherMessageOut(
                    clientMsgId = clientMsgId,
                    conversationTag = existing.tags.first(),
                    toUsername = contactId,
                    targetDeviceId = deviceId,
                    alg = ALGORITHM,
                    iv = composed.wire.iv,
                    ciphertext = composed.wire.ciphertext,
                    ratchetPublic = composed.wire.dh,
                    previousChainLength = composed.wire.pn,
                    messageNumber = composed.wire.n,
                    clientTime = System.currentTimeMillis(),
                    sessionId = existing.sessionId,
                    recallToken = recallToken,
                )
            } else {
                val preKey = preKeyBundle(contactId, deviceId, token) ?: continue
                val cert = certificate ?: fetchCertificate(token).also { certificate = it }
                val composed = call(MimCoreNative::composeFirstMessage) { json ->
                    json.encodeToString(
                        ComposeRequestJson.serializer(),
                        ComposeRequestJson(
                            identity_private = bundle.identity.privateKey,
                            identity_public = bundle.identity.publicKey,
                            peer_identity_public = Codec.base64ToHex(preKey.identityPublicKey),
                            peer_signed_pre_key_id = preKey.signedPreKeyId,
                            peer_signed_pre_key_public = Codec.base64ToHex(preKey.signedPreKeyPublic),
                            peer_one_time_pre_key_id = preKey.oneTimePreKeyId,
                            peer_one_time_pre_key_public = preKey.oneTimePreKeyPublic?.let(Codec::base64ToHex),
                            plaintext = wireText,
                            conversation_tag = peerTag,
                            session_id = UUID.randomUUID().toString(),
                            certificate = cert,
                        ),
                    )
                }.let { JsonCodec.decode<ComposeResponseJson>(it) }

                val inserted = call(MimCoreNative::sessionStoreInsert) { json ->
                    json.encodeToString(
                        InsertRequestJson.serializer(),
                        InsertRequestJson(
                            store = jsonOf(workingStore),
                            session_id = composed.session_id,
                            peer = contactId,
                            device_id = deviceId,
                            state = composed.state,
                            tags = listOf(composed.conversation_tag),
                        ),
                    )
                }.let { JsonCodec.decode<StoreResponseJson>(it) }
                workingStore = inserted.store.toString()

                replicas += CipherMessageOut(
                    clientMsgId = clientMsgId,
                    conversationTag = composed.conversation_tag,
                    toUsername = contactId,
                    targetDeviceId = deviceId,
                    alg = composed.alg,
                    iv = composed.wire.iv,
                    ciphertext = composed.wire.ciphertext,
                    ratchetPublic = composed.wire.dh,
                    previousChainLength = composed.wire.pn,
                    messageNumber = composed.wire.n,
                    senderIdentityKey = composed.sender_identity_key,
                    senderEphemeralKey = composed.sender_ephemeral_key,
                    senderUsedOneTimePreKey = composed.used_one_time_pre_key,
                    consumedOneTimePreKeyId = composed.consumed_one_time_pre_key_id,
                    recipientSignedPreKeyId = composed.recipient_signed_pre_key_id,
                    clientTime = System.currentTimeMillis(),
                    sessionId = composed.session_id,
                    recallToken = recallToken,
                )
            }
        }

        require(replicas.isNotEmpty()) { "没有可用预密钥，无法发起会话" }

        // 先推进并保存棘轮状态，再尝试投递：失败时队列只重发同一份密文，
        // 绝不重新组装，否则棘轮会多走一步、对端解不开。
        storeJson = workingStore
        vault.saveStore(workingStore)

        val entry = OutboxEntry(
            clientMsgId = clientMsgId,
            recallToken = recallToken,
            contactId = contactId,
            text = displayText,
            epochMs = System.currentTimeMillis(),
            replicas = replicas,
        )
        val delivered = runCatching {
            postList<List<CipherMessageOut>, List<CipherMessageDto>>("$server/message", token, replicas)
        }.isSuccess

        if (!delivered) {
            outbox += entry
            vault.saveOutbox(outbox)
        }

        val message = ChatMessage(
            clientMsgId,
            true,
            displayText,
            nowTime(),
            pending = !delivered,
            canRecall = true,
            epochMs = entry.epochMs,
            kind = kind,
            media = media,
        )
        cache(contactId, message, entry.epochMs, recallToken = recallToken, payload = payloadJson)
        vault.saveMessages(messages)
        return message
    }

    // ---------------- 媒体 ----------------

    override suspend fun sendImage(
        token: String,
        contactId: String,
        bytes: ByteArray,
        mime: String,
        name: String?,
        caption: String,
    ): ChatMessage = sendMedia(
        token = token,
        contactId = contactId,
        bytes = bytes,
        kind = "image",
        mime = mime,
        name = name,
        caption = caption,
        width = null,
        height = null,
        durationMs = null,
    )

    override suspend fun sendVoice(
        token: String,
        contactId: String,
        bytes: ByteArray,
        durationMs: Long,
        caption: String,
    ): ChatMessage = sendMedia(
        token = token,
        contactId = contactId,
        bytes = bytes,
        kind = "voice",
        mime = "audio/wav",
        name = null,
        caption = caption,
        width = null,
        height = null,
        durationMs = durationMs,
    )

    private suspend fun sendMedia(
        token: String,
        contactId: String,
        bytes: ByteArray,
        kind: String,
        mime: String,
        name: String?,
        caption: String,
        width: Int?,
        height: Int?,
        durationMs: Long?,
    ): ChatMessage {
        require(bytes.isNotEmpty()) { "媒体内容为空" }
        require(bytes.size <= MEDIA_MAX_BYTES) {
            "媒体文件过大，上限 ${MEDIA_MAX_BYTES / 1024 / 1024}MB"
        }

        val key = MimCoreNative.randomBytes(32)
        val nonce = MimCoreNative.randomBytes(12)
        val encrypted = MimCoreNative.encryptState(bytes, key, nonce)
            .toString(Charsets.UTF_8)
        val mediaId = uploadMedia(token, encrypted.toByteArray(Charsets.UTF_8))
        vault.saveMedia(mediaId, encrypted.toByteArray(Charsets.UTF_8))

        val attachment = MediaAttachment(
            id = mediaId,
            keyBase64 = java.util.Base64.getEncoder().encodeToString(key),
            mime = mime,
            size = bytes.size,
            name = name,
            width = width,
            height = height,
            durationMs = durationMs,
        )
        val payload = MediaPayload(
            kind = kind,
            text = caption,
            media = MediaRefJson(
                id = attachment.id,
                key = attachment.keyBase64,
                size = attachment.size,
                mime = attachment.mime,
                name = attachment.name,
                width = attachment.width,
                height = attachment.height,
                durationMs = attachment.durationMs,
            ),
        )
        val display = caption.ifBlank { if (kind == "image") "[图片]" else "[语音]" }
        return sendEncoded(
            token = token,
            contactId = contactId,
            wireText = JsonCodec.encode(payload),
            displayText = display,
            kind = kind,
            media = attachment,
            payloadJson = JsonCodec.encode(payload),
        )
    }

    private suspend fun uploadMedia(token: String, encrypted: ByteArray): String {
        val response: ApiEnvelope<MediaUploadDto> = client.post("$server/media") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.OctetStream)
            setBody(encrypted)
        }.body()
        require(response.status == 200) { response.message ?: "媒体上传失败" }
        return response.data?.id?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("媒体上传未返回标识")
    }

    override fun mediaBytes(message: ChatMessage): ByteArray? {
        val media = message.media ?: return null
        val cached = mediaCache[media.id] ?: run {
            val blob = vault.loadMedia(media.id) ?: return null
            val key = runCatching {
                java.util.Base64.getDecoder().decode(media.keyBase64)
            }.getOrNull() ?: return null
            val plain = runCatching {
                MimCoreNative.decryptState(
                    blob.toString(Charsets.UTF_8).toByteArray(Charsets.UTF_8),
                    key,
                )
            }.getOrNull() ?: return null
            rememberMedia(media.id, plain)
            plain
        }
        return cached
    }

    override fun searchLocal(query: String): List<LocalMessageHit> {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            return emptyList()
        }
        return messages.asSequence()
            .filter { it.text.contains(keyword, ignoreCase = true) }
            .sortedByDescending { it.epochMs }
            .take(200)
            .map { hit ->
                val parsed = hit.payload?.let(::parsePayload)
                LocalMessageHit(
                    contactId = hit.contactId,
                    messageId = hit.id,
                    text = hit.text,
                    time = hit.time,
                    epochMs = hit.epochMs,
                    fromMe = hit.fromMe,
                    kind = parsed?.kind ?: "text",
                )
            }
            .toList()
    }

    /** 小容量 LRU：避免滚动时反复解密同一张图。 */
    private fun rememberMedia(id: String, bytes: ByteArray) {
        mediaCache.remove(id)?.let { mediaCacheSize -= it.size }
        mediaCache[id] = bytes
        mediaCacheSize += bytes.size
        while (mediaCacheSize > MEDIA_CACHE_BYTES && mediaCache.isNotEmpty()) {
            val oldest = mediaCache.keys.firstOrNull() ?: break
            mediaCacheSize -= mediaCache.remove(oldest)?.size ?: 0
        }
    }

    override suspend fun poll(token: String): List<IncomingMessage> {
        retryOutbox(token)
        val incoming: List<CipherMessageDto> = getEnvelope("$server/message/undelivered", token)
        if (incoming.isEmpty()) {
            return emptyList()
        }
        var workingStore = storeJson
        val delivered = mutableListOf<IncomingMessage>()
        var maxId = 0L

        for (raw in incoming) {
            maxId = maxOf(maxId, raw.id ?: 0)
            val sessionId = raw.sessionId ?: continue
            runCatching {
                if (raw.recalled == true) {
                    val peer = raw.toUsername ?: return@runCatching
                    val id = raw.clientMsgId ?: UUID.randomUUID().toString()
                    val message = ChatMessage(id, false, "已撤回", nowTime(), recalled = true)
                    cache(peer, message, raw.clientTime ?: System.currentTimeMillis(), recalled = true)
                    delivered += IncomingMessage(peer, message)
                    return@runCatching
                }
                val wire = wireOf(raw)
                if (!raw.senderIdentityKey.isNullOrBlank() && !raw.senderEphemeralKey.isNullOrBlank()) {
                    val opened = openFirst(raw, wire, token)
                    val inserted = call(MimCoreNative::sessionStoreInsert) { json ->
                        json.encodeToString(
                            InsertRequestJson.serializer(),
                            InsertRequestJson(
                                store = jsonOf(workingStore),
                                session_id = sessionId,
                                peer = opened.certificate.username,
                                device_id = opened.certificate.device_id,
                                state = opened.state,
                                tags = listOfNotNull(opened.conversation_tag),
                            ),
                        )
                    }.let { JsonCodec.decode<StoreResponseJson>(it) }
                    workingStore = inserted.store.toString()
                    remember(
                        opened.certificate.username,
                        sessionId,
                        opened.plaintext,
                        raw,
                        token,
                        delivered,
                    )
                } else {
                    val opened = call(MimCoreNative::openSealed) { json ->
                        json.encodeToString(
                            OpenSealedRequestJson.serializer(),
                            OpenSealedRequestJson(jsonOf(workingStore), sessionId, wire),
                        )
                    }.let { JsonCodec.decode<OpenSealedResponseJson>(it) }
                    workingStore = opened.store.toString()
                    val peer = sessionPeer(workingStore, sessionId) ?: return@runCatching
                    remember(peer, sessionId, opened.plaintext, raw, token, delivered)
                }
            }
        }

        if (delivered.isNotEmpty()) {
            storeJson = workingStore
            vault.saveStore(workingStore)
            vault.saveMessages(messages)
            vault.saveServerIds(serverIds)
        }
        if (maxId > 0) {
            runCatching {
                client.post("$server/message/delivered?beforeId=$maxId") {
                    header("Authorization", "Bearer $token")
                }
            }
        }
        return delivered
    }

    private suspend fun retryOutbox(token: String) {
        if (outbox.isEmpty()) {
            return
        }
        val remaining = mutableListOf<OutboxEntry>()
        var changed = false
        for (entry in outbox) {
            val ok = runCatching {
                postList<List<CipherMessageOut>, List<CipherMessageDto>>(
                    "$server/message", token, entry.replicas,
                )
            }.isSuccess
            if (ok) {
                changed = true
                val index = messages.indexOfFirst { it.id == entry.clientMsgId }
                if (index >= 0) {
                    messages[index] = messages[index].copy(pending = false)
                }
            } else {
                remaining += entry
            }
        }
        outbox = remaining
        if (changed) {
            vault.saveMessages(messages)
        }
        vault.saveOutbox(outbox)
    }

    private suspend fun remember(
        peer: String,
        sessionId: String,
        plaintext: String,
        raw: CipherMessageDto,
        token: String,
        into: MutableList<IncomingMessage>,
    ) {
        val id = raw.clientMsgId ?: UUID.randomUUID().toString()
        raw.id?.let { serverIds[peer] = maxOf(serverIds[peer] ?: 0L, it) }
        // 已屏蔽：棘轮必须继续前进，否则后面的消息解不开；明文直接丢弃。
        if (peer in blockedPeers) {
            return
        }
        val parsed = parsePayload(plaintext)
        val attachment = parsed?.let(::attachmentOf)
        val display = when {
            parsed == null -> plaintext
            else -> parsed.text.ifBlank {
                if (parsed.kind == "image") "[图片]" else "[语音]"
            }
        }
        if (parsed != null && attachment != null && vault.loadMedia(attachment.id) == null) {
            // 媒体下载失败不影响消息本身；界面会显示「媒体不可用」。
            runCatching {
                val blob = downloadMedia(token, attachment.id)
                vault.saveMedia(attachment.id, blob)
            }
        }
        val message = ChatMessage(
            id = id,
            fromMe = false,
            text = display,
            time = nowTime(),
            epochMs = raw.clientTime ?: System.currentTimeMillis(),
            kind = parsed?.kind ?: "text",
            media = attachment,
        )
        cache(
            contactId = peer,
            message = message,
            epochMs = raw.clientTime ?: System.currentTimeMillis(),
            payload = if (parsed != null) plaintext else null,
        )
        into += IncomingMessage(peer, message)
    }

    private fun parsePayload(raw: String): MediaPayload? =
        if (!raw.startsWith(MEDIA_PREFIX)) {
            null
        } else {
            runCatching { JsonCodec.decode<MediaPayload>(raw) }.getOrNull()
        }

    private fun attachmentOf(payload: MediaPayload): MediaAttachment = MediaAttachment(
        id = payload.media.id,
        keyBase64 = payload.media.key,
        mime = payload.media.mime,
        size = payload.media.size,
        name = payload.media.name,
        width = payload.media.width,
        height = payload.media.height,
        durationMs = payload.media.durationMs,
    )

    private suspend fun downloadMedia(token: String, id: String): ByteArray {
        if (!MediaIds.isValid(id)) {
            throw IllegalStateException("媒体标识不合法")
        }
        val response = client.get("$server/media/$id") {
            header("Authorization", "Bearer $token")
        }
        require(response.status.value in 200..299) { "媒体下载失败：HTTP ${response.status.value}" }
        return response.body()
    }

    private fun cache(
        contactId: String,
        message: ChatMessage,
        epochMs: Long,
        recalled: Boolean = message.recalled,
        recallToken: String? = null,
        payload: String? = null,
    ) {
        if (messages.any { it.id == message.id }) {
            return
        }
        messages += CachedMessage(
            contactId = contactId,
            id = message.id,
            fromMe = message.fromMe,
            text = message.text,
            time = message.time,
            epochMs = epochMs,
            read = message.read,
            recalled = recalled,
            recallToken = recallToken,
            pending = message.pending,
            payload = payload,
        )
    }

    // ---------------- 网络 ----------------

    private suspend fun loginToken(
        username: String,
        password: String,
        deviceId: String,
        code: String? = null,
    ): String {
        val response: ApiEnvelope<String> = client.post("$server/login/token") {
            header("X-Device-Id", deviceId)
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password, code?.trim()?.takeIf { it.isNotEmpty() }))
        }.body()
        if (response.status == TWO_FACTOR_REQUIRED_CODE) {
            throw TwoFactorRequiredException(response.message ?: "请输入二次验证码或恢复码")
        }
        require(response.status == 200) { response.message ?: "登录失败" }
        return response.data ?: error("登录未返回令牌")
    }

    private suspend fun peerDevices(peer: String, token: String): List<DeviceKeyDto> =
        getEnvelope("$server/key/$peer", token)

    private suspend fun preKeyBundle(peer: String, deviceId: String, token: String): PreKeyBundleDto? {
        val bundles: List<PreKeyBundleDto> =
            getEnvelope("$server/prekey/$peer?deviceId=$deviceId", token)
        return bundles.firstOrNull { it.deviceId == deviceId }
    }

    private suspend fun fetchCertificate(token: String): CertJson {
        cachedCert?.let { return it }
        val cert: CertDto = postEnvelope("$server/cert/sender", token)
        val mapped = CertJson(
            username = cert.username,
            device_id = cert.deviceId,
            identity_key = cert.identityKey,
            issued_at = cert.issuedAt,
            expires_at = cert.expiresAt,
            key_id = cert.keyId,
            signature = cert.signature,
        )
        cachedCert = mapped
        return mapped
    }

    private suspend fun openFirst(
        raw: CipherMessageDto,
        wire: WireJson,
        token: String,
    ): OpenFirstResponseJson {
        val spk = bundle.signedPreKeys.firstOrNull { it.id == raw.recipientSignedPreKeyId }
            ?: error("找不到签名预密钥 ${raw.recipientSignedPreKeyId}")
        val opk = bundle.oneTimePreKeys.firstOrNull { it.id == raw.consumedOneTimePreKeyId }
        return call(MimCoreNative::openFirstMessage) { json ->
            json.encodeToString(
                OpenFirstRequestJson.serializer(),
                OpenFirstRequestJson(
                    identity_private = bundle.identity.privateKey,
                    identity_public = bundle.identity.publicKey,
                    signed_pre_key_private = spk.privateKey,
                    signed_pre_key_public = spk.publicKey,
                    one_time_pre_key_private = opk?.privateKey,
                    peer_identity_public = raw.senderIdentityKey!!,
                    peer_ephemeral_public = raw.senderEphemeralKey!!,
                    used_one_time_pre_key = raw.senderUsedOneTimePreKey == true,
                    wire = wire,
                ),
            )
        }.let { JsonCodec.decode<OpenFirstResponseJson>(it) }
    }

    private inline fun call(block: (ByteArray) -> ByteArray, encode: (Json) -> String): String =
        block(encode(JsonCodec.json).toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8)

    private suspend inline fun <reified T> getEnvelope(path: String, token: String): T {
        val response: ApiEnvelope<T> = client.get(path) {
            header("Authorization", "Bearer $token")
        }.body()
        require(response.status == 200) { response.message ?: "请求失败：$path" }
        return response.data ?: error("响应缺少数据：$path")
    }

    private suspend inline fun <reified T> postEnvelope(path: String, token: String): T {
        val response: ApiEnvelope<T> = client.post(path) {
            header("Authorization", "Bearer $token")
        }.body()
        require(response.status == 200) { response.message ?: "请求失败：$path" }
        return response.data ?: error("响应缺少数据：$path")
    }

    private suspend inline fun <reified Req, reified Res> postList(
        path: String,
        token: String,
        body: Req,
    ): Res {
        val response: ApiEnvelope<Res> = client.post(path) {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
        require(response.status == 200) { response.message ?: "请求失败：$path" }
        return response.data ?: error("响应缺少数据：$path")
    }

    // ---------------- 会话索引 ----------------

    private fun jsonOf(store: String): JsonElement = Json.parseToJsonElement(store)

    private fun findSession(store: String, peer: String, deviceId: String): SessionMeta? =
        runCatching { JsonCodec.decode<StoreJson>(store) }.getOrNull()
            ?.sessions?.firstOrNull { it.peer == peer && it.deviceId == deviceId }

    private fun anyTag(store: String, peer: String): String? =
        runCatching { JsonCodec.decode<StoreJson>(store) }.getOrNull()
            ?.sessions?.firstOrNull { it.peer == peer && it.tags.isNotEmpty() }
            ?.tags?.firstOrNull()

    private fun tagsFor(peer: String): List<String> =
        runCatching { JsonCodec.decode<StoreJson>(storeJson) }.getOrNull()
            ?.sessions?.filter { it.peer == peer }
            ?.flatMap { it.tags }
            ?.distinct()
            .orEmpty()

    private fun newRecallToken(): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MimCoreNative.randomBytes(32))

    private fun sessionPeer(store: String, sessionId: String): String? =
        runCatching { JsonCodec.decode<StoreJson>(store) }.getOrNull()
            ?.sessions?.firstOrNull { it.sessionId == sessionId }
            ?.peer

    private fun wireOf(raw: CipherMessageDto): WireJson = WireJson(
        dh = raw.ratchetPublic ?: error("缺少棘轮公钥"),
        pn = raw.previousChainLength ?: 0,
        n = raw.messageNumber ?: 0,
        iv = raw.iv ?: error("缺少 IV"),
        ciphertext = raw.ciphertext ?: error("缺少密文"),
    )

    override suspend fun devices(token: String): List<DeviceInfo> {
        val response: ApiEnvelope<DeviceListDto> = client.get("$server/device") {
            header("Authorization", "Bearer $token")
        }.body()
        require(response.status == 200) { response.message ?: "查询设备失败" }
        return response.data?.devices.orEmpty().mapNotNull { device ->
            val id = device.deviceId ?: return@mapNotNull null
            DeviceInfo(
                deviceId = id,
                deviceName = device.deviceName.orEmpty(),
                lastSeen = device.lastSeen,
                current = id == cachedDeviceId,
            )
        }
    }

    override suspend fun revokeDevice(token: String, deviceId: String) {
        val response = client.delete("$server/device/$deviceId") {
            header("Authorization", "Bearer $token")
        }
        val envelope: StatusEnvelope = response.body()
        require(envelope.status == 200) { envelope.message ?: "注销设备失败" }
    }

    override suspend fun peerIdentityKeys(token: String, contactId: String): List<PeerIdentityKey> =
        getEnvelope<List<DeviceKeyDto>>("$server/key/$contactId", token).mapNotNull { device ->
            val id = device.deviceId ?: return@mapNotNull null
            val key = device.publicKey ?: return@mapNotNull null
            PeerIdentityKey(id, key)
        }

    override fun ownIdentityKeyBase64(): String = Codec.hexToBase64(bundle.identity.publicKey)

    /** 只做百分号转义，不引入整段 URL 编码依赖。 */
    private fun encodeQuery(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private fun nowTime(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    companion object {
        const val ALGORITHM = "X3DH-P256-DOUBLE-RATCHET-AES-256-GCM"
        const val TERMS_VERSION = "2026-11"
        /** 服务端业务状态码：账号开启了二次验证。 */
        private const val TWO_FACTOR_REQUIRED_CODE = 40102

        private const val TYPE_NEW_MESSAGE = 100010
        private const val TYPE_READ_RECEIPT = 100012
        private const val TYPE_RECALLED = 100014
        private const val TYPE_CONVERSATION_CLEARED = 100019
        private const val TYPE_FRIEND_REQUEST = 100016
        private const val TYPE_FRIEND_ACCEPTED = 100017
        private const val TYPE_FRIEND_REJECTED = 100018

        private const val MEDIA_MAX_BYTES = 8 * 1024 * 1024
        private const val MEDIA_CACHE_BYTES = 32L * 1024 * 1024
        /** 媒体载荷的固定前缀，用来与旧版纯文本消息区分。 */
        private const val MEDIA_PREFIX = "{\"v\":1,\"kind\":"
    }
}

@kotlinx.serialization.Serializable
private data class PushEnvelope(val type: Int = 0, val data: JsonElement? = null)
