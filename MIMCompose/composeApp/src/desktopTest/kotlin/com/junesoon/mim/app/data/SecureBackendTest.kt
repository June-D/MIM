package com.junesoon.mim.app.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

        val aliceBackend = SecureBackend(server, root, pruneOtherDevices = true)
        val aliceLogin = aliceBackend.login(alice, TestAccounts.PASSWORD, "Alice Desktop")
        pruneOtherDevices(server, aliceLogin.token, aliceBackend.currentDeviceId)

        val bobBackend = SecureBackend(server, root, pruneOtherDevices = true)
        val bobLogin = bobBackend.login(bob, TestAccounts.PASSWORD, "Bob Phone")
        pruneOtherDevices(server, bobLogin.token, bobBackend.currentDeviceId)

        // 推送：A 发送后 B 的监听应立刻收到信号（不依赖轮询）。
        val signal = CompletableDeferred<Unit>()
        val listener = launch(Dispatchers.Default) {
            bobBackend.listenPush { signal.complete(Unit) }
        }
        delay(800)

        // 设备列表包含本机
        val devices = aliceBackend.devices(aliceLogin.token)
        assertTrue(
            devices.any { it.current && it.deviceId == aliceBackend.currentDeviceId },
            "设备列表应包含本机：$devices",
        )

        // 安全码对称：双方对同一设备对算出的 60 位码必须相同
        val aliceCode = safetyCode(aliceBackend, aliceLogin.token, bob, bobBackend.currentDeviceId)
        val bobCode = safetyCode(bobBackend, bobLogin.token, alice, aliceBackend.currentDeviceId)
        assertTrue(aliceCode != null, "A 应能算出与 B 的安全码")
        assertEquals(aliceCode, bobCode, "安全码必须对称")

        val sent = aliceBackend.send(aliceLogin.token, bob, "secure hello")
        withTimeout(10_000) { signal.await() }
        listener.cancel()
        val received = bobBackend.poll(bobLogin.token)
        val incoming = received.firstOrNull { it.contactId == alice }
        assertTrue(incoming != null, "B 未收到 A 的消息；收到：$received")
        assertEquals("secure hello", incoming.message.text)

        // 未读数：B 还没读时至少 1，标记已读后归零。
        assertTrue(
            bobBackend.unread(bobLogin.token, alice) >= 1,
            "B 应有未读：${bobBackend.unread(bobLogin.token, alice)}",
        )

        // 已读回执：B 标记已读后，A 通过历史状态看到 read。
        bobBackend.markRead(bobLogin.token, alice)
        assertEquals(0, bobBackend.unread(bobLogin.token, alice), "标记已读后未读应为 0")
        val readState = aliceBackend.historyStates(aliceLogin.token, bob)
            .firstOrNull { it.id == sent.id }
        assertTrue(readState?.read == true, "A 应看到已读：$readState")

        // 撤回：A 撤回后，B 通过历史状态看到 recalled。
        val recalled = aliceBackend.recall(aliceLogin.token, bob, sent.id)
        assertTrue(recalled?.recalled == true, "撤回应返回已撤回状态")
        val recallState = bobBackend.historyStates(bobLogin.token, alice)
            .firstOrNull { it.id == sent.id }
        assertTrue(recallState?.recalled == true, "B 应看到已撤回：$recallState")

        bobBackend.send(bobLogin.token, alice, "secure reply")
        val reply = aliceBackend.poll(aliceLogin.token).firstOrNull { it.contactId == bob }
        assertTrue(reply != null, "A 未收到回信")
        assertEquals("secure reply", reply.message.text)

        // 重启：同一保险库、同一设备，历史来自加密缓存。
        val aliceAgain = SecureBackend(server, root)
        aliceAgain.login(alice, TestAccounts.PASSWORD, "Alice Desktop")
        val history = aliceAgain.history(aliceLogin.token, bob)
        assertTrue(history.any { it.fromMe && it.recalled && it.id == sent.id }, "撤回记录应保留")
        assertTrue(history.any { !it.fromMe && it.text == "secure reply" }, "接收记录应保留")

        // 清除：只清自己的，对方仍看得到；本地缓存同步丢弃。
        aliceAgain.clearConversation(aliceLogin.token, bob, forBoth = false)
        assertTrue(aliceAgain.history(aliceLogin.token, bob).isEmpty(), "清除后本地不应再有该会话")
        assertTrue(
            bobBackend.history(bobLogin.token, alice).isNotEmpty(),
            "单方清除不应影响对方的记录",
        )

        // 清除后仍能继续收发：会话标签与棘轮状态没有被清除动作破坏。
        aliceAgain.send(aliceLogin.token, bob, "after clear")
        val afterClear = bobBackend.poll(bobLogin.token).firstOrNull { it.contactId == alice }
        assertTrue(afterClear != null, "清除后应还能收到新消息")
        assertEquals("after clear", afterClear.message.text)

        // 双方清除：B 执行后，B 本地与 A 的历史都应被抹掉。
        bobBackend.clearConversation(bobLogin.token, alice, forBoth = true)
        assertTrue(bobBackend.history(bobLogin.token, alice).isEmpty(), "双方清除后 B 不应有记录")
    }

    private suspend fun safetyCode(
        backend: SecureBackend,
        token: String,
        peer: String,
        peerDeviceId: String,
    ): String? {
        val own = backend.ownIdentityKeyBase64() ?: return null
        val key = backend.peerIdentityKeys(token, peer).firstOrNull { it.deviceId == peerDeviceId }
            ?: return null
        return com.junesoon.mim.app.domain.SafetyNumber.of(own, key.publicKeyBase64)
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

    private fun withEnv(block: suspend CoroutineScope.(String) -> Unit) {
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
