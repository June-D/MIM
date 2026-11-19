package com.junesoon.mim.app.data

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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 首条消息端到端：A 用 Rust 组装 → 服务端落库 → B 用 Rust 拆解。
 *
 * 需要 `MIM_SERVER_URL` 与 `MIM_NATIVE_LIB`；缺任一项则跳过。
 */
class FirstMessageTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun first_message_round_trip_through_server() = withServer { server ->
        val alice = "composealice"
        val bob = "composebob"
        val password = TestAccounts.PASSWORD
        val aliceDevice = TestAccounts.uniqueDevice("alice")
        val bobDevice = TestAccounts.uniqueDevice("bob")

        val aliceBundle = provision(server, alice, password, aliceDevice)
        val bobBundle = provision(server, bob, password, bobDevice)

        val aliceLogin = HttpBackend(server).login(alice, password, aliceDevice)
        val bobLogin = HttpBackend(server).login(bob, password, bobDevice)

        // B 的预密钥包（会消耗一个一次性预密钥）
        val bundles: List<PreKeyBundleDto> =
            getEnvelope(server, "/prekey/$bob?deviceId=$bobDevice", aliceLogin.token)
        val bundle = bundles.firstOrNull { it.deviceId == bobDevice }
        assertTrue(bundle != null, "未取到 B 的预密钥包")

        // A 的发送者证书
        val cert = postEnvelope<CertDto>(server, "/cert/sender", aliceLogin.token)
        assertTrue(cert.username == alice)

        val sessionId = UUID.randomUUID().toString()
        val composeRequest = ComposeRequestJson(
            identity_private = aliceBundle.identity.privateKey,
            identity_public = aliceBundle.identity.publicKey,
            peer_identity_public = base64ToHex(bundle!!.identityPublicKey),
            peer_signed_pre_key_id = bundle.signedPreKeyId,
            peer_signed_pre_key_public = base64ToHex(bundle.signedPreKeyPublic),
            peer_one_time_pre_key_id = bundle.oneTimePreKeyId,
            peer_one_time_pre_key_public = bundle.oneTimePreKeyPublic?.let(::base64ToHex),
            plaintext = "首条真实消息",
            conversation_tag = null,
            session_id = sessionId,
            certificate = CertJson(
                username = cert.username,
                device_id = cert.deviceId,
                identity_key = cert.identityKey,
                issued_at = cert.issuedAt,
                expires_at = cert.expiresAt,
                key_id = cert.keyId,
                signature = cert.signature,
            ),
            iv = null,
        )

        val composed: ComposeResponseJson = json.decodeFromString(
            MimCoreNative.composeFirstMessage(
                json.encodeToString(ComposeRequestJson.serializer(), composeRequest).toByteArray(Charsets.UTF_8),
            ).toString(Charsets.UTF_8),
        )

        val clientMsgId = UUID.randomUUID().toString()
        val outbound = CipherMessageOut(
            clientMsgId = clientMsgId,
            conversationTag = composed.conversation_tag,
            toUsername = bob,
            targetDeviceId = bobDevice,
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
            sealed = true,
            sessionId = sessionId,
        )
        val sent: List<CipherMessageDto> = postList<List<CipherMessageOut>, List<CipherMessageDto>>(
            server, "/message", aliceLogin.token, listOf(outbound),
        )
        assertEquals(1, sent.size)
        assertEquals(clientMsgId, sent.first().clientMsgId)

        // B 拉离线消息并用 Rust 拆解
        val inbox = getEnvelope<List<CipherMessageDto>>(server, "/message/undelivered", bobLogin.token)
        val incoming = inbox.firstOrNull { it.clientMsgId == clientMsgId }
        assertTrue(incoming != null, "B 未收到这条消息")
        assertTrue(incoming.senderIdentityKey != null && incoming.senderEphemeralKey != null)

        val bSpk = bobBundle.signedPreKeys.firstOrNull { it.id == incoming.recipientSignedPreKeyId }
        assertTrue(bSpk != null, "找不到对应的签名预密钥")
        val bOpk = bobBundle.oneTimePreKeys.firstOrNull { it.id == incoming.consumedOneTimePreKeyId }

        val openRequest = OpenFirstRequestJson(
            identity_private = bobBundle.identity.privateKey,
            identity_public = bobBundle.identity.publicKey,
            signed_pre_key_private = bSpk!!.privateKey,
            signed_pre_key_public = bSpk.publicKey,
            one_time_pre_key_private = bOpk?.privateKey,
            peer_identity_public = incoming.senderIdentityKey,
            peer_ephemeral_public = incoming.senderEphemeralKey,
            used_one_time_pre_key = incoming.senderUsedOneTimePreKey == true,
            wire = WireJson(
                dh = incoming.ratchetPublic!!,
                pn = incoming.previousChainLength!!,
                n = incoming.messageNumber!!,
                iv = incoming.iv!!,
                ciphertext = incoming.ciphertext!!,
            ),
            next_dh_private = null,
            next_dh_public = null,
        )

        val opened: OpenFirstResponseJson = json.decodeFromString(
            MimCoreNative.openFirstMessage(
                json.encodeToString(OpenFirstRequestJson.serializer(), openRequest).toByteArray(Charsets.UTF_8),
            ).toString(Charsets.UTF_8),
        )

        assertEquals("首条真实消息", opened.plaintext)
        assertEquals(alice, opened.certificate.username)
        assertEquals(aliceDevice, opened.certificate.device_id)
        assertEquals(composed.conversation_tag, opened.conversation_tag)
        assertTrue(opened.certificate.identity_key == cert.identityKey, "证书身份公钥必须一致")
    }

    private suspend fun provision(
        server: String,
        user: String,
        password: String,
        device: String,
    ): RustKeyBundle {
        awaitRegister(server, user, password)
        val login = HttpBackend(server).login(user, password, device)
        return KeyRegistry(server).provision(login.token, password, device, "Compose Test")
    }

    private suspend fun awaitRegister(server: String, user: String, password: String) {
        TestAccounts.ensure(server, user)
    }

    private inline fun <reified T> postEnvelope(server: String, path: String, token: String): T =
        runBlocking {
            val response = client.post("$server$path") {
                header("Authorization", "Bearer $token")
            }
            unwrap<T>(response.body())
        }

    private inline fun <reified T> getEnvelope(server: String, path: String, token: String): T =
        runBlocking {
            val response = client.get("$server$path") {
                header("Authorization", "Bearer $token")
            }
            unwrap<T>(response.body())
        }

    private inline fun <reified Req, reified Res> postList(
        server: String,
        path: String,
        token: String,
        body: Req,
    ): Res = runBlocking {
        val response = client.post("$server$path") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        unwrap<Res>(response.body())
    }

    private inline fun <reified T> unwrap(envelope: Envelope<T>): T {
        assertEquals(200, envelope.status, envelope.message)
        return envelope.data ?: throw IllegalStateException("响应缺少数据")
    }

    private fun withServer(block: suspend (String) -> Unit) {
        val server = System.getenv("MIM_SERVER_URL") ?: return
        if (System.getenv("MIM_NATIVE_LIB").isNullOrBlank()) return
        runBlocking { block(server) }
    }

    private val client: HttpClient by lazy {
        HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    private fun base64ToHex(value: String): String =
        Base64.getDecoder().decode(value).joinToString("") { "%02x".format(it) }

    // ---------------- DTO ----------------

    @Serializable
    private data class Envelope<T>(
        val status: Int = 0,
        val message: String? = null,
        val data: T? = null,
    )

    @Serializable
    private data class StatusEnvelope(val status: Int = 0, val message: String? = null)

    @Serializable
    private data class RegisterRequest(
        val username: String,
        val password: String,
        val agreedTerms: Boolean,
        val termsVersion: String,
    )

    @Serializable
    private data class PreKeyBundleDto(
        val deviceId: String? = null,
        val identityPublicKey: String,
        val signedPreKeyId: Int,
        val signedPreKeyPublic: String,
        val oneTimePreKeyId: Int? = null,
        val oneTimePreKeyPublic: String? = null,
    )

    @Serializable
    private data class CertDto(
        val username: String,
        val deviceId: String,
        val identityKey: String,
        val issuedAt: Long,
        val expiresAt: Long,
        val keyId: String,
        val signature: String,
    )

    @Serializable
    private data class CertJson(
        val username: String,
        val device_id: String,
        val identity_key: String,
        val issued_at: Long,
        val expires_at: Long,
        val key_id: String,
        val signature: String,
    )

    @Serializable
    private data class ComposeRequestJson(
        val identity_private: String,
        val identity_public: String,
        val peer_identity_public: String,
        val peer_signed_pre_key_id: Int,
        val peer_signed_pre_key_public: String,
        val peer_one_time_pre_key_id: Int? = null,
        val peer_one_time_pre_key_public: String? = null,
        val plaintext: String,
        val conversation_tag: String? = null,
        val session_id: String,
        val certificate: CertJson,
        val iv: String? = null,
    )

    @Serializable
    private data class WireJson(
        val dh: String,
        val pn: Int,
        val n: Int,
        val iv: String,
        val ciphertext: String,
    )

    @Serializable
    private data class ComposeResponseJson(
        val wire: WireJson,
        val alg: String,
        val conversation_tag: String,
        val session_id: String,
        val sender_identity_key: String,
        val sender_ephemeral_key: String,
        val used_one_time_pre_key: Boolean,
        val consumed_one_time_pre_key_id: Int? = null,
        val recipient_signed_pre_key_id: Int,
    )

    @Serializable
    private data class OpenFirstRequestJson(
        val identity_private: String,
        val identity_public: String,
        val signed_pre_key_private: String,
        val signed_pre_key_public: String,
        val one_time_pre_key_private: String? = null,
        val peer_identity_public: String,
        val peer_ephemeral_public: String,
        val used_one_time_pre_key: Boolean,
        val wire: WireJson,
        val next_dh_private: String? = null,
        val next_dh_public: String? = null,
    )

    @Serializable
    private data class OpenFirstResponseJson(
        val plaintext: String,
        val certificate: CertJson,
        val conversation_tag: String? = null,
    )

    @Serializable
    private data class CipherMessageOut(
        val clientMsgId: String,
        val conversationTag: String,
        val toUsername: String,
        val targetDeviceId: String,
        val alg: String,
        val iv: String,
        val ciphertext: String,
        val ratchetPublic: String,
        val previousChainLength: Int,
        val messageNumber: Int,
        val senderIdentityKey: String,
        val senderEphemeralKey: String,
        val senderUsedOneTimePreKey: Boolean,
        val consumedOneTimePreKeyId: Int?,
        val recipientSignedPreKeyId: Int,
        val clientTime: Long,
        val sealed: Boolean,
        val sessionId: String,
    )

    @Serializable
    private data class CipherMessageDto(
        val clientMsgId: String? = null,
        val toUsername: String? = null,
        val targetDeviceId: String? = null,
        val alg: String? = null,
        val iv: String? = null,
        val ciphertext: String? = null,
        val ratchetPublic: String? = null,
        val previousChainLength: Int? = null,
        val messageNumber: Int? = null,
        val senderIdentityKey: String? = null,
        val senderEphemeralKey: String? = null,
        val senderUsedOneTimePreKey: Boolean? = null,
        val consumedOneTimePreKeyId: Int? = null,
        val recipientSignedPreKeyId: Int? = null,
        val sessionId: String? = null,
    )
}
