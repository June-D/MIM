package com.junesoon.mim.app.data

import com.junesoon.mim.app.domain.ChatMessage
import com.junesoon.mim.app.domain.Contact
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内存演示实现：用来跑通 UI 与状态流，不代表真实协议。
 *
 * 换成真实实现时只需替换 AppStore 的构造参数；
 * UI 与状态层不需要改一行。
 */
class DemoBackend : MessagingBackend {

    private val conversations = mutableMapOf<String, MutableList<ChatMessage>>()

    init {
        conversations["alice"] = mutableListOf(
            ChatMessage("a1", false, "密钥指纹核对过了，可以开始。", now()),
            ChatMessage("a2", true, "收到，先发一条测试消息。", now()),
        )
        conversations["bob"] = mutableListOf(
            ChatMessage("b1", false, "晚点把配置文件发你。", now()),
        )
    }

    override suspend fun login(username: String, password: String, deviceName: String): LoginResult {
        // 演示模式不校验密码强度；真实实现服务端会拒绝弱口令。
        val name = username.ifBlank { "未命名用户" }
        return LoginResult(
            token = "demo-token",
            displayName = name,
            contacts = listOf(
                Contact("alice", "alice", "密钥指纹核对过了，可以开始。", 0),
                Contact("bob", "bob", "晚点把配置文件发你。", 1),
            ),
        )
    }

    override suspend fun contacts(token: String): List<Contact> = listOf(
        Contact("alice", "alice", conversations["alice"]?.lastOrNull()?.text ?: "", 0),
        Contact("bob", "bob", conversations["bob"]?.lastOrNull()?.text ?: "", 1),
    )

    override suspend fun history(token: String, contactId: String): List<ChatMessage> =
        conversations[contactId]?.toList() ?: emptyList()

    override suspend fun send(token: String, contactId: String, text: String): ChatMessage {
        val message = ChatMessage(
            id = "m" + System.nanoTime(),
            fromMe = true,
            text = text,
            time = now(),
        )
        conversations.getOrPut(contactId) { mutableListOf() }.add(message)
        // 演示回声：真实实现由对端设备解密后产生。
        conversations.getOrPut(contactId) { mutableListOf() }.add(
            ChatMessage(
                id = "r" + System.nanoTime(),
                fromMe = false,
                text = "已收到的演示回执",
                time = now(),
            )
        )
        return message
    }

    private fun now(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}
