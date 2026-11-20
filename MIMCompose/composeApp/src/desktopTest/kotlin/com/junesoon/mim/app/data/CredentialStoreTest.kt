package com.junesoon.mim.app.data

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「记住账号密码」的落盘往返。
 *
 * 加密依赖 Rust 核心的 JNI，因此只在设置了 `MIM_NATIVE_LIB` 时执行；
 * 未设置时跳过，而不是假装通过。
 */
class CredentialStoreTest {

    @Test
    fun save_load_clear_round_trip() {
        if (System.getenv("MIM_NATIVE_LIB").isNullOrBlank()) {
            return
        }
        val root = Files.createTempDirectory("mim-remember")
        val store = CredentialStore(root)
        assertNull(store.load(), "初始状态不应有记住的账号")

        store.save(
            RememberedLogin(
                username = "alice",
                password = "secret-pass",
                deviceName = "Alice PC",
                autoLogin = true,
            ),
        )
        val loaded = store.load()
        assertEquals("alice", loaded?.username)
        assertEquals("secret-pass", loaded?.password)
        assertEquals("Alice PC", loaded?.deviceName)
        assertTrue(loaded?.autoLogin == true)

        store.save(loaded!!.copy(autoLogin = false))
        assertEquals(false, store.load()?.autoLogin, "退出登录后应关闭自动登录")

        val permissions = Files.getPosixFilePermissions(root.resolve("remember.bin"))
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            permissions,
            "凭据文件必须只允许本人读写",
        )

        store.clear()
        assertNull(store.load(), "清除后不应还能读出凭据")
    }
}
