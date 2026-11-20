package com.junesoon.mim.app.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 本机登录保护：阈值计数与自毁。
 *
 * 计数文件必须放在账号目录下、保险库之外，并且自毁后计数一起消失，
 * 否则「删缓存」「重启」都能把计数清零。
 */
class DeviceGuardTest {

    @Test
    fun counts_failures_and_keeps_them_across_instances() {
        val root = Files.createTempDirectory("mim-guard")
        assertEquals(1, DeviceGuard(root).recordFailure("alice"))
        assertEquals(2, DeviceGuard(root).recordFailure("alice"))
        // 新实例（等于重启应用）能读到同一个计数
        assertEquals(2, DeviceGuard(root).failures("alice"))
        // 不同账号互不影响
        assertEquals(0, DeviceGuard(root).failures("bob"))
    }

    @Test
    fun reset_clears_counter() {
        val root = Files.createTempDirectory("mim-guard")
        val guard = DeviceGuard(root)
        guard.recordFailure("alice")
        guard.recordFailure("alice")
        guard.reset("alice")
        assertEquals(0, guard.failures("alice"))
    }

    @Test
    fun wipe_removes_account_directory_and_leaves_a_one_shot_marker() {
        val root = Files.createTempDirectory("mim-guard")
        val guard = DeviceGuard(root)
        val vaultFile = root.resolve("alice").resolve("desktop-1").resolve("messages.bin")
        Files.createDirectories(vaultFile.parent)
        Files.write(vaultFile, byteArrayOf(1, 2, 3))
        Files.write(root.resolve("alice").resolve("device.txt"), "desktop-1".toByteArray())
        guard.recordFailure("alice")

        val wipedAt = guard.wipe("alice")

        assertTrue(wipedAt > 0)
        assertFalse(Files.exists(root.resolve("alice")), "账号目录（含设备标识与保险库）应被整目录删除")
        // 标记只读一次：登录提示过就该消失，避免每次登录都提示
        assertNotNull(guard.consumeWipedAt("alice"))
        assertNull(guard.consumeWipedAt("alice"))
        // 自毁后计数归零，不会立刻再次触发
        assertEquals(0, guard.failures("alice"))
    }
}
