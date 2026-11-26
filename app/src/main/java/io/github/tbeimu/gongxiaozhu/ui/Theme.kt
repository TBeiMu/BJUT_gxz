package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import android.app.Activity
import android.content.ContextWrapper

/**
 * 墨色、背景与课程色保持中性；用户可选强调色只映射到今日、此刻和其他现有标记。
 * 品牌朱红及操作色固定，避免外观选择改变 Logo、按钮语义或课程颜色。
 */
@Immutable
internal data class GxzPalette(
    val dark: Boolean,
    val ink: Color,
    val onInk: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val hairline: Color,
    val glass: Color,
    val glassStrong: Color,
    val glassEdge: Color,
    val well: Color,
    val accent: Color,
    val onAccent: Color,
    val brandAccent: Color,
    val positive: Color,
)

private val LightPalette = GxzPalette(
    dark = false,
    // Neutral ink on paper; only card surfaces receive the chosen opacity.
    ink = Color(0xFF1B1D22), onInk = Color.White,
    inkSoft = Color(0xFF585D67), inkFaint = Color(0xFF8D919A),
    hairline = Color(0xFF1B1D22).copy(alpha = .07f),
    glass = Color.White.copy(alpha = .96f), glassStrong = Color.White,
    glassEdge = Color(0xFF1B1D22).copy(alpha = .05f), well = Color(0xFF1B1D22).copy(alpha = .045f),
    accent = Color(0xFFE5482E), onAccent = Color.White, brandAccent = Color(0xFFE5482E), positive = Color(0xFF1C9A68),
)

private val DarkPalette = GxzPalette(
    dark = true,
    ink = Color(0xFFECEDEF), onInk = Color(0xFF1B1D22),
    inkSoft = Color(0xFFA4A8B0), inkFaint = Color(0xFF747882),
    hairline = Color.White.copy(alpha = .08f),
    glass = Color(0xFF1C1E23).copy(alpha = .96f), glassStrong = Color(0xFF23262B),
    glassEdge = Color.White.copy(alpha = .06f), well = Color.White.copy(alpha = .07f),
    accent = Color(0xFFFF6E52), onAccent = Color(0xFF2B0700), brandAccent = Color(0xFFFF6E52), positive = Color(0xFF52CC9E),
)

internal val LocalGxzPalette = staticCompositionLocalOf { LightPalette }

internal val gxz: GxzPalette
    @Composable get() = LocalGxzPalette.current

// Every Material slot is set: unspecified ones fall back to the baseline purple.
private val Light = lightColorScheme(
    primary = LightPalette.ink, onPrimary = Color.White,
    primaryContainer = Color(0xFFE9EAEC), onPrimaryContainer = LightPalette.ink,
    inversePrimary = Color(0xFFC5C8CE),
    secondary = LightPalette.inkSoft, onSecondary = Color.White,
    secondaryContainer = Color(0xFFECEDEF), onSecondaryContainer = LightPalette.ink,
    tertiary = LightPalette.accent, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE3DC), onTertiaryContainer = Color(0xFF5A1508),
    background = Color(0xFFF3F4F6), onBackground = LightPalette.ink,
    surface = Color(0xFFF8F8F9), onSurface = LightPalette.ink,
    surfaceVariant = Color(0xFFEBECEE), onSurfaceVariant = LightPalette.inkSoft,
    surfaceTint = LightPalette.ink,
    inverseSurface = Color(0xFF23252A), inverseOnSurface = Color(0xFFECEDEF),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFFCE4E1), onErrorContainer = Color(0xFF5C120D),
    outline = Color(0xFFA0A4AB), outlineVariant = Color(0xFFE0E2E5),
    scrim = Color(0xFF08090B),
    surfaceBright = Color.White, surfaceDim = Color(0xFFE3E4E7),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAFB),
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFEDEEF0),
)

private val Dark = darkColorScheme(
    primary = DarkPalette.ink, onPrimary = Color(0xFF1B1D22),
    primaryContainer = Color(0xFF2E3137), onPrimaryContainer = Color(0xFFE6E7EA),
    inversePrimary = Color(0xFF4A4E56),
    secondary = DarkPalette.inkSoft, onSecondary = Color(0xFF1E2025),
    secondaryContainer = Color(0xFF2A2D33), onSecondaryContainer = Color(0xFFE2E3E6),
    tertiary = DarkPalette.accent, onTertiary = Color(0xFF3A0B02),
    tertiaryContainer = Color(0xFF5A1E12), onTertiaryContainer = Color(0xFFFFDAD2),
    background = Color(0xFF0F1115), onBackground = DarkPalette.ink,
    surface = Color(0xFF16181C), onSurface = DarkPalette.ink,
    surfaceVariant = Color(0xFF25282D), onSurfaceVariant = DarkPalette.inkSoft,
    surfaceTint = DarkPalette.ink,
    inverseSurface = Color(0xFFECEDEF), inverseOnSurface = Color(0xFF1B1D22),
    error = Color(0xFFFFB4A9), onError = Color(0xFF5F150D),
    errorContainer = Color(0xFF7A2118), onErrorContainer = Color(0xFFFFDAD4),
    outline = Color(0xFF6E727A), outlineVariant = Color(0xFF31343A),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2C2F35), surfaceDim = Color(0xFF0F1115),
    surfaceContainerLowest = Color(0xFF0C0D10), surfaceContainerLow = Color(0xFF181A1E),
    surfaceContainer = Color(0xFF1C1E23), surfaceContainerHigh = Color(0xFF202227),
    surfaceContainerHighest = Color(0xFF282B31),
)

private fun type(size: Float, line: Float, weight: FontWeight, tracking: Float = 0f) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)

private val Type = Typography(
    displayLarge = type(44f, 50f, FontWeight.Bold, -1f),
    displayMedium = type(38f, 44f, FontWeight.Bold, -.8f),
    displaySmall = type(32f, 38f, FontWeight.Bold, -.6f),
    headlineLarge = type(30f, 36f, FontWeight.Bold, -.5f),
    headlineMedium = type(26f, 32f, FontWeight.Bold, -.3f),
    headlineSmall = type(22f, 29f, FontWeight.SemiBold, -.2f),
    titleLarge = type(20f, 26f, FontWeight.SemiBold, -.1f),
    titleMedium = type(16f, 22f, FontWeight.SemiBold),
    titleSmall = type(14f, 20f, FontWeight.SemiBold),
    bodyLarge = type(16f, 24f, FontWeight.Normal),
    bodyMedium = type(14f, 21f, FontWeight.Normal),
    bodySmall = type(12.5f, 18f, FontWeight.Normal),
    labelLarge = type(14f, 20f, FontWeight.SemiBold),
    labelMedium = type(12f, 16f, FontWeight.Medium, .2f),
    labelSmall = type(11f, 14f, FontWeight.Medium, .3f),
)

private val Shape = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun GongXiaoZhuTheme(content: @Composable () -> Unit) = GongXiaoZhuTheme(WallpaperPreferences(), content)

@Composable
internal fun GongXiaoZhuTheme(appearance: WallpaperPreferences, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val palette = appearancePalette(appearance, systemDark)
    val dark = palette.dark
    val view = LocalView.current
    SideEffect {
        var context = view.context
        var activity: Activity? = null
        while (context is ContextWrapper) {
            if (context is Activity) { activity = context; break }
            context = context.baseContext
        }
        if (context is Activity) activity = context
        val window = activity?.window
        if (window != null) {
            WindowInsetsControllerCompat(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalGxzPalette provides palette,
        LocalReducedMotion provides rememberReducedMotion()) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, shapes = Shape, content = content)
    }
}

/** The same palette for the app and its miniature previews; no window or save side effects. */
internal fun appearancePalette(appearance: WallpaperPreferences, systemDark: Boolean): GxzPalette {
    val dark = appearance.themeMode.isDark(systemDark)
    val base = if (dark) DarkPalette else LightPalette
    val (accent, onAccent) = accentColors(appearance.accentPreset, dark)
    // Preserve the existing 2–50% appearance; reinforce ink further only beyond 50%.
    val clearerInk = ((84 - appearance.cardOpacityPercent) / 34f).coerceIn(0f, 1f)
    val secondaryStrength = clearerInk * .3f + extraCardTransparency(appearance.cardOpacityPercent) * .5f
    return base.copy(accent = accent, onAccent = onAccent,
        inkSoft = lerp(base.inkSoft, base.ink, secondaryStrength),
        glass = base.glass.copy(alpha = appearance.cardOpacityPercent / 100f))
}

private fun extraCardTransparency(cardOpacityPercent: Int): Float =
    ((50 - cardOpacityPercent) / 50f).coerceIn(0f, 1f)

/** Shared by the real wallpaper and previews, including cards with no background fill. */
internal fun wallpaperPhotoVeil(cardOpacityPercent: Int, dark: Boolean): Color {
    val extra = extraCardTransparency(cardOpacityPercent)
    return if (dark) Color(0xFF0F1115).copy(alpha = .62f + extra * .04f)
        else Color(0xFFF5F5F5).copy(alpha = .5f + extra * .1f)
}

internal fun accentColors(preset: AccentPreset, dark: Boolean): Pair<Color, Color> = when (preset) {
    AccentPreset.VERMILION -> if (dark) Color(0xFFFF6E52) to Color(0xFF2B0700) else Color(0xFFE5482E) to Color.White
    AccentPreset.BLUE -> if (dark) Color(0xFF83B8FF) to Color(0xFF09213D) else Color(0xFF276BC0) to Color.White
    AccentPreset.GREEN -> if (dark) Color(0xFF72D0AB) to Color(0xFF0B2B20) else Color(0xFF207B58) to Color.White
    AccentPreset.VIOLET -> if (dark) Color(0xFFB8A2FF) to Color(0xFF211342) else Color(0xFF7050B5) to Color.White
}
