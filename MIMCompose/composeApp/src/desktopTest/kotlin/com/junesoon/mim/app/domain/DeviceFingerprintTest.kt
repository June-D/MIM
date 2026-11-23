package com.junesoon.mim.app.domain

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 设备指纹只用于目视比对：同样的公钥必须稳定输出同样的分组，
 * 换一把公钥就必须不同，否则「对不上说明不是同一台机器」这句话是假的。
 */
class DeviceFingerprintTest {

    private val key = Base64.getEncoder().encodeToString(ByteArray(91) { (it + 3).toByte() })

    @Test
    fun formats_as_eight_groups_of_four() {
        val fingerprint = DeviceFingerprint.of(key)
        assertTrue(fingerprint != null, "合法公钥应能算出指纹")
        val groups = fingerprint!!.split(" ")
        assertEquals(8, groups.size)
        groups.forEach { assertEquals(4, it.length) }
        assertTrue(groups.all { group -> group.all { it in "0123456789ABCDEF" } })
    }

    @Test
    fun is_stable_and_key_dependent() {
        assertEquals(DeviceFingerprint.of(key), DeviceFingerprint.of(key))
        val other = Base64.getEncoder().encodeToString(ByteArray(91) { (it + 9).toByte() })
        assertNotEquals(DeviceFingerprint.of(key), DeviceFingerprint.of(other))
        assertEquals(null, DeviceFingerprint.of("not-base64"))
    }
}
