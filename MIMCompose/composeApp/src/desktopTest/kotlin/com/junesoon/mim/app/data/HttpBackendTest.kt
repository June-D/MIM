package com.junesoon.mim.app.data

import io.ktor.client.call.body
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 真实服务端联调，只在设置 `MIM_SERVER_URL` 时执行。
 *
 * 用一个新注册的账号验证「注册 → 登录 → 拉联系人」整条链路；
 * 不依赖任何预置账号，避免污染或依赖 e2e 数据。
 */
class HttpBackendTest {

    @Test
    fun register_login_and_load_contacts() {
        val server = System.getenv("MIM_SERVER_URL") ?: return

        val username = "composelogin"
        val password = TestAccounts.PASSWORD

        runBlocking {
            TestAccounts.ensure(server, username)

            val backend = HttpBackend(server)
            val result = backend.login(username, password, TestAccounts.uniqueDevice("login"))
            assertTrue(result.token.isNotBlank(), "登录未返回令牌")
            assertTrue(result.displayName == username)
            // 新账号还没有联系人；这里只验证接口可用且能解析。
            assertTrue(result.contacts.isEmpty(), "新账号不应有联系人")
        }
    }
}
