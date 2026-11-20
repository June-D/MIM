package com.junesoon.mim.app.data

import com.junesoon.mim.app.data.messaging.ApiEnvelope
import com.junesoon.mim.app.data.messaging.CertDto
import com.junesoon.mim.app.data.messaging.CertJson
import com.junesoon.mim.app.data.messaging.ChatContactGroup
import com.junesoon.mim.app.data.messaging.CipherMessageDto
import com.junesoon.mim.app.data.messaging.CipherMessageOut
import com.junesoon.mim.app.data.messaging.ComposeRequestJson
import com.junesoon.mim.app.data.messaging.ComposeResponseJson
import com.junesoon.mim.app.data.messaging.ComposeSealedRequestJson
import com.junesoon.mim.app.data.messaging.ComposeSealedResponseJson
import com.junesoon.mim.app.data.messaging.DeviceKeyDto
import com.junesoon.mim.app.data.messaging.InsertRequestJson
import com.junesoon.mim.app.data.messaging.OpenFirstRequestJson
import com.junesoon.mim.app.data.messaging.OpenFirstResponseJson
import com.junesoon.mim.app.data.messaging.OpenSealedRequestJson
import com.junesoon.mim.app.data.messaging.OpenSealedResponseJson
import com.junesoon.mim.app.data.messaging.PreKeyBundleDto
import com.junesoon.mim.app.data.messaging.ReadRequest
import com.junesoon.mim.app.data.messaging.RecallRequest
import com.junesoon.mim.app.data.messaging.SessionMeta
import com.junesoon.mim.app.data.messaging.StatusEnvelope
import com.junesoon.mim.app.data.messaging.StoreJson
import com.junesoon.mim.app.data.messaging.StoreResponseJson
import com.junesoon.mim.app.data.messaging.WireJson
import com.junesoon.mim.app.domain.ChatMessage
import com.junesoon.mim.app.domain.Contact
import com.junesoon.mim.core.MimCoreNative
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
    },
) : MessagingBackend {

    private lateinit var vault: LocalVault
    private lateinit var bundle: RustKeyBundle
    private var storeJson: String = LocalVault.EMPTY_STORE
    private var messages: MutableList<CachedMessage> = mutableListOf()
    private var serverIds: MutableMap<String, Long> = mutableMapOf()
    private var outbox: MutableList<OutboxEntry> = mutableListOf()
    private var cachedCert: CertJson? = null
    private var cachedToken: String = ""
    private var cachedDeviceId: String = ""
    private var cachedUsername: String = ""

    /** 当前登录的设备与用户名（测试与设备管理界面用）。 */
    val currentDeviceId: String get() = cachedDeviceId

    override suspend fun login(username: String, password: String, deviceName: String): LoginResult {
        cachedUsername = username
        cachedDeviceId = LocalVault.deviceIdFor(vaultRoot, username)
        val token = loginToken(username, password, cachedDeviceId)
        cachedToken = token

        vault = LocalVault(vaultRoot, username, cachedDeviceId)
        bundle = vault.loadBundle() ?: KeyRegistry(server)
            .provision(token, password, cachedDeviceId, deviceName.ifBlank { "Compose Desktop" })
            .also { vault.saveBundle(it) }

        storeJson = vault.loadStore()
        messages = vault.loadMessages().distinctBy { it.id }.toMutableList()
        serverIds = vault.loadServerIds().toMutableMap()
        outbox = vault.loadOutbox().toMutableList()
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
                    name = user.nickname ?: user.labelName ?: name,
                    subtitle = if (user.online == true) "在线" else group.groupName.orEmpty(),
                )
            }
        }
    }

    override suspend fun history(token: String, contactId: String): List<ChatMessage> =
        messages.filter { it.contactId == contactId }
            .sortedBy { it.epochMs }
            .map {
                ChatMessage(
                    id = it.id,
                    fromMe = it.fromMe,
                    text = if (it.recalled) "已撤回" else it.text,
                    time = it.time,
                    pending = it.pending,
                    read = it.read,
                    recalled = it.recalled,
                    canRecall = it.fromMe && it.recallToken != null && !it.recalled,
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

        val updated = cached.copy(recalled = true, text = "已撤回")
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

    override suspend fun send(token: String, contactId: String, text: String): ChatMessage {
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
                        ComposeSealedRequestJson(jsonOf(workingStore), existing.sessionId, text, null),
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
                            plaintext = text,
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
            text = text,
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

        val message = ChatMessage(clientMsgId, true, text, nowTime(), pending = !delivered, canRecall = true)
        cache(contactId, message, entry.epochMs, recallToken = recallToken)
        vault.saveMessages(messages)
        return message
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
                    remember(opened.certificate.username, sessionId, opened.plaintext, raw, delivered)
                } else {
                    val opened = call(MimCoreNative::openSealed) { json ->
                        json.encodeToString(
                            OpenSealedRequestJson.serializer(),
                            OpenSealedRequestJson(jsonOf(workingStore), sessionId, wire),
                        )
                    }.let { JsonCodec.decode<OpenSealedResponseJson>(it) }
                    workingStore = opened.store.toString()
                    val peer = sessionPeer(workingStore, sessionId) ?: return@runCatching
                    remember(peer, sessionId, opened.plaintext, raw, delivered)
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
        into: MutableList<IncomingMessage>,
    ) {
        val id = raw.clientMsgId ?: UUID.randomUUID().toString()
        val message = ChatMessage(id, false, plaintext, nowTime())
        cache(peer, message, raw.clientTime ?: System.currentTimeMillis())
        raw.id?.let { serverIds[peer] = maxOf(serverIds[peer] ?: 0L, it) }
        into += IncomingMessage(peer, message)
    }

    private fun cache(
        contactId: String,
        message: ChatMessage,
        epochMs: Long,
        recalled: Boolean = message.recalled,
        recallToken: String? = null,
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
        )
    }

    // ---------------- 网络 ----------------

    private suspend fun loginToken(username: String, password: String, deviceId: String): String {
        val response: ApiEnvelope<String> = client.post("$server/login/token") {
            header("X-Device-Id", deviceId)
            contentType(ContentType.Application.Json)
            setBody(mapOf("username" to username, "password" to password))
        }.body()
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

    private fun nowTime(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    companion object {
        const val ALGORITHM = "X3DH-P256-DOUBLE-RATCHET-AES-256-GCM"
    }
}
