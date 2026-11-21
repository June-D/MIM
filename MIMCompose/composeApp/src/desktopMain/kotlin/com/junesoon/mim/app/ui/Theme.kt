package com.junesoon.mim.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MIM 视觉基线：暖白纸面 + 深青主色 + 黄铜点缀（深色模式为暖黑 + 薄荷青）。
 *
 * 选深青而不是常见蓝紫：它更接近「安全、克制」的品牌语义，
 * 也不会像默认 Material 模板。颜色、字体、圆角都只在这里定义。
 *
 * <p>调色板是进程级单例（[dark] 由 [MimTheme] 在组合开始时设置）：
 * 桌面端只有一个窗口、一种主题，用 CompositionLocal 会让 300 多处调用点全部改写，
 * 收益不抵风险。切换主题时 [MimTheme] 会重组整棵树，各 getter 自然读到新值。
 *
 * <p>两套色板都必须各自核对对比度，不能把浅色值直接调暗。
 */
object MimPalette {

    /** 由 [MimTheme] 设置；深色模式下为 true。 */
    internal var dark: Boolean = false

    // ---------------- 文本与底色 ----------------

    /** 正文主色：浅色近黑，深色近白（暖调，不用纯白，避免刺眼）。 */
    val Ink: Color get() = if (dark) Color(0xFFEDEAE3) else Color(0xFF17181A)
    val InkSoft: Color get() = if (dark) Color(0xFFA19C93) else Color(0xFF5F5C55)

    /** 页面底色。 */
    val Paper: Color get() = if (dark) Color(0xFF131513) else Color(0xFFF7F5F0)

    /** 卡片 / 列表栏 / 输入区底色，比 [Paper] 高一层。 */
    val Card: Color get() = if (dark) Color(0xFF1B1E1B) else Color(0xFFFFFFFF)

    val Line: Color get() = if (dark) Color(0xFF333830) else Color(0xFFE4DFD6)
    val LineSoft: Color get() = if (dark) Color(0xFF272B25) else Color(0xFFEFEBE4)

    // ---------------- 主色 ----------------

    /** 强调色（文字、图标、边框）。深色下提亮，才能压在深底上。 */
    val Teal: Color get() = if (dark) Color(0xFF6FD3BC) else Color(0xFF0F5B4F)

    /** 实心块（主按钮、未读角标）的填充色，与上者分开：一处当底色、一处当字色。 */
    val TealFill: Color get() = if (dark) Color(0xFF1E7A68) else Color(0xFF0F5B4F)

    /** 压在 [TealFill] 上的文字 / 图标。 */
    val OnTeal: Color get() = if (dark) Color(0xFFF3FBF8) else Color(0xFFFDFDFB)

    /** 在 [TealSoft] 上的强调文字（提示条、选中行标题）。 */
    val TealDeep: Color get() = if (dark) Color(0xFF9FE0CF) else Color(0xFF0A3B33)

    /** 主色的容器底色（图标圆底、提示条）。 */
    val TealSoft: Color get() = if (dark) Color(0xFF1C3A34) else Color(0xFFDDEBE6)

    // ---------------- 点缀与语义色 ----------------

    val Brass: Color get() = if (dark) Color(0xFFC9A063) else Color(0xFF9A6F35)
    val BrassSoft: Color get() = if (dark) Color(0xFF33291B) else Color(0xFFF4E9D6)

    /** 压在 [BrassSoft] 上的文字。 */
    val OnBrassSoft: Color get() = if (dark) Color(0xFFE8CFA6) else Color(0xFF3E2B12)

    val Danger: Color get() = if (dark) Color(0xFFF08A7E) else Color(0xFFB3261E)
    val DangerSoft: Color get() = if (dark) Color(0xFF3A1E1B) else Color(0xFFFBEAE8)

    // ---------------- 气泡 ----------------

    /** 自己发出的气泡：两套色板都保持深青，白字对比度稳定。 */
    val BubbleMine: Color get() = if (dark) Color(0xFF1C6F5F) else Color(0xFF0F5B4F)
    val BubblePeer: Color get() = if (dark) Color(0xFF232722) else Color(0xFFFFFFFF)

    // ---------------- 状态 ----------------

    /** 悬停 / 按下底色：浅色用主色淡染，深色用白光提亮。 */
    val Hover: Color get() = if (dark) Color(0x14FFFFFF) else Color(0x0D0F5B4F)
    val Pressed: Color get() = if (dark) Color(0x24FFFFFF) else Color(0x1A0F5B4F)

    /** 当前会话行的选中底色。 */
    val Selected: Color get() = if (dark) Color(0x2A6FD3BC) else Color(0x240F5B4F)

    /** 卡片投影色：深色模式不用投影（看不见），改用 1px 边框分层。 */
    val Shadow: Color get() = if (dark) Color(0xFF000000) else Color(0xFF2A2620)
}

private fun lightScheme() = lightColorScheme(
    primary = MimPalette.Teal,
    onPrimary = MimPalette.OnTeal,
    primaryContainer = MimPalette.TealSoft,
    onPrimaryContainer = MimPalette.TealDeep,
    secondary = MimPalette.Brass,
    onSecondary = Color(0xFFFDFDFB),
    secondaryContainer = MimPalette.BrassSoft,
    onSecondaryContainer = MimPalette.OnBrassSoft,
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
    onErrorContainer = MimPalette.Danger,
)

private fun darkScheme() = darkColorScheme(
    primary = MimPalette.Teal,
    onPrimary = MimPalette.OnTeal,
    primaryContainer = MimPalette.TealSoft,
    onPrimaryContainer = MimPalette.TealDeep,
    secondary = MimPalette.Brass,
    onSecondary = Color(0xFF20180C),
    secondaryContainer = MimPalette.BrassSoft,
    onSecondaryContainer = MimPalette.OnBrassSoft,
    tertiary = Color(0xFF9CBBD6),
    onTertiary = Color(0xFF10161C),
    background = MimPalette.Paper,
    onBackground = MimPalette.Ink,
    surface = MimPalette.Card,
    onSurface = MimPalette.Ink,
    surfaceVariant = MimPalette.Paper,
    onSurfaceVariant = MimPalette.InkSoft,
    outline = MimPalette.Line,
    outlineVariant = MimPalette.LineSoft,
    error = MimPalette.Danger,
    onError = Color(0xFF2A0F0C),
    errorContainer = MimPalette.DangerSoft,
    onErrorContainer = MimPalette.Danger,
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

/** 外观：跟随系统 / 强制浅色 / 强制深色。仅本机偏好，目前不持久化。 */
enum class ThemeMode(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色"),
    ;

    fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]
}

/**
 * 主题入口。默认跟随系统外观；[dark] 显式传入用于「浅色 / 深色」手动切换。
 */
@Composable
fun MimTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // 必须在构建 ColorScheme 之前落值：色板 getter 直接读这个开关。
    MimPalette.dark = dark
    val scheme = remember(dark) { if (dark) darkScheme() else lightScheme() }
    MaterialTheme(
        colorScheme = scheme,
        typography = MimTypography,
        shapes = MimShapes,
        content = content,
    )
}
