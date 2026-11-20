package com.junesoon.mim.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        // 固定 test 前缀的设备标识：清理时只删这些，不碰用户的 desktop- 设备。
        writeDeviceId(root, alice, "testalice-" + System.currentTimeMillis().toString().takeLast(6))
        writeDeviceId(root, bob, "testbob-" + System.currentTimeMillis().toString().takeLast(6))

        val aliceBackend = SecureBackend(server, root)
        val aliceLogin = TestAccounts.withLoginBudget {
            aliceBackend.login(alice, TestAccounts.PASSWORD, "Alice Desktop")
        }
        pruneOtherDevices(server, aliceLogin.token, aliceBackend.currentDeviceId)

        val bobBackend = SecureBackend(server, root)
        val bobLogin = TestAccounts.withLoginBudget {
            bobBackend.login(bob, TestAccounts.PASSWORD, "Bob Phone")
        }
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
        TestAccounts.withLoginBudget {
            aliceAgain.login(alice, TestAccounts.PASSWORD, "Alice Desktop")
        }
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

        // 设备被注销：同一设备标识不能再登录，必须给出可执行的提示。
        aliceAgain.revokeDevice(aliceLogin.token, aliceAgain.currentDeviceId)
        val revokedError = runCatching {
            TestAccounts.withLoginBudget {
                SecureBackend(server, root).login(alice, TestAccounts.PASSWORD, "Alice Desktop")
            }
        }.exceptionOrNull()
        assertTrue(
            revokedError?.message?.contains("已被注销") == true,
            "已注销设备应给出明确提示：$revokedError",
        )

        // 换新设备标识即可继续使用：旧设备目录保留，离线历史仍能打开。
        val newRoot = Files.createTempDirectory("mim-vault-new")
        writeDeviceId(newRoot, alice, "testalice-new-" + System.currentTimeMillis().toString().takeLast(6))
        val aliceNew = SecureBackend(server, newRoot)
        val newLogin = TestAccounts.withLoginBudget {
            aliceNew.login(alice, TestAccounts.PASSWORD, "Alice Desktop 2")
        }
        bobBackend.send(bobLogin.token, alice, "after new device")
        val afterNewDevice = aliceNew.poll(newLogin.token).firstOrNull { it.contactId == bob }
        assertTrue(afterNewDevice != null, "新设备应能收到消息")
        assertEquals("after new device", afterNewDevice.message.text)

        // 屏蔽：仍然解密（棘轮必须前进），但明文不入库、不提示。
        aliceNew.setBlocked(bob, true)
        assertTrue(aliceNew.blockedContacts().contains(bob), "屏蔽状态应保存")
        bobBackend.send(bobLogin.token, alice, "blocked one")
        assertTrue(
            aliceNew.poll(newLogin.token).none { it.contactId == bob },
            "屏蔽后不应出现新消息",
        )
        bobBackend.send(bobLogin.token, alice, "while blocked")
        assertTrue(
            aliceNew.poll(newLogin.token).none { it.contactId == bob },
            "屏蔽期间的消息也不应出现",
        )

        // 解除屏蔽后继续收发：证明屏蔽期间棘轮没有断。
        aliceNew.setBlocked(bob, false)
        assertTrue(aliceNew.blockedContacts().isEmpty(), "解除后不应再有屏蔽记录")
        bobBackend.send(bobLogin.token, alice, "after unblock")
        val resumed = aliceNew.poll(newLogin.token).firstOrNull { it.contactId == bob }
        assertTrue(resumed != null, "解除屏蔽后应能收到消息")
        assertEquals("after unblock", resumed.message.text)

        // 图片：文件先加密上传，接收端下载后按消息里的密钥解密。
        val imageBytes = ByteArray(2048) { (it * 7 % 251).toByte() }
        bobBackend.sendImage(
            bobLogin.token, alice, imageBytes, "image/png", "tiny.png", "看图",
        )
        val image = aliceNew.poll(newLogin.token).firstOrNull { it.contactId == bob }
        assertTrue(image != null, "应收到图片消息")
        assertEquals("image", image.message.kind, "消息类型应为图片")
        assertEquals("看图", image.message.text, "图片说明应保留")
        val imagePlain = aliceNew.mediaBytes(image.message)
        assertTrue(
            imagePlain != null && imagePlain.contentEquals(imageBytes),
            "图片解密后应与原文件完全一致",
        )

        // 语音：同一条媒体链路，只是多带一个时长。
        val voiceBytes = ByteArray(1600) { (it * 3 % 251).toByte() }
        bobBackend.sendVoice(bobLogin.token, alice, voiceBytes, 1_500L, "语音说明")
        val voice = aliceNew.poll(newLogin.token).firstOrNull { it.contactId == bob }
        assertTrue(voice != null, "应收到语音消息")
        assertEquals("voice", voice.message.kind)
        assertEquals(1_500L, voice.message.media?.durationMs, "时长应随消息保存")
        val voicePlain = aliceNew.mediaBytes(voice.message)
        assertTrue(
            voicePlain != null && voicePlain.contentEquals(voiceBytes),
            "语音解密后应与原文件完全一致",
        )
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

    /** 清掉历史遗留的测试设备，避免多设备分发把测试放大。 */
    private suspend fun pruneOtherDevices(server: String, token: String, keep: String) {
        KeyRegistry(server).pruneOtherDevices(token, keep, listOf("test"))
    }

    private fun writeDeviceId(root: java.nio.file.Path, username: String, deviceId: String) {
        val dir = root.resolve(username)
        Files.createDirectories(dir)
        Files.write(dir.resolve("device.txt"), deviceId.toByteArray(Charsets.UTF_8))
    }

    private fun withEnv(block: suspend CoroutineScope.(String) -> Unit) {
        val server = System.getenv("MIM_SERVER_URL") ?: return
        if (System.getenv("MIM_NATIVE_LIB").isNullOrBlank()) return
        runBlocking { block(server) }
    }

}
