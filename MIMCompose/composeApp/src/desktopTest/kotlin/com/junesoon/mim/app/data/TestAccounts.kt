package com.junesoon.mim.app.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 联调测试的共享账号。
 *
 * 服务端注册限流是「每 IP 每分钟 5 次」，反复建新账号会互相挤掉；
 * 因此固定账号只创建一次，后续先查可用性再决定是否注册。
 */
object TestAccounts {

    const val PASSWORD = "ComposePass1234"

    private val client: HttpClient by lazy {
        HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    private val ensured = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun ensure(server: String, username: String) {
        // 同一次测试进程内只查一次，避免把可用性接口打到限流。
        if (!ensured.add(username)) {
            return
        }
        val available: TestEnvelope<Boolean> = client.get(
            "$server/register/available?username=$username",
        ).body()
        // 限流时假定账号已存在：这些固定账号在首次运行时就已创建。
        if (available.status != 200) {
            return
        }
        if (available.data == true) {
            val registered: StatusEnvelope = client.post("$server/register/token") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        username = username,
                        password = PASSWORD,
                        agreedTerms = true,
                        termsVersion = "2026-11",
                    )
                )
            }.body()
            check(registered.status == 200) { registered.message ?: "注册失败" }
        }
    }

    /** 登录拿令牌；登录本身不登记设备，可用于清理。 */
    suspend fun loginToken(server: String, username: String, deviceId: String): String {
        val response: TestEnvelope<String> = client.post("$server/login/token") {
            header("X-Device-Id", deviceId)
            contentType(ContentType.Application.Json)
            setBody(mapOf("username" to username, "password" to PASSWORD))
        }.body()
        check(response.status == 200) { response.message ?: "登录失败" }
        return response.data ?: error("登录未返回令牌")
    }

    /**
     * 清掉账号下的其它设备。
     *
     * 服务端每账号最多 10 台；测试每次都建新设备，不清理很快就会撞上限。
     */
    suspend fun clearDevices(server: String, username: String) {
        val temp = uniqueDevice("clean")
        val token = loginToken(server, username, temp)
        val list: TestEnvelope<AccountDeviceList> = client.get("$server/device") {
            header("Authorization", "Bearer $token")
        }.body()
        if (list.status != 200) return
        for (device in list.data?.devices.orEmpty()) {
            val id = device.deviceId ?: continue
            runCatching {
                client.delete("$server/device/$id") {
                    header("Authorization", "Bearer $token")
                }
            }
        }
    }

    fun uniqueDevice(prefix: String): String =
        "$prefix-" + System.currentTimeMillis().toString().takeLast(9)

    @Serializable
    private data class RegisterRequest(
        val username: String,
        val password: String,
        val agreedTerms: Boolean,
        val termsVersion: String,
    )

}

@Serializable
internal data class StatusEnvelope(
    val status: Int = 0,
    val message: String? = null,
)

@Serializable
internal data class TestEnvelope<T>(
    val status: Int = 0,
    val message: String? = null,
    val data: T? = null,
)

@Serializable
internal data class AccountDeviceList(
    val count: Int = 0,
    val devices: List<AccountDevice> = emptyList(),
)

@Serializable
internal data class AccountDevice(val deviceId: String? = null)
