package com.junesoon.mim.app.data

import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 二次验证登录：删掉 JavaFX 客户端前，确认 Compose 能走通 2FA。
 *
 * 用一次性恢复码登录（启用时返回），避免等 30 秒的 TOTP 时间窗；
 * TOTP 码本身在启用时已经校验过一次。
 */
class TwoFactorLoginTest {

    @Test
    fun login_requires_code_when_two_factor_enabled() {
        val server = System.getenv("MIM_SERVER_URL") ?: return
        if (System.getenv("MIM_NATIVE_LIB").isNullOrBlank()) return

        val username = "uifa" + System.currentTimeMillis().toString().takeLast(8)
        val password = "UiTestPass1234"
        val backend = SecureBackend(server, Files.createTempDirectory("mim-2fa"))

        runBlocking {
            val registration = runCatching { backend.register(username, password, null) }
            if (registration.isFailure) {
                val message = registration.exceptionOrNull()?.message.orEmpty()
                if (message.contains("请求过于频繁")) return@runBlocking
                throw registration.exceptionOrNull()!!
            }

            val login = TestAccounts.withLoginBudget {
                backend.login(username, password, "UI 2FA")
            }

            val setup = postJson("$server/user/2fa/setup", login.token, null).data
            val secret = setup?.get("secret")?.jsonPrimitive?.content
            assertTrue(!secret.isNullOrBlank(), "2FA setup 应返回共享密钥：$setup")

            val enable = postJson(
                "$server/user/2fa/enable",
                login.token,
                """{"code":"${totp(secret!!)}"}""",
            )
            val codes = Regex("\"([A-Z0-9-]{14})\"").findAll(enable.raw)
                .map { it.groupValues[1] }
                .toList()
            assertTrue(codes.isNotEmpty(), "启用 2FA 应返回恢复码：${enable.raw}")

            // 不验证证码：服务端会要求补码，客户端应识别成「需要验证码」而不是密码错误。
            val needsCode = runCatching {
                TestAccounts.withLoginBudget {
                    backend.login(username, password, "UI 2FA")
                }
            }.exceptionOrNull()
            assertTrue(
                needsCode is TwoFactorRequiredException,
                "应识别为需要二次验证：$needsCode",
            )

            // 用一次性恢复码登录。
            val withCode = TestAccounts.withLoginBudget {
                backend.login(username, password, "UI 2FA", codes.first())
            }
            assertEquals(username, withCode.displayName, "恢复码应能登录")
        }
    }

    // ---------------- 最小 HTTP / TOTP 工具 ----------------

    private fun postJson(url: String, token: String, body: String?): Envelope {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = body != null
        if (body != null) {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val element = json.parseToJsonElement(raw).jsonObject
        return Envelope(
            raw = raw,
            data = runCatching { element["data"]!!.jsonObject }.getOrNull(),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    private data class Envelope(val raw: String, val data: JsonObject?)

    /** 标准 TOTP：HMAC-SHA1 + 30 秒步长 + 6 位。 */
    private fun totp(secret: String): String {
        val key = base32(secret)
        val counter = Instant.now().epochSecond / 30
        val message = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(key, "HmacSHA1"))
        }
        val hash = mac.doFinal(message)
        val offset = hash.last().toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return String.format("%06d", binary % 1_000_000)
    }

    private fun base32(value: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = value.trim().replace("=", "").uppercase()
        var buffer = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (char in clean) {
            val index = alphabet.indexOf(char)
            if (index < 0) continue
            buffer = (buffer shl 5) or index
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
