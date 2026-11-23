package com.junesoon.mim.app.data

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/**
 * 多设备历史同步的真机回归：同账号两台设备，A 把最近历史重新加密给 B。
 *
 * <p>只验证「转发 → 服务端接受 → B 拉取 → 验签 → 归属还原」这条链路；
 * 确认弹窗与信任列表在界面层，不属于这条测试。
 */
class SyncE2eTest {

    @Test
    fun history_is_reencrypted_to_my_other_device() = withEnv { server ->
        val alice = "composealice"
        val bob = "composebob"
        TestAccounts.ensure(server, alice)
        TestAccounts.ensure(server, bob)

        val stamp = System.currentTimeMillis().toString().takeLast(6)
        val rootA = Files.createTempDirectory("mim-sync-a")
        val rootB = Files.createTempDirectory("mim-sync-b")
        val rootBob = Files.createTempDirectory("mim-sync-bob")
        writeDeviceId(rootA, alice, "testalice-a-$stamp")
        writeDeviceId(rootB, alice, "testalice-b-$stamp")
        writeDeviceId(rootBob, bob, "testbob-$stamp")

        val a = SecureBackend(server, rootA)
        val tokenA = TestAccounts.withLoginBudget {
            a.login(alice, TestAccounts.PASSWORD, "Alice Desktop")
        }.token
        pruneOtherDevices(server, tokenA, a.currentDeviceId)

        val bobBackend = SecureBackend(server, rootBob)
        val tokenBob = TestAccounts.withLoginBudget {
            bobBackend.login(bob, TestAccounts.PASSWORD, "Bob Phone")
        }.token
        pruneOtherDevices(server, tokenBob, bobBackend.currentDeviceId)

        // A 与 bob 互发一条：一条 fromMe、一条 fromMe=false，两条都要能被同步。
        val outgoing = "我这边的历史 $stamp"
        val incoming = "bob 的回复 $stamp"
        a.send(tokenA, bob, outgoing)
        val atBob = bobBackend.poll(tokenBob).firstOrNull { it.contactId == alice }
        assertTrue(atBob != null, "bob 应收到 A 的消息")
        bobBackend.send(tokenBob, alice, incoming)
        val atA = a.poll(tokenA).firstOrNull { it.contactId == bob }
        assertTrue(atA != null, "A 应收到 bob 的回复")

        // 附件也要能同步：只搬引用与密钥，B 端按 id 去服务端取密文再解密。
        val fileBytes = ByteArray(3072) { index -> (index % 251).toByte() }
        a.sendFile(tokenA, bob, fileBytes, "application/pdf", "季度预算.pdf")
        val atBobFile = bobBackend.poll(tokenBob).firstOrNull { it.message.kind == "file" }
        assertTrue(atBobFile != null, "bob 应收到文件消息")

        // 新设备必须在这些消息之后才登录：否则它会作为普通设备直接收到一份，
        // 服务端按 clientMsgId 判定「副本已存在」，同步就没什么可搬的了。
        val b = SecureBackend(server, rootB)
        val tokenB = TestAccounts.withLoginBudget {
            b.login(alice, TestAccounts.PASSWORD, "Alice Laptop")
        }.token

        val submitted = try {
            a.syncToDevice(tokenA, b.currentDeviceId)
        } catch (error: IllegalArgumentException) {
            // 预密钥接口有 10 次 / 10 分钟的限流：和别的真机测试一起跑很容易撞上。
            // 这不是功能问题，但也不能假装通过——给出可执行的指引再失败。
            throw AssertionError(
                "同步失败：${error.message}。这条 e2e 请单独运行："
                    + "./gradlew :composeApp:desktopTest --tests '*SyncE2eTest'",
                error,
            )
        }
        assertEquals(3, submitted, "两条文本 + 一个附件都应提交给服务端")

        val synced = b.poll(tokenB).filter { it.contactId == bob }
        assertEquals(3, synced.size, "B 应收到三条同步副本：${synced.map { it.message.text }}")

        val mine = synced.first { it.message.text == outgoing }.message
        assertTrue(mine.fromMe, "A 发出去的那条在 B 上仍标记为自己发送")
        assertEquals(a.currentDeviceId, mine.syncedFrom, "来源设备应被记录")

        val theirs = synced.first { it.message.text == incoming }.message
        assertTrue(!theirs.fromMe, "bob 发来的那条在 B 上仍是对方发送")

        val file = synced.first { it.message.kind == "file" }.message
        assertTrue(file.fromMe, "A 发出的附件在 B 上仍标记为自己发送")
        assertEquals("季度预算.pdf", file.media?.name, "附件名来自端到端密文，同步后应完整还原")
        assertEquals("application/pdf", file.media?.mime)
        val fileContent = b.mediaBytes(file)
        assertTrue(fileContent != null, "B 应能取到密文并解密")
        assertTrue(fileContent!!.contentEquals(fileBytes), "同步过来的附件内容必须与原件一致")
    }

    private fun withEnv(block: suspend CoroutineScope.(String) -> Unit) {
        val server = System.getenv("MIM_SERVER_URL") ?: return
        if (System.getenv("MIM_NATIVE_LIB").isNullOrBlank()) return
        runBlocking { block(server) }
    }

    private fun pruneOtherDevices(server: String, token: String, keep: String) {
        runBlocking { KeyRegistry(server).pruneOtherDevices(token, keep, listOf("test")) }
    }

    private fun writeDeviceId(root: Path, username: String, deviceId: String) {
        val dir = root.resolve(username)
        Files.createDirectories(dir)
        Files.write(dir.resolve("device.txt"), deviceId.toByteArray(Charsets.UTF_8))
    }
}
