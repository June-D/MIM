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
    /** 对端已读（仅自己发的消息有意义）。 */
    val read: Boolean = false,
    /** 已撤回，正文固定显示为「已撤回」。 */
    val recalled: Boolean = false,
    /** 本地持有撤回凭证，可以发起撤回。 */
    val canRecall: Boolean = false,
)
