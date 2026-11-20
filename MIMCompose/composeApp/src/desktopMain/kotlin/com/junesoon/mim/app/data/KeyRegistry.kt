package com.junesoon.mim.app.data

import com.junesoon.mim.core.MimCoreNative
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import com.junesoon.mim.app.data.messaging.DeviceListDto
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * 设备登记与密钥上传。
 *
 * <p>密钥材料由 Rust 核心生成（JNI），这里只负责按服务端契约登记设备、
 * 上传身份公钥、签名预密钥与一次性预密钥。私钥不离开本进程。
 *
 * <p>注意：服务端校验的是**明文口令**（BCrypt），不是 Base64。
 */
class KeyRegistry(
    private val baseUrl: String,
    private val client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    },
) {

    suspend fun provision(
        token: String,
        password: String,
        deviceId: String,
        deviceName: String,
        oneTimeCount: Int = 20,
    ): RustKeyBundle {
        val bundle = NativeKeyProvider.newBundle(version = 1, oneTimeCount = oneTimeCount)
        upload(bundle, token, password, deviceId, deviceName)
        return bundle
    }

    /**
     * 把已有的密钥包重新登记到服务端。
     *
     * 设备被注销（或测试清理）后，本地私钥还在但服务端看不到这台设备，
     * 于是别人发不过来。这里复用同一份密钥包重新登记，不生成新密钥，
     * 已有会话与棘轮状态因此不受影响。
     */
    suspend fun republish(
        token: String,
        password: String,
        deviceId: String,
        deviceName: String,
        bundle: RustKeyBundle,
    ) {
        upload(bundle, token, password, deviceId, deviceName)
    }

    /**
     * 本设备相对服务端的状态。
     *
     * 注销是软删除：已注销设备的令牌会被服务端直接拒绝，
     * 所以必须与「设备行不存在」区分开，前者只能换设备重新登录。
     */
    suspend fun deviceState(token: String, deviceId: String): DeviceState {
        val response: ApiEnvelope<DeviceListDto> = client.get("$baseUrl/device") {
            header("Authorization", "Bearer $token")
        }.body()
        if (response.status != 200) {
            return if (response.message?.contains("已注销") == true) {
                DeviceState.REVOKED
            } else {
                DeviceState.UNKNOWN
            }
        }
        val active = response.data?.devices.orEmpty().any {
            it.deviceId == deviceId && it.revoked != true
        }
        return if (active) DeviceState.ACTIVE else DeviceState.UNREGISTERED
    }

    private suspend fun upload(
        bundle: RustKeyBundle,
        token: String,
        password: String,
        deviceId: String,
        deviceName: String,
    ) {
        postVoid("$baseUrl/device", token, DeviceRegisterRequest(deviceId, deviceName))

        postVoid(
            "$baseUrl/key", token,
            KeyUploadRequest(
                publicKey = hexToBase64(bundle.identity.publicKey),
                algorithm = "EC-P256",
                password = password,
                supportsPadding = true,
                supportsRecallToken = true,
                supportsSenderCert = true,
            ),
        )

        val signed = bundle.signedPreKeys.maxByOrNull { it.id }
            ?: throw IllegalStateException("密钥包缺少签名预密钥")
        postVoid(
            "$baseUrl/prekey/signed", token,
            SignedPreKeyUpload(
                preKeyId = signed.id,
                publicKey = hexToBase64(signed.publicKey),
                signature = signed.signature,
            ),
        )

        if (bundle.oneTimePreKeys.isNotEmpty()) {
            postVoid(
                "$baseUrl/prekey/one-time", token,
                bundle.oneTimePreKeys.map {
                    OneTimePreKeyUpload(it.id, hexToBase64(it.publicKey))
                },
            )
        }
    }

    /**
     * 注销本账号下指定前缀的其它设备（只保留 keep）。
     *
     * 服务端每账号最多 10 台。测试每次新建设备，必须清理自己造的那些；
     * 前缀是安全边界：绝不碰用户真实设备的 `desktop-` 标识。
     */
    suspend fun pruneOtherDevices(token: String, keep: String, prefixes: List<String>) {
        val response: ApiEnvelope<DeviceListDto> = client.get("$baseUrl/device") {
            header("Authorization", "Bearer $token")
        }.body()
        if (response.status != 200) {
            return
        }
        for (device in response.data?.devices.orEmpty()) {
            val id = device.deviceId ?: continue
            if (id == keep || prefixes.none { id.startsWith(it) }) continue
            runCatching {
                client.delete("$baseUrl/device/$id") {
                    header("Authorization", "Bearer $token")
                }
            }
        }
    }

    /** 查询本设备剩余的一次性预密钥数量。 */
    suspend fun oneTimePreKeyCount(token: String, deviceId: String): Int {
        val response: ApiEnvelope<PreKeyStatus> = client.get("$baseUrl/prekey/status") {
            header("Authorization", "Bearer $token")
        }.body()
        require(response.status == 200) { response.message ?: "查询失败" }
        return response.data?.oneTimePreKeys ?: 0
    }

    private suspend fun postVoid(url: String, token: String, body: Any) {
        val response: HttpResponse = client.post(url) {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val envelope: StatusEnvelope = response.body()
        require(envelope.status == 200) { envelope.message ?: "请求失败：$url" }
    }
}

/** 本设备在服务端的登记状态。 */
enum class DeviceState {
    /** 已登记且未注销。 */
    ACTIVE,

    /** 设备行不存在，可用同一份密钥重新登记。 */
    UNREGISTERED,

    /** 已注销，令牌被拒；只能换设备或用新设备标识重新登录。 */
    REVOKED,

    /** 查询失败（网络或其它原因），本次不做处理。 */
    UNKNOWN,
}

/** 由 Rust 生成的密钥包（字段名与 `keys.rs` 的序列化一致）。 */
@Serializable
data class RustKeyBundle(
    val version: Int,
    val identity: RustIdentityKey,
    @SerialName("signed_pre_keys") val signedPreKeys: List<RustSignedPreKey>,
    @SerialName("one_time_pre_keys") val oneTimePreKeys: List<RustOneTimePreKey>,
)

@Serializable
data class RustIdentityKey(
    val version: Int,
    @SerialName("private_key") val privateKey: String,
    @SerialName("public_key") val publicKey: String,
)

@Serializable
data class RustSignedPreKey(
    val id: Int,
    @SerialName("private_key") val privateKey: String,
    @SerialName("public_key") val publicKey: String,
    val signature: String,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class RustOneTimePreKey(
    val id: Int,
    @SerialName("private_key") val privateKey: String,
    @SerialName("public_key") val publicKey: String,
)

/** 调用 Rust 核心生成密钥包；需要 `MIM_NATIVE_LIB` 指向动态库。 */
object NativeKeyProvider {
    private val json = Json { ignoreUnknownKeys = true }

    fun newBundle(version: Int, oneTimeCount: Int): RustKeyBundle {
        val bytes = MimCoreNative.generateKeyBundle(version, oneTimeCount)
        return json.decodeFromString(bytes.toString(Charsets.UTF_8))
    }
}

private fun hexToBase64(hex: String): String {
    val bytes = ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    return Base64.getEncoder().encodeToString(bytes)
}

@Serializable
private data class DeviceRegisterRequest(val deviceId: String, val deviceName: String)

@Serializable
private data class KeyUploadRequest(
    val publicKey: String,
    val algorithm: String,
    val password: String,
    val supportsPadding: Boolean,
    val supportsRecallToken: Boolean,
    val supportsSenderCert: Boolean,
)

@Serializable
private data class SignedPreKeyUpload(
    val preKeyId: Int,
    val publicKey: String,
    val signature: String,
)

@Serializable
private data class OneTimePreKeyUpload(val preKeyId: Int, val publicKey: String)

@Serializable
private data class ApiEnvelope<T>(
    val status: Int = 0,
    val message: String? = null,
    val data: T? = null,
    val rel: Boolean = false,
)

@Serializable
private data class StatusEnvelope(val status: Int = 0, val message: String? = null)

@Serializable
private data class PreKeyStatus(
    val deviceId: String? = null,
    val oneTimePreKeys: Int = 0,
)
