package com.junesoon.mim.app.domain

/** 账号下的一台设备。 */
data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val lastSeen: Long?,
    val current: Boolean,
)

/** 对端一台设备的身份公钥（Base64 SPKI）。 */
data class PeerIdentityKey(
    val deviceId: String,
    val publicKeyBase64: String,
)

/** 与某台对端设备的安全码。 */
data class SafetyEntry(
    val deviceId: String,
    val code: String,
)

/** 搜索结果里的一个用户。 */
data class UserSummary(
    val username: String,
    val nickname: String,
    val canReceive: Boolean,
)

/** 一条好友申请。 */
data class FriendRequest(
    val id: Long,
    val username: String,
    val incoming: Boolean,
    val status: String,
) {
    val pending: Boolean get() = status == "pending"
}
