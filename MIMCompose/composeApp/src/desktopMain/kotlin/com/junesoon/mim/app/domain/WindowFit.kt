package com.junesoon.mim.app.domain

/**
 * 窗口初始尺寸与最小尺寸的计算。
 *
 * 两条约束同时成立才算对：
 * 1. 初始窗口不能比屏幕可用区域还大——小屏（1280×800、1080×700）上直接写 1120×760
 *    会顶到屏幕外，底部按钮看不见；
 * 2. 最小尺寸必须**小于等于**初始尺寸，否则窗口一旦被约束到最小值就再也拉不动了。
 *
 * 单位是 AWT 的「点」（在 macOS 上等于 Compose 的 dp）：`window.minimumSize` 用的就是
 * 这套单位，不是物理像素——按 density 乘一遍会得到 2 倍，窗口反而缩不小。
 */
object WindowFit {

    const val PREFERRED_WIDTH = 1120
    const val PREFERRED_HEIGHT = 760

    /** 两栏布局不塌的下限：左栏 312 + 右栏最小可读区。 */
    const val MIN_WIDTH = 880
    const val MIN_HEIGHT = 620

    /** 给菜单栏 / 任务栏 / 窗口边框留的余量。 */
    const val MARGIN = 48

    /** 再小就没有意义了，保底一个能看的尺寸。 */
    private const val FLOOR_WIDTH = 420
    private const val FLOOR_HEIGHT = 320

    data class Size(val width: Int, val height: Int) {
        fun fitsInside(other: Size): Boolean =
            width <= other.width && height <= other.height
    }

    data class Layout(val initial: Size, val minimum: Size)

    /**
     * 按屏幕可用区域算出初始尺寸与最小尺寸。
     *
     * @param workWidth  屏幕可用宽度（已扣除菜单栏 / 任务栏）
     * @param workHeight 屏幕可用高度
     */
    fun fit(workWidth: Int, workHeight: Int): Layout {
        val width = minOf(PREFERRED_WIDTH, workWidth - MARGIN).coerceAtLeast(FLOOR_WIDTH)
        val height = minOf(PREFERRED_HEIGHT, workHeight - MARGIN).coerceAtLeast(FLOOR_HEIGHT)
        // 最小值不能大于初始值：否则用户看到的就是「窗口拉不动」。
        return Layout(
            initial = Size(width, height),
            minimum = Size(minOf(MIN_WIDTH, width), minOf(MIN_HEIGHT, height)),
        )
    }
}
