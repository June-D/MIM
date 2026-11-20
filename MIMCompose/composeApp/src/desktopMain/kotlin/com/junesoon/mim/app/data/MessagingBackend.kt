package com.junesoon.mim.app.data

import com.junesoon.mim.app.domain.ChatMessage
import com.junesoon.mim.app.domain.Contact
import com.junesoon.mim.app.domain.DeviceInfo
import com.junesoon.mim.app.domain.FriendRequest
import com.junesoon.mim.app.domain.PeerIdentityKey
import com.junesoon.mim.app.domain.UserSummary

/**
 * 传输层契约。
 *
 * 这里刻意只放「业务语义」，不放 HTTP/WebSocket 细节：
 * Android/iOS 的真实实现接服务端 REST 与推送，桌面演示实现走内存。
 * 协议与密码学不在这层，见 mim-core / Rust 核心。
 */
interface MessagingBackend {

    suspend fun login(
        username: String,
        password: String,
        deviceName: String,
        code: String? = null,
    ): LoginResult

    suspend fun contacts(token: String): List<Contact>

    suspend fun history(token: String, contactId: String): List<ChatMessage>

    suspend fun send(token: String, contactId: String, text: String): ChatMessage

    /**
     * 拉取尚未消费的消息。
     *
     * 真实实现会解密并把明文放进缓存；演示实现返回空。
     */
    suspend fun poll(token: String): List<IncomingMessage> = emptyList()

    /** 撤回自己发出的一条消息。 */
    suspend fun recall(token: String, contactId: String, messageId: String): ChatMessage? = null

    /** 把与该联系人的消息标记为已读。 */
    suspend fun markRead(token: String, contactId: String) = Unit

    /** 刷新已读 / 撤回状态（已读回执与实时撤回缺少推送时的补偿）。 */
    suspend fun historyStates(token: String, contactId: String): List<MessageState> = emptyList()

    /**
     * 监听下行推送，直到协程被取消。
     *
     * 只把「有新数据」这件事通知出去，具体拉取仍走 HTTP：
     * 落库与鉴权都在 HTTP 上，推送丢失也不会影响一致性。
     */
    suspend fun listenPush(onSignal: (PushEvent) -> Unit) = Unit

    /** 与某人的未读消息数（服务端按逻辑消息计数）。 */
    suspend fun unread(token: String, contactId: String): Int = 0

    /** 清除与某人的会话；forBoth 时对方也看不到这段消息。 */
    suspend fun clearConversation(token: String, contactId: String, forBoth: Boolean) = Unit

    /** 只丢弃本地缓存，不动服务端（对方清除会话时用）。 */
    fun forgetLocalConversation(contactId: String) = Unit

    /** 搜索可以添加的用户（服务端排除自己与已有好友）。 */
    suspend fun searchUsers(token: String, keyword: String): List<UserSummary> = emptyList()

    /** 待处理的好友申请：我收到与我发出的。 */
    suspend fun friendRequests(token: String): List<FriendRequest> = emptyList()

    /** 发起好友申请。 */
    suspend fun requestFriend(token: String, username: String) = Unit

    /** 同意或拒绝一条发给我的申请。 */
    suspend fun respondRequest(token: String, requestId: Long, accept: Boolean) = Unit

    /** 删除好友（双向）。 */
    suspend fun removeContact(token: String, peer: String) = Unit

    /** 修改好友备注名。 */
    suspend fun renameContact(token: String, peer: String, label: String) = Unit

    /** 注册账号；email 可选，用于找回密码。 */
    suspend fun register(username: String, password: String, email: String?): Unit =
        unsupported("注册")

    /** 发送邮箱验证码（忘记密码）。 */
    suspend fun sendRecoveryCode(username: String): Unit = unsupported("邮箱找回")

    /** 用邮箱验证码重置密码。 */
    suspend fun resetPassword(username: String, code: String, newPassword: String): Unit =
        unsupported("密码重置")

    /** 本机屏蔽 / 解除屏蔽某个联系人。 */
    fun setBlocked(contactId: String, blocked: Boolean) = Unit

    /** 本机已屏蔽的联系人。 */
    fun blockedContacts(): Set<String> = emptySet()

    private fun unsupported(action: String): Nothing =
        throw IllegalStateException("当前后端不支持$action")

    /** 本账号的设备列表。 */
    suspend fun devices(token: String): List<DeviceInfo> = emptyList()

    /** 注销（踢掉）指定设备。 */
    suspend fun revokeDevice(token: String, deviceId: String) = Unit

    /** 对端设备的身份公钥，用于计算安全码。 */
    suspend fun peerIdentityKeys(token: String, contactId: String): List<PeerIdentityKey> = emptyList()

    /** 本设备身份公钥（Base64）；演示后端返回 null。 */
    fun ownIdentityKeyBase64(): String? = null
}

/** 下行推送事件。 */
sealed interface PushEvent {

    /** 有新消息、已读回执或撤回，需要拉一次 HTTP。 */
    data object NewData : PushEvent

    /** 好友关系变化：收到申请、被同意、被拒绝。 */
    data object FriendsChanged : PushEvent

    /** 对方清除了与我们的会话。 */
    data class Cleared(val peer: String) : PushEvent
}

/** 一条逻辑消息的外部状态。 */
data class MessageState(
    val id: String,
    val read: Boolean = false,
    val recalled: Boolean = false,
    val pending: Boolean = false,
)

/** 一条新到达的消息，附带它属于哪个联系人。 */
data class IncomingMessage(val contactId: String, val message: ChatMessage)

data class LoginResult(
    val token: String,
    val displayName: String,
    val contacts: List<Contact>,
)
