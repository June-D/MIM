package com.junesoon.mim.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 主题：偏「纸与墨」的暖色调，避免默认紫色的模板感。
 * 颜色集中在这里，后续换深色主题只改这一处。
 */
private val MimColors = lightColorScheme(
    primary = Color(0xFF14453A),
    onPrimary = Color(0xFFFFFDF7),
    primaryContainer = Color(0xFFD7E8DF),
    onPrimaryContainer = Color(0xFF0B2A23),
    secondary = Color(0xFF9A5B2E),
    onSecondary = Color(0xFFFFFDF7),
    background = Color(0xFFF6F2EA),
    onBackground = Color(0xFF1D1B16),
    surface = Color(0xFFFFFDF7),
    onSurface = Color(0xFF1D1B16),
    surfaceVariant = Color(0xFFEAE4D8),
    onSurfaceVariant = Color(0xFF4A463C),
    error = Color(0xFF8C1D18),
)

@Composable
fun MimTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MimColors, content = content)
}
