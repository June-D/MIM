package com.junesoon.mim.app.data

import com.junesoon.mim.app.domain.ChatMessage
import com.junesoon.mim.app.domain.ClearPoint
import com.junesoon.mim.app.domain.Contact
import com.junesoon.mim.app.domain.DeviceInfo
import com.junesoon.mim.app.domain.FriendRequest
import com.junesoon.mim.app.domain.PeerIdentityKey
import com.junesoon.mim.app.domain.LocalMessageHit
import com.junesoon.mim.app.domain.TwoFactorSetup
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

    /** 发送图片：文件先加密再上传，消息里只带媒体引用与密钥。 */
    suspend fun sendImage(
        token: String,
        contactId: String,
        bytes: ByteArray,
        mime: String,
        name: String?,
        caption: String,
    ): ChatMessage = unsupported("图片")

    /** 发送语音：bytes 是 WAV，时长只用于展示。 */
    suspend fun sendVoice(
        token: String,
        contactId: String,
        bytes: ByteArray,
        durationMs: Long,
        caption: String,
    ): ChatMessage = unsupported("语音")

    /**
     * 发送任意文件。
     *
     * 与图片/语音同一条链路：一次性密钥 AES-GCM 加密后上传密文，
     * 消息里只带媒体引用、密钥、文件名与大小；服务端拿不到文件名和类型。
     */
    suspend fun sendFile(
        token: String,
        contactId: String,
        bytes: ByteArray,
        mime: String,
        name: String,
    ): ChatMessage = unsupported("文件")

    /** 读取并解密本地缓存的媒体明文；拿不到时返回 null。 */
    fun mediaBytes(message: ChatMessage): ByteArray? = null

    /**
     * 请本账号的其他设备把历史转发过来。
     *
     * 服务端只是转发这条请求（WS 100022），能不能拿到取决于对方设备上的
     * 用户是否同意——这是这个功能的安全边界。
     */
    suspend fun requestHistorySync(token: String) = Unit

    /** 本机已批准「历史同步」的本账号其它设备。只记设备 id，不上报服务端。 */
    fun trustedDevices(): Set<String> = emptySet()

    fun setTrustedDevice(deviceId: String, trusted: Boolean) = Unit

    /** 本机是否已有历史缓存（决定新设备登录时要不要请求另一台设备同步）。 */
    fun hasLocalHistory(): Boolean = false

    /** 某台本账号设备的身份公钥短指纹；取不到时返回 null（不猜）。 */
    suspend fun deviceFingerprint(token: String, deviceId: String): String? = null

    /**
     * 把本机留存的最近历史重新加密给本账号的另一台设备，返回实际提交条数。
     *
     * 只能转发本机有明文的消息（棘轮解不开自己发出的密文），且只发最近
     * [limit] 条 / 30 天内的文本消息；调用前必须已获得用户对该设备的批准。
     */
    suspend fun syncToDevice(token: String, deviceId: String, limit: Int = 50): Int = 0

    /**
     * 释放本机上传过、已经不再引用的媒体密文。
     *
     * 服务端只看得到密文、看不到引用关系，所以由发送方主动告知；
     * 服务端仍然保留期兜底。失败不影响本地流程。
     */
    suspend fun releaseUploadedMedia(token: String, mediaIds: List<String>) = Unit

    /**
     * 在本地加密缓存里搜索消息。
     *
     * 服务端不知道明文，搜索只能在已缓存的消息里做；换设备后没缓存就搜不到。
     */
    fun searchLocal(query: String): List<LocalMessageHit> = emptyList()

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

    /**
     * 服务端记录的清除点（批量）。
     *
     * 对方执行「双方清除」时，本机如果不在线就收不到推送；登录、刷新列表、
     * 打开会话时用它对账，避免本地缓存继续显示已被抹掉的消息。
     */
    suspend fun clearPoints(token: String, peers: List<String>): Map<String, ClearPoint> = emptyMap()

    /**
     * 丢弃本机在该清除点之前的消息缓存，返回是否真的丢掉了内容。
     *
     * 有服务端 id 的消息按 id 判断；旧版本缓存没有 id，按清除时间兜底。
     */
    fun dropClearedMessages(contactId: String, cursor: Long, clearedAt: Long): Boolean = false

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

    /** 本机置顶的会话；纯本地偏好，不上报服务端。 */
    fun pinnedContacts(): Set<String> = emptySet()

    fun setPinned(contactId: String, pinned: Boolean) = Unit

    /** 该会话「已读到哪里」的时间水位；0 表示还没记录过。 */
    fun readMark(contactId: String): Long = 0L

    fun setReadMark(contactId: String, epochMs: Long) = Unit

    /** 上次见到的对端设备安全码：设备 id → 安全码（仅当前联系人）。 */
    fun safetyCodes(contactId: String): Map<String, String> = emptyMap()

    /**
     * 记住对端设备的安全码。
     *
     * 只记录公钥派生的安全码，不保存公钥本身；用于下一次比对是否变更。
     */
    fun rememberSafetyCodes(contactId: String, codes: Map<String, String>) = Unit

    /** 当前账号绑定的找回邮箱；未绑定或后端不支持时返回空串。 */
    suspend fun userEmail(token: String): String = ""

    /** 绑定 / 更换找回邮箱；email 传空串表示解绑。需要当前密码。 */
    suspend fun bindEmail(token: String, password: String, email: String) = Unit

    /** 账号是否开启了二次验证。 */
    suspend fun twoFactorEnabled(token: String): Boolean = false

    /** 生成二次验证共享密钥（尚未启用）。 */
    suspend fun setupTwoFactor(token: String): TwoFactorSetup = unsupported("二次验证")

    /**
     * 启用二次验证；返回新令牌与一次性恢复码。
     *
     * 服务端会强制其它设备重新登录，当前设备的旧令牌同时失效。
     */
    suspend fun enableTwoFactor(token: String, code: String): TwoFactorResult =
        unsupported("二次验证")

    /** 关闭二次验证；返回当前设备的新令牌。 */
    suspend fun disableTwoFactor(token: String, password: String, code: String): String =
        unsupported("二次验证")

    /** 重新生成一次性恢复码。 */
    suspend fun regenerateRecoveryCodes(
        token: String,
        password: String,
        code: String,
    ): List<String> = unsupported("二次验证")

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

    /**
     * 同账号的另一台设备请求历史同步（WS 100022）。
     *
     * 只带请求方的设备 id：要不要转发、转多少，由本机用户决定，
     * 服务端无权替用户把历史交出去。
     */
    data class SyncRequested(val deviceId: String) : PushEvent
}

/** 启用二次验证的结果。 */
data class TwoFactorResult(
    val token: String,
    val recoveryCodes: List<String>,
)

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
