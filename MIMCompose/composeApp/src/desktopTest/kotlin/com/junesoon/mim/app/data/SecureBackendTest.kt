package com.junesoon.mim.app.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真实后端端到端：A 发送 → B 轮询解密 → B 回信 → A 轮询解密，
 * 并验证重启（新建后端实例、同一保险库）后缓存仍能读出。
 */
class SecureBackendTest {

    @Test
    fun two_way_messaging_and_cache_survive_restart() = withEnv { server ->
        val alice = "composealice"
        val bob = "composebob"
        TestAccounts.ensure(server, alice)
        TestAccounts.ensure(server, bob)

        val root = Files.createTempDirectory("mim-vault")

        val aliceBackend = SecureBackend(server, root)
        val aliceLogin = aliceBackend.login(alice, TestAccounts.PASSWORD, "Alice Desktop")
        pruneOtherDevices(server, aliceLogin.token, aliceBackend.currentDeviceId)

        val bobBackend = SecureBackend(server, root)
        val bobLogin = bobBackend.login(bob, TestAccounts.PASSWORD, "Bob Phone")
        pruneOtherDevices(server, bobLogin.token, bobBackend.currentDeviceId)

        aliceBackend.send(aliceLogin.token, bob, "secure hello")
        val received = bobBackend.poll(bobLogin.token)
        val incoming = received.firstOrNull { it.contactId == alice }
        assertTrue(incoming != null, "B 未收到 A 的消息；收到：$received")
        assertEquals("secure hello", incoming.message.text)

        bobBackend.send(bobLogin.token, alice, "secure reply")
        val reply = aliceBackend.poll(aliceLogin.token).firstOrNull { it.contactId == bob }
        assertTrue(reply != null, "A 未收到回信")
        assertEquals("secure reply", reply.message.text)

        // 重启：同一保险库、同一设备，历史来自加密缓存。
        val aliceAgain = SecureBackend(server, root)
        aliceAgain.login(alice, TestAccounts.PASSWORD, "Alice Desktop")
        val history = aliceAgain.history(aliceLogin.token, bob)
        assertTrue(history.any { it.fromMe && it.text == "secure hello" }, "发送记录应保留")
        assertTrue(history.any { !it.fromMe && it.text == "secure reply" }, "接收记录应保留")
    }

    /** 清掉历史遗留设备，避免多设备分发把测试放大。 */
    private suspend fun pruneOtherDevices(server: String, token: String, keep: String) {
        val response: Envelope<DeviceList> = client.get("$server/device") {
            header("Authorization", "Bearer $token")
        }.body()
        if (response.status != 200) return
        for (device in response.data?.devices.orEmpty()) {
            val id = device.deviceId ?: continue
            if (id == keep) continue
            runCatching {
                client.delete("$server/device/$id") {
                    header("Authorization", "Bearer $token")
                }
            }
        }
    }

    private fun withEnv(block: suspend (String) -> Unit) {
        val server = System.getenv("MIM_SERVER_URL") ?: return
        if (System.getenv("MIM_NATIVE_LIB").isNullOrBlank()) return
        runBlocking { block(server) }
    }

    private val client: HttpClient by lazy {
        HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    @Serializable
    private data class Envelope<T>(
        val status: Int = 0,
        val message: String? = null,
        val data: T? = null,
    )

    @Serializable
    private data class DeviceList(
        val count: Int = 0,
        val devices: List<DeviceDto> = emptyList(),
    )

    @Serializable
    private data class DeviceDto(val deviceId: String? = null)
}
