package com.junesoon.mim.app.data.messaging

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 应用侧协议 DTO。
 *
 * 服务端字段是 camelCase，Rust 边界是 snake_case；两套都在这里显式映射，
 * 不在业务代码里手拼字符串。未知字段一律忽略。
 */

// ---------------- 服务端 ----------------

@Serializable
data class ApiEnvelope<T>(
    val status: Int = 0,
    val message: String? = null,
    val data: T? = null,
    val rel: Boolean = false,
)

@Serializable
data class StatusEnvelope(val status: Int = 0, val message: String? = null)

@Serializable
data class DeviceKeyDto(
    val deviceId: String? = null,
    val publicKey: String? = null,
    val algorithm: String? = null,
    val supportsSenderCert: Boolean? = null,
    val supportsPadding: Boolean? = null,
)

@Serializable
data class PreKeyBundleDto(
    val deviceId: String? = null,
    val identityPublicKey: String,
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String,
    val oneTimePreKeyId: Int? = null,
    val oneTimePreKeyPublic: String? = null,
    val supportsSenderCert: Boolean? = null,
)

@Serializable
data class DeviceListDto(
    val count: Int = 0,
    val devices: List<DeviceDto> = emptyList(),
)

@Serializable
data class DeviceDto(
    val deviceId: String? = null,
    val deviceName: String? = null,
    val lastSeen: Long? = null,
    val revoked: Boolean? = null,
)

@Serializable
data class CertDto(
    val username: String,
    val deviceId: String,
    val identityKey: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val keyId: String,
    val signature: String,
)

@Serializable
data class ChatContactGroup(
    val groupId: String? = null,
    val groupName: String? = null,
    val users: List<ChatUserDto> = emptyList(),
)

@Serializable
data class ChatUserDto(
    val username: String? = null,
    val nickname: String? = null,
    val labelName: String? = null,
    val online: Boolean? = null,
)

@Serializable
data class CipherMessageDto(
    val id: Long? = null,
    val clientMsgId: String? = null,
    val toUsername: String? = null,
    val targetDeviceId: String? = null,
    val alg: String? = null,
    val iv: String? = null,
    val ciphertext: String? = null,
    val ratchetPublic: String? = null,
    val previousChainLength: Int? = null,
    val messageNumber: Int? = null,
    val senderIdentityKey: String? = null,
    val senderEphemeralKey: String? = null,
    val senderUsedOneTimePreKey: Boolean? = null,
    val consumedOneTimePreKeyId: Int? = null,
    val recipientSignedPreKeyId: Int? = null,
    val clientTime: Long? = null,
    val sessionId: String? = null,
    val readFlag: Boolean? = null,
    val recalled: Boolean? = null,
    val fromMe: Boolean? = null,
)

@Serializable
data class CipherMessageOut(
    val clientMsgId: String,
    val conversationTag: String,
    val toUsername: String,
    val targetDeviceId: String,
    val alg: String,
    val iv: String,
    val ciphertext: String,
    val ratchetPublic: String,
    val previousChainLength: Int,
    val messageNumber: Int,
    val senderIdentityKey: String? = null,
    val senderEphemeralKey: String? = null,
    val senderUsedOneTimePreKey: Boolean? = null,
    val consumedOneTimePreKeyId: Int? = null,
    val recipientSignedPreKeyId: Int? = null,
    val clientTime: Long,
    val sealed: Boolean = true,
    val sessionId: String,
    /** 一次性撤回凭证；服务端只存哈希。 */
    val recallToken: String? = null,
)

@Serializable
data class ReadRequest(
    val peer: String,
    val beforeId: Long? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class RecallRequest(
    val recallToken: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class UserSummaryDto(
    val username: String? = null,
    val nickname: String? = null,
    val canReceive: Boolean = false,
)

@Serializable
data class FriendRequestDto(
    val id: Long = 0,
    val username: String? = null,
    /** in：待我处理；out：我发出的。 */
    val direction: String? = null,
    val status: String? = null,
)

@Serializable
data class AddContactRequest(val username: String, val labelName: String? = null)

@Serializable
data class TwoFactorStatusDto(val enabled: Boolean = false)

@Serializable
data class TwoFactorSetupDto(
    val secret: String? = null,
    val otpauthUri: String? = null,
)

@Serializable
data class TwoFactorEnableDto(
    val token: String? = null,
    val recoveryCodes: List<String> = emptyList(),
)

@Serializable
data class RecoveryCodesDto(val recoveryCodes: List<String> = emptyList())

@Serializable
data class TokenOnlyDto(val token: String? = null)

@Serializable
data class TwoFactorCodeRequest(
    val code: String,
    val password: String? = null,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    /** 开启二次验证时必填：6 位动态码或一次性恢复码。 */
    val code: String? = null,
)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String? = null,
    val agreedTerms: Boolean = true,
    val termsVersion: String,
)

@Serializable
data class RecoverEmailRequest(val username: String)

@Serializable
data class RecoverResetRequest(
    val username: String,
    val code: String,
    val newPassword: String,
)

/**
 * 媒体消息的明文载荷。
 *
 * 它以 JSON 字符串的形式进入双棘轮，字段顺序固定：
 * `v, kind, text, media`，用前缀识别旧版纯文本消息。
 */
@Serializable
data class MediaPayload(
    val v: Int = 1,
    val kind: String,
    val text: String = "",
    val media: MediaRefJson,
)

@Serializable
data class MediaUploadDto(
    val id: String? = null,
    val size: Long = 0,
)

@Serializable
data class MediaRefJson(
    val id: String,
    val key: String,
    val size: Int,
    val mime: String,
    val name: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
)

@Serializable
data class ClearRequest(
    val peer: String,
    val forBoth: Boolean = false,
    val tags: List<String> = emptyList(),
)

@Serializable
data class ClearResultDto(
    val peer: String? = null,
    val clearedBeforeId: Long = 0,
    val forBoth: Boolean = false,
)

/** 绑定 / 解绑找回邮箱（`POST /user/email`）。 */
@Serializable
data class BindEmailRequest(
    val password: String,
    /** 空串表示解绑：解绑后无法用邮箱找回密码。 */
    val email: String,
)

/** `GET /user/info` 只回给账号本人。 */
@Serializable
data class UserInfoDto(
    val username: String? = null,
    val nickname: String? = null,
    val email: String? = null,
)

/** 本人与某个对端的清除点（`GET /message/clear`）。 */
@Serializable
data class ClearPointDto(
    val clearedBeforeId: Long = 0,
    val updatedAt: Long = 0,
)

// ---------------- Rust 边界 ----------------

@Serializable
data class WireJson(
    val dh: String,
    val pn: Int,
    val n: Int,
    val iv: String,
    val ciphertext: String,
)

@Serializable
data class CertJson(
    val username: String,
    val device_id: String,
    val identity_key: String,
    val issued_at: Long,
    val expires_at: Long,
    val key_id: String,
    val signature: String,
)

@Serializable
data class ComposeRequestJson(
    val identity_private: String,
    val identity_public: String,
    val peer_identity_public: String,
    val peer_signed_pre_key_id: Int,
    val peer_signed_pre_key_public: String,
    val peer_one_time_pre_key_id: Int? = null,
    val peer_one_time_pre_key_public: String? = null,
    val plaintext: String,
    val conversation_tag: String? = null,
    val session_id: String,
    val certificate: CertJson,
    val iv: String? = null,
)

@Serializable
data class ComposeResponseJson(
    val wire: WireJson,
    val state: JsonElement,
    val alg: String,
    val conversation_tag: String,
    val session_id: String,
    val sender_identity_key: String,
    val sender_ephemeral_key: String,
    val used_one_time_pre_key: Boolean,
    val consumed_one_time_pre_key_id: Int? = null,
    val recipient_signed_pre_key_id: Int,
)

@Serializable
data class OpenFirstRequestJson(
    val identity_private: String,
    val identity_public: String,
    val signed_pre_key_private: String,
    val signed_pre_key_public: String,
    val one_time_pre_key_private: String? = null,
    val peer_identity_public: String,
    val peer_ephemeral_public: String,
    val used_one_time_pre_key: Boolean,
    val wire: WireJson,
    val next_dh_private: String? = null,
    val next_dh_public: String? = null,
)

@Serializable
data class OpenFirstResponseJson(
    val plaintext: String,
    val certificate: CertJson,
    val conversation_tag: String? = null,
    val state: JsonElement,
)

@Serializable
data class InsertRequestJson(
    val store: JsonElement,
    val session_id: String,
    val peer: String,
    val device_id: String,
    val state: JsonElement,
    val tags: List<String> = emptyList(),
)

@Serializable
data class StoreResponseJson(val store: JsonElement)

@Serializable
data class ComposeSealedRequestJson(
    val store: JsonElement,
    val session_id: String,
    val plaintext: String,
    val iv: String? = null,
)

@Serializable
data class ComposeSealedResponseJson(val store: JsonElement, val wire: WireJson)

@Serializable
data class OpenSealedRequestJson(
    val store: JsonElement,
    val session_id: String,
    val wire: WireJson,
)

@Serializable
data class OpenSealedResponseJson(val store: JsonElement, val plaintext: String)

// ---------------- 本地会话索引 ----------------

/** 只读索引视图；`state` 保持不透明，交给 Rust 解释。 */
@Serializable
data class StoreJson(val sessions: List<SessionMeta> = emptyList())

@Serializable
data class SessionMeta(
    @SerialName("session_id") val sessionId: String,
    val peer: String,
    @SerialName("device_id") val deviceId: String,
    val state: JsonElement,
    val tags: List<String> = emptyList(),
    val confirmed: Boolean = false,
)
