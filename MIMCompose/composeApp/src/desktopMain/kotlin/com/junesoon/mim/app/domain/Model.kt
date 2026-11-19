package com.junesoon.mim.app.domain

/** 会话列表里的一行。 */
data class Contact(
    val id: String,
    val name: String,
    val subtitle: String,
    val unread: Int = 0,
)

/** 聊天里的一条消息。`fromMe` 决定气泡方向，`pending` 表示尚未送达。 */
data class ChatMessage(
    val id: String,
    val fromMe: Boolean,
    val text: String,
    val time: String,
    val pending: Boolean = false,
)
