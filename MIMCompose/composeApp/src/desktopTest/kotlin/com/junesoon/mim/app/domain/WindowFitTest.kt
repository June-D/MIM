package com.junesoon.mim.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 窗口尺寸的两条硬约束：初始值不超过屏幕可用区，最小值不超过初始值。
 *
 * 后者就是「窗口拉不动」的根因：最小尺寸大于初始尺寸时，窗口被约束在原尺寸上。
 */
class WindowFitTest {

    @Test
    fun large_screen_uses_preferred_size() {
        val layout = WindowFit.fit(1920, 1080)
        assertEquals(1120, layout.initial.width)
        assertEquals(760, layout.initial.height)
        assertEquals(880, layout.minimum.width)
        assertEquals(620, layout.minimum.height)
    }

    @Test
    fun small_screen_shrinks_to_fit() {
        val layout = WindowFit.fit(1080, 700)
        assertEquals(1032, layout.initial.width)
        assertEquals(652, layout.initial.height)
        assertTrue(layout.initial.fitsInside(WindowFit.Size(1080, 700)), "不能超出屏幕")
        assertTrue(layout.minimum.fitsInside(layout.initial), "最小值不能大于初始值")
        assertEquals(620, layout.minimum.height, "652 仍容得下 620，最小值不必降")
        // 更矮的屏幕才会把最小值也压下来（见下一个用例）。
        assertEquals(880, layout.minimum.width)
    }

    @Test
    fun tiny_screen_still_gets_usable_window() {
        val layout = WindowFit.fit(800, 600)
        assertEquals(752, layout.initial.width)
        assertEquals(552, layout.initial.height)
        assertTrue(layout.minimum.fitsInside(layout.initial))
        assertTrue(layout.initial.width >= 420 && layout.initial.height >= 320)
    }
}
