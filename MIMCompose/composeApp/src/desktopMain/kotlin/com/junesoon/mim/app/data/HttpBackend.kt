package com.junesoon.mim.app.data

import com.junesoon.mim.app.domain.ChatMessage
import com.junesoon.mim.app.domain.Contact
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 真实服务端传输（当前只接登录与联系人）。
 *
 * <p>历史与发送需要端到端加密链路，属于协议层迁移的下一步；
 * 这里刻意让它们显式失败，而不是返回假数据。
 */
class HttpBackend(private val baseUrl: String) : MessagingBackend {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    override suspend fun login(username: String, password: String, deviceName: String): LoginResult {
        val response: LoginResponse = client.post("$baseUrl/login/token") {
            header("X-Device-Id", deviceName.ifBlank { "compose-desktop" })
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }.body()

        val token = response.data
            ?: throw IllegalStateException(response.message ?: "登录失败")
        return LoginResult(token, username, contacts(token))
    }

    override suspend fun contacts(token: String): List<Contact> {
        val groups: List<ContactGroup> = client.get("$baseUrl/chat/contacts") {
            header("Authorization", "Bearer $token")
        }.body()

        return groups.flatMap { group ->
            group.users.map { user ->
                val name = user.username ?: "未命名"
                Contact(
                    id = name,
                    name = user.nickname ?: user.labelName ?: name,
                    subtitle = if (user.online == true) "在线" else group.groupName.orEmpty(),
                )
            }
        }
    }

    override suspend fun history(token: String, contactId: String): List<ChatMessage> = emptyList()

    override suspend fun send(token: String, contactId: String, text: String): ChatMessage {
        throw IllegalStateException("协议层尚未接入：当前仅验证登录与联系人")
    }
}

@Serializable
private data class LoginRequest(val username: String, val password: String)

@Serializable
private data class LoginResponse(
    val data: String? = null,
    val rel: Boolean = false,
    val message: String? = null,
)

@Serializable
private data class ContactGroup(
    val groupId: String? = null,
    val groupName: String? = null,
    val users: List<ChatUserDto> = emptyList(),
)

@Serializable
private data class ChatUserDto(
    val username: String? = null,
    val nickname: String? = null,
    val labelName: String? = null,
    val online: Boolean? = null,
)
