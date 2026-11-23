package com.junesoon.mim.app.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/**
 * 应用级界面偏好（不属于某个账号），目前只存外观模式。
 *
 * <p>放在保险库根目录下但**不加密**：里面只有一行 `theme=Dark`，
 * 加密它只会让「主题怎么没记住」这种问题难排查。
 */
object UiPrefs {

    private const val FILE = "ui.prefs"

    fun theme(): String? = runCatching {
        val path = file()
        if (!Files.exists(path)) {
            null
        } else {
            Files.readAllLines(path, Charsets.UTF_8)
                .firstOrNull { it.startsWith("theme=") }
                ?.substringAfter('=')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    fun saveTheme(value: String) {
        runCatching {
            val path = file()
            Files.createDirectories(path.parent)
            Files.write(path, listOf("theme=$value"), Charsets.UTF_8)
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    private fun file(): Path =
        Paths.get(LocalVault.defaultRoot().toString()).resolve(FILE)
}
