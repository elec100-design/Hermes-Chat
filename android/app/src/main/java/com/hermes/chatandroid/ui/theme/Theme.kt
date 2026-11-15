package com.hermes.chatandroid.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 브랜드 시그니처 + 시맨틱 토큰(온라인/경고/코드/버블/보더). */
@Immutable
data class HermesColors(
    val isDark: Boolean,
    val online: Color,
    val offline: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val codeSurface: Color,
    val codeHeader: Color,
    val userBubbleStart: Color,
    val userBubbleEnd: Color,
    val onUserBubble: Color,
    val cardBorder: Color,
    val subtleDivider: Color,
)

// 팔레트: 따뜻한 크림 바탕 + 클레이(테라코타) 강조 — 채도 낮은 종이 느낌. 순백/순흑·보라 계열을 쓰지 않는다.
private val LightHermesColors = HermesColors(
    isDark = false,
    online = Color(0xFF2F7D4F),
    offline = Color(0xFFA8A69B),
    warning = Color(0xFFB7791F),
    warningContainer = Color(0xFFFBF0D9),
    onWarningContainer = Color(0xFF6B4A0F),
    codeSurface = Color(0xFFF5F4ED),
    codeHeader = Color(0xFFEBE9E0),
    userBubbleStart = Color(0xFFF0EEE6),
    userBubbleEnd = Color(0xFFF0EEE6),
    onUserBubble = Color(0xFF141413),
    cardBorder = Color(0xFFE8E6DC),
    subtleDivider = Color(0xFFEFEDE5),
)

private val DarkHermesColors = HermesColors(
    isDark = true,
    online = Color(0xFF6FCF97),
    offline = Color(0xFF7A7972),
    warning = Color(0xFFE5B45A),
    warningContainer = Color(0xFF3F3418),
    onWarningContainer = Color(0xFFF5DFA8),
    codeSurface = Color(0xFF1F1E1D),
    codeHeader = Color(0xFF2B2B29),
    userBubbleStart = Color(0xFF141413),
    userBubbleEnd = Color(0xFF141413),
    onUserBubble = Color(0xFFFAF9F5),
    cardBorder = Color(0xFF3E3D3A),
    subtleDivider = Color(0xFF333331),
)

private val LocalHermesColors = staticCompositionLocalOf { LightHermesColors }

val MaterialTheme.hermesColors: HermesColors
    @Composable
    @ReadOnlyComposable
    get() = LocalHermesColors.current

private val LightColors = lightColorScheme(
    primary = Color(0xFFBA5634),          // 흰 글자 대비 4.7:1
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF3DFD5),
    onPrimaryContainer = Color(0xFF5C2512),
    secondary = Color(0xFF5E5D59),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0EEE6),
    onSecondaryContainer = Color(0xFF3D3D3A),
    tertiary = Color(0xFF2F7D4F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCEBDF),
    onTertiaryContainer = Color(0xFF173F27),
    error = Color(0xFFB3382C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9E4E1),
    onErrorContainer = Color(0xFF6B1F1A),
    background = Color(0xFFFAF9F5),
    onBackground = Color(0xFF141413),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141413),
    surfaceVariant = Color(0xFFF0EEE6),
    onSurfaceVariant = Color(0xFF6B6A65),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F4ED),
    surfaceContainer = Color(0xFFF0EEE6),
    surfaceContainerHigh = Color(0xFFE8E6DC),
    surfaceContainerHighest = Color(0xFFDEDCD1),
    outline = Color(0xFFA8A69B),
    outlineVariant = Color(0xFFE8E6DC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD97757),
    onPrimary = Color(0xFF2B1509),
    primaryContainer = Color(0xFF5A2E1E),
    onPrimaryContainer = Color(0xFFF7D9CB),
    secondary = Color(0xFFC2C0B6),
    onSecondary = Color(0xFF1F1E1D),
    secondaryContainer = Color(0xFF3A3A37),
    onSecondaryContainer = Color(0xFFE8E6DC),
    tertiary = Color(0xFF6FCF97),
    onTertiary = Color(0xFF0E2A1A),
    tertiaryContainer = Color(0xFF23402D),
    onTertiaryContainer = Color(0xFFCDEBD7),
    error = Color(0xFFE8837A),
    onError = Color(0xFF3B0D09),
    errorContainer = Color(0xFF4A1F1B),
    onErrorContainer = Color(0xFFF6CFCB),
    background = Color(0xFF262624),
    onBackground = Color(0xFFFAF9F5),
    surface = Color(0xFF30302E),
    onSurface = Color(0xFFFAF9F5),
    surfaceVariant = Color(0xFF3A3A37),
    onSurfaceVariant = Color(0xFFB8B6AC),
    surfaceContainerLowest = Color(0xFF1F1E1D),
    surfaceContainerLow = Color(0xFF2B2B29),
    surfaceContainer = Color(0xFF333331),
    surfaceContainerHigh = Color(0xFF3C3C39),
    surfaceContainerHighest = Color(0xFF454542),
    outline = Color(0xFF7A7972),
    outlineVariant = Color(0xFF3E3D3A),
)

private val HermesTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 23.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 20.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.5.sp,
        lineHeight = 21.5.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
    ),
)

private val HermesShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 크림 바탕 + 클레이 강조의 따뜻한 종이 느낌 테마를 기본 적용한다(제목은 세리프).
 * 필요 시 [dynamicColor]=true 로 Android 12+ 동적 색상을 선택할 수 있다.
 */
@Composable
fun HermesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val hermesColors = if (darkTheme) DarkHermesColors else LightHermesColors
    CompositionLocalProvider(LocalHermesColors provides hermesColors) {
        MaterialTheme(
            colorScheme = colors,
            typography = HermesTypography,
            shapes = HermesShapes,
            content = content,
        )
    }
}
