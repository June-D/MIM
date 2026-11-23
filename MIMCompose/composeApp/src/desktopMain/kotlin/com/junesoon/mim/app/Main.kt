package com.junesoon.mim.app

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.junesoon.mim.app.domain.WindowFit
import java.awt.Dimension
import java.awt.GraphicsEnvironment

/**
 * 桌面入口。
 *
 * 默认窗口按「列表 + 聊天」两栏排：左栏固定 312dp，右栏吃掉剩余宽度。
 *
 * 尺寸有两处坑，都在 [WindowFit] 里处理：
 * 1. 初始 1120×760 在小屏上会超出屏幕可用区（底部输入框被顶到屏幕外）；
 * 2. `window.minimumSize` 的单位是 AWT 的「点」，不是物理像素——按 density 换算
 *    会得到 2 倍（880dp → 1760），比屏幕还宽，窗口就再也缩不小了。
 */
fun main() = application {
    val work = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val layout = WindowFit.fit(work.width, work.height)
    Window(
        onCloseRequest = ::exitApplication,
        title = "MiM · 私密通讯",
        state = rememberWindowState(
            width = layout.initial.width.dp,
            height = layout.initial.height.dp,
            position = WindowPosition(Alignment.Center),
        ),
    ) {
        // AWT 尺寸与 Compose 的 dp 在同一套单位里，直接传，不要再乘 density。
        window.minimumSize = Dimension(layout.minimum.width, layout.minimum.height)
        MimApp()
    }
}
