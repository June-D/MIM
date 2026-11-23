package com.junesoon.mim.app

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension

/**
 * 桌面入口。
 *
 * 默认窗口按「列表 + 聊天」两栏排：左栏固定 312dp，右栏吃掉剩余宽度；
 * 最小尺寸保证两栏都不至于挤成一团（再窄就只能单栏，留给移动端）。
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "MiM · 私密通讯",
        state = rememberWindowState(
            width = 1120.dp,
            height = 760.dp,
            position = WindowPosition(Alignment.Center),
        ),
    ) {
        // 最小尺寸按 dp 换算成物理像素：直接写 Dimension(880, 620) 在 2x 屏上
        // 只有 440dp 宽，两栏会被挤塌。
        val density = androidx.compose.ui.platform.LocalDensity.current
        window.minimumSize = Dimension(
            with(density) { 880.dp.roundToPx() },
            with(density) { 620.dp.roundToPx() },
        )
        MimApp()
    }
}
