package com.junesoon.mim.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * 头像：有真实图片就显示图片，没有就按名字稳定推导配色与首字母。
 *
 * 图片由调用方从 `AppState.avatars` 取（服务端只存头像，不是端到端加密的）。
 */
private val AvatarPalettes = listOf(
    listOf(Color(0xFF0F5B4F), Color(0xFF2E8474)),
    listOf(Color(0xFF5B4B8A), Color(0xFF8E7BC0)),
    listOf(Color(0xFF8A5A2B), Color(0xFFC08A4E)),
    listOf(Color(0xFF345B7A), Color(0xFF6C93AE)),
    listOf(Color(0xFF6B3F4A), Color(0xFFA9707C)),
    listOf(Color(0xFF446B3F), Color(0xFF7DA472)),
)

@Composable
fun Avatar(
    name: String,
    size: Dp = 40.dp,
    online: Boolean? = null,
    image: androidx.compose.ui.graphics.ImageBitmap? = null,
    modifier: Modifier = Modifier,
) {
    val palette = AvatarPalettes[stableIndex(name)]
    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = name },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Brush.linearGradient(palette)),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                androidx.compose.foundation.Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    initial(name),
                    color = Color(0xFFFDFDFB),
                    fontSize = (size.value * 0.38f).sp,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        if (online == true) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.3f)
                    .clip(CircleShape)
                    .background(Color(0xFF2E9E5B))
                    .border(2.dp, MimPalette.Card, CircleShape),
            )
        }
    }
}

/** 把服务端下发的 PNG 解成可绘制的位图；同一份字节只解一次。 */
@Composable
fun rememberAvatar(bytes: ByteArray?): androidx.compose.ui.graphics.ImageBitmap? =
    androidx.compose.runtime.remember(bytes) {
        bytes?.let { MediaTools.decodeImage(it) }
    }

private fun initial(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) {
        return "?"
    }
    return trimmed.take(1).uppercase()
}

private fun stableIndex(name: String): Int {
    var hash = 0
    for (char in name) {
        hash = hash * 31 + char.code
    }
    return (hash and Int.MAX_VALUE) % AvatarPalettes.size
}
