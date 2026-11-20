package com.junesoon.mim.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** 附件大小展示：列表、气泡、搜索结果都用这一套写法。 */
class MediaToolsTest {

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
