package com.junesoon.mim.app.domain

/**
 * 键盘选择列表时的游标移动。
 *
 * 规则刻意做得简单可测：还没选中时按下键落到第一项、按上键落到最后一项；
 * 已在边界继续按时停在原地（不循环，避免"按到底突然跳回顶部"）。
 */
fun nextCursor(current: Int, size: Int, delta: Int): Int = when {
    size <= 0 -> -1
    current < 0 -> if (delta > 0) 0 else size - 1
    else -> (current + delta).coerceIn(0, size - 1)
}
