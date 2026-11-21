package com.junesoon.mim.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 空态的统一规格：图标圆底 + 标题 + 一段说明 + 可选主操作。
 *
 * 左右两栏各写一套会漂移（图标 64 vs 52、文案贴边、断行不同），
 * 所以收成一个组件：圆底 54dp、图标 22dp、说明限宽 236dp 居中、间距 9dp。
 */
@Composable
fun MimEmptyState(
    icon: MimIcon,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionIcon: MimIcon? = null,
    onAction: (() -> Unit)? = null,
) {
    // 空态可能渲染在 312dp 的列表栏里：必须自己收住宽度，否则正文会顶到两边缘。
    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(MimPalette.TealSoft),
                contentAlignment = Alignment.Center,
            ) {
                MimIcon(icon, size = 22.dp, tint = MimPalette.Teal)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MimPalette.Ink,
            )
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MimPalette.InkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 236.dp),
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MimPalette.TealFill)
                        .mimHoverable(
                            shape = RoundedCornerShape(10.dp),
                            hoverColor = Color(0x1FFFFFFF),
                            pressColor = Color(0x33FFFFFF),
                            onClick = onAction,
                        )
                        .padding(horizontal = 15.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (actionIcon != null) {
                        MimIcon(actionIcon, size = 14.dp, tint = MimPalette.OnTeal)
                    }
                    Text(
                        actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MimPalette.OnTeal,
                    )
                }
            }
        }
    }
}
