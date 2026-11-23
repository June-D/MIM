package com.junesoon.mim.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 附件大小展示：列表、气泡、搜索结果都用这一套写法。 */
class MediaToolsTest {

    /**
     * 头像一律重编码成 PNG：这既统一了服务端的格式校验，
     * 也顺手丢掉 EXIF（相机型号、GPS 不该跟着头像进服务器）。
     */
    @Test
    fun avatar_is_reencoded_to_png() {
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(4, 4)
        surface.canvas.clear(org.jetbrains.skia.Color.RED)
        val jpeg = surface.makeImageSnapshot()
            .encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG)!!.bytes

        val png = MediaTools.normalizeAvatarPng(jpeg)
        assertTrue(png != null, "JPEG 应能被重编码")
        val magic = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        assertTrue(png!!.copyOfRange(0, 4).contentEquals(magic), "输出必须是 PNG 魔数开头")
        assertTrue(MediaTools.decodeImage(png) != null, "重编码结果应能被解回来")
    }

    @Test
    fun garbage_is_not_accepted_as_avatar() {
        assertEquals(null, MediaTools.normalizeAvatarPng("not an image".toByteArray()))
    }

    @Test
    fun human_size_switches_unit_at_the_right_threshold() {
        assertEquals("0 B", MediaTools.humanSize(0))
        assertEquals("999 B", MediaTools.humanSize(999))
        assertEquals("1.0 KB", MediaTools.humanSize(1024))
        assertEquals("1.5 KB", MediaTools.humanSize(1536))
        assertEquals("1.0 MB", MediaTools.humanSize(1024L * 1024L))
        assertEquals("8.0 MB", MediaTools.humanSize(8L * 1024L * 1024L))
    }
}
