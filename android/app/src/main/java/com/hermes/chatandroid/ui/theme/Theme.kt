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

private val LightHermesColors = HermesColors(
    isDark = false,
    online = Color(0xFF16A34A),
    offline = Color(0xFF94A3B8),
    warning = Color(0xFFD97706),
    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFF78350F),
    codeSurface = Color(0xFFF3F5FA),
    codeHeader = Color(0xFFE7ECF6),
    userBubbleStart = Color(0xFF5647E5),
    userBubbleEnd = Color(0xFF715DF9),
    onUserBubble = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE5E9F3),
    subtleDivider = Color(0xFFEDF0F7),
)

private val DarkHermesColors = HermesColors(
    isDark = true,
    online = Color(0xFF34D399),
    offline = Color(0xFF64748B),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF3A2C10),
    onWarningContainer = Color(0xFFFDE68A),
    codeSurface = Color(0xFF0C1018),
    codeHeader = Color(0xFF161D2C),
    userBubbleStart = Color(0xFF4B3DC9),
    userBubbleEnd = Color(0xFF6756EB),
    onUserBubble = Color(0xFFF8FAFF),
    cardBorder = Color(0xFF232C3F),
    subtleDivider = Color(0xFF1B2333),
)

private val LocalHermesColors = staticCompositionLocalOf { LightHermesColors }

val MaterialTheme.hermesColors: HermesColors
    @Composable
    @ReadOnlyComposable
    get() = LocalHermesColors.current

private val LightColors = lightColorScheme(
    primary = Color(0xFF5647E5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9E7FF),
    onPrimaryContainer = Color(0xFF1A1166),
    secondary = Color(0xFF475569),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8ECF5),
    onSecondaryContainer = Color(0xFF1E293B),
    tertiary = Color(0xFF0E9F6E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF064E3B),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEEF1F8),
    onSurfaceVariant = Color(0xFF526075),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F9FD),
    surfaceContainer = Color(0xFFF0F2F9),
    surfaceContainerHigh = Color(0xFFE7EBF5),
    surfaceContainerHighest = Color(0xFFDDE3F0),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFE2E6F0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8C82FF),
    onPrimary = Color(0xFF130B4E),
    primaryContainer = Color(0xFF2A2468),
    onPrimaryContainer = Color(0xFFE8E6FF),
    secondary = Color(0xFF94A3B8),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF1E2738),
    onSecondaryContainer = Color(0xFFE2E8F0),
    tertiary = Color(0xFF34D399),
    onTertiary = Color(0xFF022C22),
    tertiaryContainer = Color(0xFF064E3B),
    onTertiaryContainer = Color(0xFFA7F3D0),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF451A1E),
    onErrorContainer = Color(0xFFFECACA),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF111622),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1A2233),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerLowest = Color(0xFF080B10),
    surfaceContainerLow = Color(0xFF141A28),
    surfaceContainer = Color(0xFF192030),
    surfaceContainerHigh = Color(0xFF20293D),
    surfaceContainerHighest = Color(0xFF28334A),
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF232C3F),
)

private val HermesTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 23.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
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
 * 일관된 Hermes Obsidian & Alabaster 디자인 시스템을 기본 적용한다.
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
