package com.junesoon.mim.app.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/**
 * 本机登录保护：连续输错密码达到阈值后，销毁本机该账号的全部加密数据。
 *
 * <p>为什么只在设备侧做：清除一旦由「服务端收到的失败次数」触发，任何知道用户名的人
 * 连发几次错误密码就能删掉你的历史（甚至对方的记录），等于给攻击者一个免费的
 * 远程销毁按钮。设备侧计数只影响这台机器，服务端与对方都不受影响。
 *
 * <p>计数文件放在账号目录下、保险库之外，且不随「删除保险库」消失——
 * 否则只要重启或手动删一次消息缓存就能把计数清零。
 *
 * <p>已知边界：能直接读写你的用户目录的恶意程序可以改这个计数。
 * 这个功能防的是「别人拿到这台设备在界面上试密码」，不是本地取证。
 */
class DeviceGuard(
    private val root: Path = Paths.get(LocalVault.defaultRoot().toString()),
    private val threshold: Int = THRESHOLD,
) {

    fun failures(username: String): Int = readFailure(username)

    /** 记一次失败并返回累计次数；达到阈值后由调用方执行 [wipe]。 */
    fun recordFailure(username: String): Int {
        val next = readFailure(username) + 1
        writeGuard(username, next)
        return next
    }

    /** 登录成功后清零，避免零散的历史失败把下次误输推过阈值。 */
    fun reset(username: String) {
        writeGuard(username, 0)
    }

    /**
     * 删除本机该账号目录（密钥包、会话、消息缓存、离线队列、设备标识），
     * 并落一条自毁标记，返回时间戳。
     */
    fun wipe(username: String): Long {
        runCatching { root.resolve(username).toFile().deleteRecursively() }
        val now = System.currentTimeMillis()
        runCatching {
            val marker = markerOf(username)
            Files.createDirectories(marker.parent)
            writePrivate(marker, now.toString())
        }
        return now
    }

    /** 读取并清除自毁标记：只提示一次，读完即删。 */
    fun consumeWipedAt(username: String): Long? {
        val marker = markerOf(username)
        if (!Files.exists(marker)) {
            return null
        }
        val value = runCatching {
            String(Files.readAllBytes(marker), Charsets.UTF_8).trim().toLong()
        }.getOrNull()
        runCatching { Files.deleteIfExists(marker) }
        return value
    }

    private fun markerOf(username: String): Path =
        root.resolve(WIPE_DIR).resolve("$username.txt")

    private fun guardFile(username: String): Path =
        root.resolve(username).resolve(GUARD_FILE)

    private fun readFailure(username: String): Int {
        val file = guardFile(username)
        if (!Files.exists(file)) {
            return 0
        }
        return runCatching {
            String(Files.readAllBytes(file), Charsets.UTF_8).trim().toInt()
        }.getOrDefault(0)
    }

    private fun writeGuard(username: String, count: Int) {
        runCatching {
            val file = guardFile(username)
            Files.createDirectories(file.parent)
            writePrivate(file, count.toString())
        }
    }

    private fun writePrivate(file: Path, content: String) {
        Files.write(file, content.toByteArray(Charsets.UTF_8))
        runCatching {
            Files.setPosixFilePermissions(
                file,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    /** 阈值：写进协议文本，改这里必须同步改用户协议与注册弹窗。 */
    val limit: Int get() = threshold

    companion object {
        const val THRESHOLD = 3
        private const val GUARD_FILE = "guard.txt"
        private const val WIPE_DIR = ".wiped"
    }
}
