package com.junesoon.mim.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MIM 视觉基线：暖白纸面 + 深青主色 + 黄铜点缀。
 *
 * 选深青而不是常见蓝紫：它更接近「安全、克制」的品牌语义，
 * 也不会像默认 Material 模板。颜色、字体、圆角都只在这里定义，
 * 换主题或做深色模式时改这一处即可。
 */
object MimPalette {

    val Ink = Color(0xFF17181A)
    val InkSoft = Color(0xFF5F5C55)
    val Paper = Color(0xFFF7F5F0)
    val Card = Color(0xFFFFFFFF)
    val Line = Color(0xFFE4DFD6)
    val LineSoft = Color(0xFFEFEBE4)
    val Teal = Color(0xFF0F5B4F)
    val TealDeep = Color(0xFF0A3B33)
    val TealSoft = Color(0xFFDDEBE6)
    val Brass = Color(0xFF9A6F35)
    val BrassSoft = Color(0xFFF4E9D6)
    val Danger = Color(0xFFB3261E)
    val DangerSoft = Color(0xFFFBEAE8)
    val BubbleMine = Color(0xFF0F5B4F)
    val BubblePeer = Color(0xFFFFFFFF)
}

private val MimColorScheme = lightColorScheme(
    primary = MimPalette.Teal,
    onPrimary = Color(0xFFFDFDFB),
    primaryContainer = MimPalette.TealSoft,
    onPrimaryContainer = MimPalette.TealDeep,
    secondary = MimPalette.Brass,
    onSecondary = Color(0xFFFDFDFB),
    secondaryContainer = MimPalette.BrassSoft,
    onSecondaryContainer = Color(0xFF3E2B12),
    tertiary = Color(0xFF4A6B8A),
    onTertiary = Color(0xFFFDFDFB),
    background = MimPalette.Paper,
    onBackground = MimPalette.Ink,
    surface = MimPalette.Card,
    onSurface = MimPalette.Ink,
    surfaceVariant = MimPalette.Paper,
    onSurfaceVariant = MimPalette.InkSoft,
    outline = MimPalette.Line,
    outlineVariant = MimPalette.LineSoft,
    error = MimPalette.Danger,
    onError = Color(0xFFFDFDFB),
    errorContainer = MimPalette.DangerSoft,
    onErrorContainer = Color(0xFF5F1310),
)

private val MimTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp,
    ),
)

private val MimShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MimTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MimColorScheme,
        typography = MimTypography,
        shapes = MimShapes,
        content = content,
    )
}
