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
