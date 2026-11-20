package com.junesoon.mim.app.data

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 注册与找回密码的契约联调。
 *
 * 注册请求必须序列化成 JSON 对象：早期用 buildMap 组装请求，
 * Ktor 拿到的是 MapBuilder 运行时类型，kotlinx.serialization 会直接报错。
 */
class AccountFlowTest {

    @Test
    fun register_then_login() {
        val server = System.getenv("MIM_SERVER_URL") ?: return
        val username = "uireg" + System.currentTimeMillis().toString().takeLast(8)
        val password = "UiTestPass1234"
        val backend = SecureBackend(server, Files.createTempDirectory("mim-reg"))

        runBlocking {
        val registration = runCatching {
            backend.register(username, password, "ui-test@example.com")
        }
        val failure = registration.exceptionOrNull()
        if (failure != null) {
            // 注册限流是环境状态（5 次/分钟/IP），不把它算作功能失败。
            if (failure.message?.contains("请求过于频繁") == true) {
                return@runBlocking
            }
            throw failure
        }

        val login = TestAccounts.withLoginBudget {
            backend.login(username, password, "UI Test")
        }
        assertEquals(username, login.displayName, "注册后应能用同一口令登录")
        assertTrue(login.token.isNotBlank(), "登录应返回令牌")
        }
    }
}
