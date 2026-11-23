package com.junesoon.mim.app.domain

import java.security.MessageDigest
import java.util.Base64

/**
 * 设备身份公钥的短指纹，用于「历史同步」确认弹窗里的目视比对。
 *
 * 取 SPKI DER 的 SHA-256，转成大写十六进制后按 4 位分组，共 8 组（128 位）。
 * 不做安全决策，只做人眼比对：完整公钥太长，抄不下来。
 */
object DeviceFingerprint {

    fun of(publicKeyBase64: String): String? = runCatching {
        val der = Base64.getDecoder().decode(publicKeyBase64)
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        val hex = digest.joinToString("") { byte -> "%02X".format(byte) }
        hex.take(32).chunked(4).joinToString(" ")
    }.getOrNull()
}
