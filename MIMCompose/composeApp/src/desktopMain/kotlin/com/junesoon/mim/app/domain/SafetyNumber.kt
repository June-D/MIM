package com.junesoon.mim.app.domain

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * 双方可对照的 60 位安全码（与 Java 客户端算法一致）。
 *
 * 把双方身份公钥按字节序排序后做 SHA-256，取十进制模 10^60；
 * 因为先排序，双方算出的结果相同。用于首次联系的线下核对。
 */
object SafetyNumber {

    private const val DIGITS = 60
    private const val GROUP = 5

    fun of(ownPublicKeyBase64: String, peerPublicKeyBase64: String): String {
        val own = Base64.getDecoder().decode(ownPublicKeyBase64)
        val peer = Base64.getDecoder().decode(peerPublicKeyBase64)
        val (first, second) = if (compare(own, peer) > 0) peer to own else own to peer
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(first)
        digest.update(second)
        val value = BigInteger(1, digest.digest())
        val digits = String.format(
            Locale.ROOT,
            "%0${DIGITS}d",
            value.mod(BigInteger.TEN.pow(DIGITS)),
        )
        return buildString {
            var index = 0
            while (index < DIGITS) {
                if (index > 0) {
                    append(if (index % 30 == 0) '\n' else ' ')
                }
                append(digits, index, index + GROUP)
                index += GROUP
            }
        }
    }

    private fun compare(left: ByteArray, right: ByteArray): Int {
        val length = minOf(left.size, right.size)
        for (index in 0 until length) {
            val a = left[index].toInt() and 0xff
            val b = right[index].toInt() and 0xff
            if (a != b) {
                return a - b
            }
        }
        return left.size - right.size
    }
}
