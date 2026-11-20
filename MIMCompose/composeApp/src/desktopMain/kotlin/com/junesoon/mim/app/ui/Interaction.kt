package com.junesoon.mim.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp

/**
 * 桌面端交互基线：悬停反馈、手型光标、按下态。
 *
 * Compose 的 clickable 在桌面上没有「悬停」概念，不做这一层的话，鼠标移上去
 * 完全没有反馈，界面会显得像静态截图。这里把三件事合成一个 Modifier：
 * 手型光标 + 悬停底色 + 按下底色。
 */
@Composable
fun Modifier.mimHoverable(
    shape: Shape = RoundedCornerShape(10.dp),
    hoverColor: Color = MimPalette.Hover,
    pressColor: Color = MimPalette.Pressed,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val tint = when {
        pressed -> pressColor
        hovered -> hoverColor
        else -> Color.Transparent
    }
    var modifier = this
        .clip(shape)
        .background(tint, shape)
    if (enabled) {
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand)
        modifier = if (onClick != null) {
            modifier.clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
        } else {
            // 只追踪悬停、不消费点击：让内部的子控件（按钮、菜单）自己处理点击。
            modifier.hoverable(interaction)
        }
    }
    return modifier
}

/**
 * 桌面 tooltip。图标按钮没有文字，没有提示就只能靠猜。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MimTooltip(text: String?, content: @Composable () -> Unit) {
    if (text.isNullOrBlank()) {
        content()
        return
    }
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MimPalette.Ink.copy(alpha = 0.92f),
                contentColor = Color(0xFFFDFDFB),
                shadowElevation = 4.dp,
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        },
        delayMillis = 450,
        content = content,
    )
}

/** 文字链接：悬停时加深并加下划线，让人知道它可以点。 */
@Composable
fun MimLink(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (hovered) MimPalette.TealDeep else MimPalette.Teal,
        textDecoration = if (hovered) TextDecoration.Underline else null,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 2.dp, horizontal = 1.dp),
    )
}

/**
 * 头部图标按钮：统一的点击热区、悬停圆底、可选 tooltip 与角标。
 * 两个页面的头部都用它，避免各写一套导致大小与对齐不一致。
 */
@Composable
fun MimIconAction(
    icon: MimIcon,
    tooltip: String? = null,
    badge: Int = 0,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    MimTooltip(tooltip) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .mimHoverable(shape = CircleShape, onClick = onClick)
                .padding(7.dp),
            contentAlignment = Alignment.Center,
        ) {
            MimIcon(
                icon,
                size = 17.dp,
                tint = if (active) MimPalette.Teal else MimPalette.InkSoft,
            )
            if (badge > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 1.dp, end = 1.dp),
                ) {
                    UnreadBadge(badge)
                }
            }
        }
    }
}
