package com.junesoon.mim.app.data

import com.junesoon.mim.app.domain.ChatMessage
import com.junesoon.mim.app.domain.Contact

/**
 * 传输层契约。
 *
 * 这里刻意只放「业务语义」，不放 HTTP/WebSocket 细节：
 * Android/iOS 的真实实现接服务端 REST 与推送，桌面演示实现走内存。
 * 协议与密码学不在这层，见 mim-core / Rust 核心。
 */
interface MessagingBackend {

    suspend fun login(username: String, password: String, deviceName: String): LoginResult

    suspend fun contacts(token: String): List<Contact>

    suspend fun history(token: String, contactId: String): List<ChatMessage>

    suspend fun send(token: String, contactId: String, text: String): ChatMessage

    /**
     * 拉取尚未消费的消息。
     *
     * 真实实现会解密并把明文放进缓存；演示实现返回空。
     */
    suspend fun poll(token: String): List<IncomingMessage> = emptyList()
}

/** 一条新到达的消息，附带它属于哪个联系人。 */
data class IncomingMessage(val contactId: String, val message: ChatMessage)

data class LoginResult(
    val token: String,
    val displayName: String,
    val contacts: List<Contact>,
)
