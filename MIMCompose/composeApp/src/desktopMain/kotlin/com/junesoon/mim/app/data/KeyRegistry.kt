package com.junesoon.mim.app.data

import com.junesoon.mim.core.MimCoreNative
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
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
        postVoid("$baseUrl/device", token, DeviceRegisterRequest(deviceId, deviceName))

        val bundle = NativeKeyProvider.newBundle(version = 1, oneTimeCount = oneTimeCount)
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
        return bundle
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
