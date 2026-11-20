package com.junesoon.mim.app.data

import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 二次验证的完整链路：开启 → 登录需要验证码 → 恢复码登录 → 关闭。
 *
 * 全部走客户端公开 API（SecureBackend），这样界面用到的每个方法都有覆盖；
 * 只有 TOTP 码本身在测试里用标准 HMAC-SHA1 现算。
 */
class TwoFactorLoginTest {

    @Test
    fun enable_login_with_recovery_code_disable() {
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
            assertFalse(backend.twoFactorEnabled(login.token), "新账号不应已开启二次验证")

            val setup = backend.setupTwoFactor(login.token)
            assertTrue(setup.secret.isNotBlank(), "应返回共享密钥")
            assertTrue(setup.otpauthUri.startsWith("otpauth://"), "应返回可导入的 otpauth 链接")

            val enabled = backend.enableTwoFactor(login.token, totp(setup.secret))
            assertTrue(enabled.token.isNotBlank(), "启用后应换发新令牌")
            assertTrue(enabled.recoveryCodes.size >= 5, "应返回一组恢复码：${enabled.recoveryCodes}")
            assertTrue(backend.twoFactorEnabled(enabled.token), "启用后状态应为已开启")

            // 不带验证码：应被识别为「需要验证码」，而不是密码错误。
            val missingCode = runCatching {
                TestAccounts.withLoginBudget {
                    backend.login(username, password, "UI 2FA")
                }
            }.exceptionOrNull()
            assertTrue(
                missingCode is TwoFactorRequiredException,
                "应识别为需要二次验证：$missingCode",
            )

            // 用一条恢复码登录。
            val withRecovery = TestAccounts.withLoginBudget {
                backend.login(username, password, "UI 2FA", enabled.recoveryCodes[0])
            }
            assertEquals(username, withRecovery.displayName, "恢复码应能登录")

            // 关闭二次验证：需要密码 + 另一条恢复码。
            val newToken = backend.disableTwoFactor(
                withRecovery.token,
                password,
                enabled.recoveryCodes[1],
            )
            assertTrue(newToken.isNotBlank(), "关闭后应换发新令牌")
            assertFalse(backend.twoFactorEnabled(newToken), "关闭后状态应为未开启")

            // 之后不带验证码也能登录。
            val plain = TestAccounts.withLoginBudget {
                backend.login(username, password, "UI 2FA")
            }
            assertEquals(username, plain.displayName)
        }
    }

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
