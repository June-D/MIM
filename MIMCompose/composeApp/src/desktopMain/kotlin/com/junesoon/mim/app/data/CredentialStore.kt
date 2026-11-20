package com.junesoon.mim.app.data

import com.junesoon.mim.core.MimCoreNative
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.Serializable

/**
 * 「记住账号密码」的本地存储。
 *
 * 内容用 AES-256-GCM 加密，密钥是同目录下的 `remember.key`（0600）。
 * 这只防住「别人随手翻到明文」，不防能读你用户目录的恶意程序——
 * 桌面端没有系统级密钥库，这一点必须在界面上说清楚。
 */
class CredentialStore(private val root: Path = LocalVault.defaultRoot()) {

    private val storeFile: Path = root.resolve("remember.bin")
    private val keyFile: Path = root.resolve("remember.key")

    fun load(): RememberedLogin? {
        if (!Files.exists(storeFile)) {
            return null
        }
        return runCatching {
            val key = Files.readAllBytes(keyFile)
            val sealed = Files.readAllBytes(storeFile)
            val plain = MimCoreNative.decryptState(sealed, key)
            JsonCodec.decode<RememberedLogin>(plain.toString(Charsets.UTF_8))
        }.getOrNull()
    }

    fun save(login: RememberedLogin) {
        Files.createDirectories(root)
        val key = if (Files.exists(keyFile)) {
            Files.readAllBytes(keyFile)
        } else {
            MimCoreNative.randomBytes(32).also {
                Files.write(keyFile, it)
                restrict(keyFile)
            }
        }
        val nonce = MimCoreNative.randomBytes(12)
        val sealed = MimCoreNative.encryptState(
            JsonCodec.encode(login).toByteArray(Charsets.UTF_8),
            key,
            nonce,
        )
        Files.write(storeFile, sealed)
        restrict(storeFile)
    }

    fun clear() {
        runCatching { Files.deleteIfExists(storeFile) }
        runCatching { Files.deleteIfExists(keyFile) }
    }

    private fun restrict(file: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                file,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}

/**
 * 记住的登录信息。
 *
 * `autoLogin` 区分两种状态：勾选记住后是 true（启动自动登录）；
 * 用户主动退出登录后改为 false——密码仍保留用于预填，
 * 但不会自动把用户登回去。
 */
@Serializable
data class RememberedLogin(
    val username: String,
    val password: String,
    val deviceName: String,
    val autoLogin: Boolean = true,
)
