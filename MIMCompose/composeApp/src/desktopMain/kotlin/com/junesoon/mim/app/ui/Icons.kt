package com.junesoon.mim.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 本地绘制的小图标。
 *
 * 项目没有引入 material-icons 依赖，图标全部用 Canvas 画：
 * 不增加依赖体积，线条粗细与颜色也随主题统一。
 */
enum class MimIcon {
    Back,
    More,
    Send,
    Search,
    Plus,
    Check,
    Close,
    Lock,
    Shield,
    Device,
    Person,
    Edit,
    Block,
    Logout,
}

@Composable
fun MimIcon(
    icon: MimIcon,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    tint: Color = LocalContentColor.current,
) {
    Canvas(modifier = modifier.size(size)) {
        val side = this.size.minDimension
        val stroke = Stroke(width = side * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val points = DrawPoints(side)
        when (icon) {
            MimIcon.Back -> {
                drawLine(tint, points(0.62f, 0.18f), points(0.32f, 0.5f), stroke.width, StrokeCap.Round)
                drawLine(tint, points(0.32f, 0.5f), points(0.62f, 0.82f), stroke.width, StrokeCap.Round)
            }
            MimIcon.More -> {
                for (x in listOf(0.22f, 0.5f, 0.78f)) {
                    drawCircle(tint, side * 0.055f, points(x, 0.5f))
                }
            }
            MimIcon.Send -> {
                drawLine(tint, points(0.5f, 0.82f), points(0.5f, 0.2f), stroke.width, StrokeCap.Round)
                drawLine(tint, points(0.26f, 0.44f), points(0.5f, 0.2f), stroke.width, StrokeCap.Round)
                drawLine(tint, points(0.5f, 0.2f), points(0.74f, 0.44f), stroke.width, StrokeCap.Round)
            }
            MimIcon.Search -> {
                drawCircle(tint, side * 0.26f, points(0.43f, 0.43f), style = stroke)
                drawLine(tint, points(0.62f, 0.62f), points(0.8f, 0.8f), stroke.width, StrokeCap.Round)
            }
            MimIcon.Plus -> {
                drawLine(tint, points(0.5f, 0.2f), points(0.5f, 0.8f), stroke.width, StrokeCap.Round)
                drawLine(tint, points(0.2f, 0.5f), points(0.8f, 0.5f), stroke.width, StrokeCap.Round)
            }
            MimIcon.Check -> {
                drawLine(tint, points(0.22f, 0.54f), points(0.42f, 0.74f), stroke.width, StrokeCap.Round)
                drawLine(tint, points(0.42f, 0.74f), points(0.78f, 0.28f), stroke.width, StrokeCap.Round)
            }
            MimIcon.Close -> {
                drawLine(tint, points(0.26f, 0.26f), points(0.74f, 0.74f), stroke.width, StrokeCap.Round)
                drawLine(tint, points(0.74f, 0.26f), points(0.26f, 0.74f), stroke.width, StrokeCap.Round)
            }
            MimIcon.Lock -> {
                drawRoundRect(
                    color = tint,
                    topLeft = points(0.3f, 0.46f),
                    size = Size(side * 0.4f, side * 0.34f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(side * 0.06f),
                    style = stroke,
                )
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = points(0.36f, 0.3f),
                    size = Size(side * 0.28f, side * 0.28f),
                    style = stroke,
                )
            }
            MimIcon.Shield -> {
                val path = Path().apply {
                    moveTo(side * 0.5f, side * 0.16f)
                    lineTo(side * 0.8f, side * 0.28f)
                    lineTo(side * 0.8f, side * 0.52f)
                    cubicTo(
                        side * 0.8f, side * 0.72f, side * 0.66f, side * 0.84f, side * 0.5f, side * 0.87f,
                    )
                    cubicTo(
                        side * 0.34f, side * 0.84f, side * 0.2f, side * 0.72f, side * 0.2f, side * 0.52f,
                    )
                    lineTo(side * 0.2f, side * 0.28f)
                    close()
                }
                drawPath(path, tint, style = stroke)
            }
            MimIcon.Device -> {
                drawRoundRect(
                    color = tint,
                    topLeft = points(0.16f, 0.22f),
                    size = Size(side * 0.68f, side * 0.44f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(side * 0.06f),
                    style = stroke,
                )
                drawLine(tint, points(0.5f, 0.66f), points(0.5f, 0.8f), stroke.width, StrokeCap.Round)
                drawLine(tint, points(0.32f, 0.82f), points(0.68f, 0.82f), stroke.width, StrokeCap.Round)
            }
            MimIcon.Person -> {
                drawCircle(tint, side * 0.16f, points(0.5f, 0.33f), style = stroke)
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = points(0.22f, 0.5f),
                    size = Size(side * 0.56f, side * 0.5f),
                    style = stroke,
                )
            }
            MimIcon.Edit -> {
                drawLine(tint, points(0.3f, 0.7f), points(0.72f, 0.28f), stroke.width, StrokeCap.Round)
                drawLine(tint, points(0.28f, 0.58f), points(0.3f, 0.72f), stroke.width, StrokeCap.Round)
                drawLine(tint, points(0.3f, 0.72f), points(0.44f, 0.7f), stroke.width, StrokeCap.Round)
            }
            MimIcon.Block -> {
                drawCircle(tint, side * 0.3f, points(0.5f, 0.5f), style = stroke)
                drawLine(tint, points(0.29f, 0.29f), points(0.71f, 0.71f), stroke.width, StrokeCap.Round)
            }
            MimIcon.Logout -> {
                drawRoundRect(
                    color = tint,
                    topLeft = points(0.16f, 0.2f),
                    size = Size(side * 0.42f, side * 0.6f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(side * 0.06f),
                    style = stroke,
                )
                drawLine(tint, points(0.46f, 0.5f), points(0.84f, 0.5f), stroke.width, StrokeCap.Round)
                drawLine(tint, points(0.68f, 0.34f), points(0.84f, 0.5f), stroke.width, StrokeCap.Round)
                drawLine(tint, points(0.84f, 0.5f), points(0.68f, 0.66f), stroke.width, StrokeCap.Round)
            }
        }
    }
}

/** 把 0..1 的归一化坐标换算成画布坐标。 */
private class DrawPoints(private val side: Float) {
    operator fun invoke(x: Float, y: Float): Offset = Offset(side * x, side * y)
}

@Suppress("unused")
private fun DrawScope.strokeWidth(side: Float): Float = side * 0.085f
