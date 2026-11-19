package com.junesoon.mim.app.data

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 设备登记与密钥上传联调。需要同时满足：
 * - `MIM_SERVER_URL` 指向运行中的服务端
 * - `MIM_NATIVE_LIB` 指向 Rust 动态库（密钥由核心生成）
 * 否则跳过，避免在无环境时假失败。
 */
class KeyRegistryTest {

    @Test
    fun provision_uploads_identity_and_prekeys() = withServer { server ->
        val username = "composekeys"
        val password = TestAccounts.PASSWORD
        val deviceId = TestAccounts.uniqueDevice("keys")

        TestAccounts.ensure(server, username)
        val login = HttpBackend(server).login(username, password, deviceId)

        val registry = KeyRegistry(server)
        val bundle = registry.provision(
            token = login.token,
            password = password,
            deviceId = deviceId,
            deviceName = "Compose Desktop",
            oneTimeCount = 20,
        )
        assertEquals(1, bundle.version)
        assertEquals(1, bundle.signedPreKeys.size)
        assertEquals(20, bundle.oneTimePreKeys.size)

        val uploaded: ApiEnvelope<List<KeyBundleDto>> = client.get(
            "$server/key/$username?deviceId=$deviceId",
        ) {
            header("Authorization", "Bearer ${login.token}")
        }.body()
        assertEquals(200, uploaded.status, uploaded.message)
        val key = uploaded.data?.firstOrNull()
        assertTrue(key != null, "服务端未返回已上传的公钥")
        assertEquals(1, key.version)
        assertEquals("EC-P256", key.algorithm)
        assertTrue(key.fingerprint.length == 64, "指纹应为 SHA-256 十六进制")

        assertEquals(20, registry.oneTimePreKeyCount(login.token, deviceId))
    }

    private fun withServer(block: suspend (server: String) -> Unit) {
        val server = System.getenv("MIM_SERVER_URL") ?: return
        if (System.getenv("MIM_NATIVE_LIB").isNullOrBlank()) return
        runBlocking { block(server) }
    }

    private suspend fun registerUser(server: String, username: String, password: String) {
        val response = client.post("$server/register/token") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(username, password, agreedTerms = true, termsVersion = "2026-11"))
        }
        val envelope: StatusEnvelope = response.body()
        assertEquals(200, envelope.status, envelope.message)
    }

    private val client: HttpClient by lazy {
        HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    @Serializable
    private data class RegisterRequest(
        val username: String,
        val password: String,
        val agreedTerms: Boolean,
        val termsVersion: String,
    )

    @Serializable
    private data class StatusEnvelope(val status: Int = 0, val message: String? = null)

    @Serializable
    private data class ApiEnvelope<T>(
        val status: Int = 0,
        val message: String? = null,
        val data: T? = null,
    )

    @Serializable
    private data class KeyBundleDto(
        val version: Int = 0,
        val algorithm: String? = null,
        val fingerprint: String = "",
    )
}
