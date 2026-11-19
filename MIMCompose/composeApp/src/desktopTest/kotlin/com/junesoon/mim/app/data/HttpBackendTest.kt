package com.junesoon.mim.app.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 真实服务端联调，只在设置 `MIM_SERVER_URL` 时执行。
 *
 * 用一个新注册的账号验证「注册 → 登录 → 拉联系人」整条链路；
 * 不依赖任何预置账号，避免污染或依赖 e2e 数据。
 */
class HttpBackendTest {

    @Serializable
    private data class Credentials(
        val username: String,
        val password: String,
        val agreedTerms: Boolean,
        val termsVersion: String,
    )

    @Test
    fun register_login_and_load_contacts() {
        val server = System.getenv("MIM_SERVER_URL") ?: return

        val username = "compose" + System.currentTimeMillis().toString().takeLast(9)
        val password = "compose-pass-1234"

        runBlocking {
            val client = HttpClient(CIO) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val registerStatus = client.post("$server/register/token") {
                contentType(ContentType.Application.Json)
                // 注册必须显式同意协议；版本由服务端常量决定。
                setBody(Credentials(username, password, agreedTerms = true, termsVersion = "2026-11"))
            }.status.value
            assertTrue(registerStatus in 200..299, "注册失败：HTTP $registerStatus")

            val backend = HttpBackend(server)
            val result = backend.login(username, password, "compose-test")
            assertTrue(result.token.isNotBlank(), "登录未返回令牌")
            assertTrue(result.displayName == username)
            // 新账号还没有联系人；这里只验证接口可用且能解析。
            assertTrue(result.contacts.isEmpty(), "新账号不应有联系人")
        }
    }
}
