package com.junesoon.mim.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * 桌面习惯：Esc 返回上一层。
 *
 * <p>用 preview 阶段拦截，但对话框 / 下拉菜单是独立的窗口，它们会先消费 Esc，
 * 这里不会把下层页面一起关掉；图片查看器是页内浮层，所以要自己判断。
 */
fun Modifier.escapeBack(enabled: Boolean = true, onBack: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onBack()
                true
            } else {
                false
            }
        }
    }
