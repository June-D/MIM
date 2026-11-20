package com.junesoon.mim.app.data

import java.util.Base64

/** 十六进制 / Base64 转换。协议边界混用两种编码，转换集中在这里。 */
internal object Codec {

    fun hexToBase64(hex: String): String =
        Base64.getEncoder().encodeToString(hexToBytes(hex))

    fun base64ToHex(value: String): String =
        bytesToHex(Base64.getDecoder().decode(value))

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
