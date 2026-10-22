package com.metrolist.music.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.metrolist.music.R

/**
 * Whole-app themes: a fixed palette (light and dark), its own type, shapes and decorations drawn by
 * [ThemeTopBarBackground] / [ThemeContentOverlay] / [ThemeTitle]. [STANDARD] is the Material You
 * look picked with the colour palette. [LINUX] goes furthest: monospace everywhere, square corners,
 * a tiling-window frame, a tmux status bar and a fetch block on the home page.
 */
enum class AppThemePreset(val nameRes: Int, val descriptionRes: Int) {
    STANDARD(R.string.app_theme_standard, R.string.app_theme_standard_desc),
    GRUVBOX(R.string.app_theme_gruvbox, R.string.app_theme_gruvbox_desc),
    LINUX(R.string.app_theme_linux, R.string.app_theme_linux_desc),
    SAKURA(R.string.app_theme_sakura, R.string.app_theme_sakura_desc),
    SYNTHWAVE(R.string.app_theme_synthwave, R.string.app_theme_synthwave_desc),
    CATPPUCCIN(R.string.app_theme_catppuccin, R.string.app_theme_catppuccin_desc),
    ;

    fun colorScheme(dark: Boolean): ColorScheme? = when (this) {
        STANDARD -> null
        GRUVBOX -> if (dark) GruvboxDark else GruvboxLight
        LINUX -> if (dark) LinuxDark else LinuxLight
        SAKURA -> if (dark) SakuraDark else SakuraLight
        SYNTHWAVE -> if (dark) SynthwaveDark else SynthwaveLight
        CATPPUCCIN -> if (dark) CatppuccinMocha else CatppuccinLatte
    }

    fun typography(): Typography = when (this) {
        // Gruvbox is a terminal/editor scheme: headings and labels in a monospace face.
        GRUVBOX -> AppTypography.withFamily(FontFamily.Monospace)
        // Linux: everything is a terminal.
        LINUX -> AppTypography.withFamily(FontFamily.Monospace, body = true)
        else -> AppTypography
    }

    /** Square corners for the window-manager look; null keeps Material's rounded shapes. */
    fun shapes(): Shapes? = when (this) {
        LINUX -> Shapes(
            extraSmall = RoundedCornerShape(0.dp),
            small = RoundedCornerShape(2.dp),
            medium = RoundedCornerShape(2.dp),
            large = RoundedCornerShape(3.dp),
            extraLarge = RoundedCornerShape(4.dp),
        )
        else -> null
    }
}

/** The theme in use, for the decorations and anything else that draws itself per theme. */
val LocalAppThemePreset = staticCompositionLocalOf { AppThemePreset.STANDARD }

/** Whether a theme's decorations may move (falling petals, drifting code, the blinking cursor). */
val LocalThemeAnimations = staticCompositionLocalOf { true }

private fun Typography.withFamily(family: FontFamily, body: Boolean = false) = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
    bodyLarge = if (body) bodyLarge.copy(fontFamily = family) else bodyLarge,
    bodyMedium = if (body) bodyMedium.copy(fontFamily = family) else bodyMedium,
    bodySmall = if (body) bodySmall.copy(fontFamily = family) else bodySmall,
)

// ── Gruvbox (morhetz/gruvbox: dark = bg0 #282828 / fg #ebdbb2, light = bg0 #fbf1c7 / fg #3c3836) ──

object Gruvbox {
    val red = Color(0xFFFB4934)
    val green = Color(0xFFB8BB26)
    val yellow = Color(0xFFFABD2F)
    val blue = Color(0xFF83A598)
    val purple = Color(0xFFD3869B)
    val aqua = Color(0xFF8EC07C)
    val orange = Color(0xFFFE8019)

    /** The retro stripe across the top bar. */
    val stripe = listOf(red, orange, yellow, green, aqua, blue, purple)
}

private val GruvboxDark = darkColorScheme(
    primary = Color(0xFFFABD2F),
    onPrimary = Color(0xFF282828),
    primaryContainer = Color(0xFF504945),
    onPrimaryContainer = Color(0xFFFABD2F),
    inversePrimary = Color(0xFFB57614),
    secondary = Color(0xFF8EC07C),
    onSecondary = Color(0xFF282828),
    secondaryContainer = Color(0xFF3C3836),
    onSecondaryContainer = Color(0xFF8EC07C),
    tertiary = Color(0xFFFE8019),
    onTertiary = Color(0xFF282828),
    tertiaryContainer = Color(0xFF504945),
    onTertiaryContainer = Color(0xFFFE8019),
    error = Color(0xFFFB4934),
    onError = Color(0xFF282828),
    errorContainer = Color(0xFF5C2620),
    onErrorContainer = Color(0xFFFBC4BC),
    background = Color(0xFF282828),
    onBackground = Color(0xFFEBDBB2),
    surface = Color(0xFF282828),
    onSurface = Color(0xFFEBDBB2),
    surfaceVariant = Color(0xFF3C3836),
    onSurfaceVariant = Color(0xFFBDAE93),
    surfaceTint = Color(0xFFFABD2F),
    inverseSurface = Color(0xFFEBDBB2),
    inverseOnSurface = Color(0xFF282828),
    outline = Color(0xFF7C6F64),
    outlineVariant = Color(0xFF504945),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF504945),
    surfaceDim = Color(0xFF1D2021),
    surfaceContainerLowest = Color(0xFF1D2021),
    surfaceContainerLow = Color(0xFF242424),
    surfaceContainer = Color(0xFF32302F),
    surfaceContainerHigh = Color(0xFF3C3836),
    surfaceContainerHighest = Color(0xFF504945),
)

private val GruvboxLight = lightColorScheme(
    primary = Color(0xFFB57614),
    onPrimary = Color(0xFFFBF1C7),
    primaryContainer = Color(0xFFEBDBB2),
    onPrimaryContainer = Color(0xFF8A5A0E),
    inversePrimary = Color(0xFFFABD2F),
    secondary = Color(0xFF427B58),
    onSecondary = Color(0xFFFBF1C7),
    secondaryContainer = Color(0xFFE4DDB0),
    onSecondaryContainer = Color(0xFF2E5A3F),
    tertiary = Color(0xFFAF3A03),
    onTertiary = Color(0xFFFBF1C7),
    tertiaryContainer = Color(0xFFF2D6B8),
    onTertiaryContainer = Color(0xFF7A2902),
    error = Color(0xFF9D0006),
    onError = Color(0xFFFBF1C7),
    errorContainer = Color(0xFFF4C7BE),
    onErrorContainer = Color(0xFF5E0004),
    background = Color(0xFFFBF1C7),
    onBackground = Color(0xFF3C3836),
    surface = Color(0xFFFBF1C7),
    onSurface = Color(0xFF3C3836),
    surfaceVariant = Color(0xFFEBDBB2),
    onSurfaceVariant = Color(0xFF665C54),
    surfaceTint = Color(0xFFB57614),
    inverseSurface = Color(0xFF3C3836),
    inverseOnSurface = Color(0xFFFBF1C7),
    outline = Color(0xFF928374),
    outlineVariant = Color(0xFFD5C4A1),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8DB),
    surfaceDim = Color(0xFFE6D8AE),
    surfaceContainerLowest = Color(0xFFFFF8DB),
    surfaceContainerLow = Color(0xFFF9EEC2),
    surfaceContainer = Color(0xFFF2E5BC),
    surfaceContainerHigh = Color(0xFFEBDBB2),
    surfaceContainerHighest = Color(0xFFE3D2A6),
)

// ── Sakura: night = plum dusk with blossom pink, wisteria and torii vermilion; day = petal white ──

object Sakura {
    val petal = Color(0xFFF7B6C8)
    val petalDeep = Color(0xFFE88AA6)
    val petalLight = Color(0xFFFFE1EA)
    val branch = Color(0xFF5B3A3F)
    val vermilion = Color(0xFFD9483B)
}

private val SakuraDark = darkColorScheme(
    primary = Color(0xFFF4A7BB),
    onPrimary = Color(0xFF4A1A2C),
    primaryContainer = Color(0xFF6B2F45),
    onPrimaryContainer = Color(0xFFFFD9E2),
    inversePrimary = Color(0xFFB83E66),
    secondary = Color(0xFFCDB8EC),
    onSecondary = Color(0xFF35235A),
    secondaryContainer = Color(0xFF433259),
    onSecondaryContainer = Color(0xFFEADDFF),
    tertiary = Color(0xFFFF9C8A),
    onTertiary = Color(0xFF5C150B),
    tertiaryContainer = Color(0xFF7A2A1E),
    onTertiaryContainer = Color(0xFFFFDAD3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1C171F),
    onBackground = Color(0xFFF2E4EA),
    surface = Color(0xFF1C171F),
    onSurface = Color(0xFFF2E4EA),
    surfaceVariant = Color(0xFF3A2E38),
    onSurfaceVariant = Color(0xFFD6C1CB),
    surfaceTint = Color(0xFFF4A7BB),
    inverseSurface = Color(0xFFF2E4EA),
    inverseOnSurface = Color(0xFF2E2530),
    outline = Color(0xFF9E8794),
    outlineVariant = Color(0xFF4F4049),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF433844),
    surfaceDim = Color(0xFF1C171F),
    surfaceContainerLowest = Color(0xFF151118),
    surfaceContainerLow = Color(0xFF231C26),
    surfaceContainer = Color(0xFF28202B),
    surfaceContainerHigh = Color(0xFF332A36),
    surfaceContainerHighest = Color(0xFF3E3441),
)

private val SakuraLight = lightColorScheme(
    primary = Color(0xFFB83E66),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E2),
    onPrimaryContainer = Color(0xFF3E0019),
    inversePrimary = Color(0xFFF4A7BB),
    secondary = Color(0xFF6E5596),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDDDFF),
    onSecondaryContainer = Color(0xFF28114E),
    tertiary = Color(0xFFBD3B2C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD3),
    onTertiaryContainer = Color(0xFF410000),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF7F9),
    onBackground = Color(0xFF2B1D23),
    surface = Color(0xFFFFF7F9),
    onSurface = Color(0xFF2B1D23),
    surfaceVariant = Color(0xFFF6DFE6),
    onSurfaceVariant = Color(0xFF5A4249),
    surfaceTint = Color(0xFFB83E66),
    inverseSurface = Color(0xFF412E35),
    inverseOnSurface = Color(0xFFFFECF0),
    outline = Color(0xFF8D7178),
    outlineVariant = Color(0xFFE6C9D1),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF7F9),
    surfaceDim = Color(0xFFF0D9DF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0F3),
    surfaceContainer = Color(0xFFFCE9EE),
    surfaceContainerHigh = Color(0xFFF8E2E8),
    surfaceContainerHighest = Color(0xFFF2DAE1),
)

// ── Linux: gruvbox "hard" (bg0_h #1d2021), aqua prompt + yellow focus — a riced tiling desktop ──

private val LinuxDark = darkColorScheme(
    primary = Color(0xFFFABD2F),
    onPrimary = Color(0xFF1D2021),
    primaryContainer = Color(0xFF3C3836),
    onPrimaryContainer = Color(0xFFFABD2F),
    inversePrimary = Color(0xFFB57614),
    secondary = Color(0xFF8EC07C),
    onSecondary = Color(0xFF1D2021),
    secondaryContainer = Color(0xFF32302F),
    onSecondaryContainer = Color(0xFF8EC07C),
    tertiary = Color(0xFF83A598),
    onTertiary = Color(0xFF1D2021),
    tertiaryContainer = Color(0xFF3C3836),
    onTertiaryContainer = Color(0xFF83A598),
    error = Color(0xFFFB4934),
    onError = Color(0xFF1D2021),
    errorContainer = Color(0xFF4A1F1A),
    onErrorContainer = Color(0xFFFBC4BC),
    background = Color(0xFF1D2021),
    onBackground = Color(0xFFEBDBB2),
    surface = Color(0xFF1D2021),
    onSurface = Color(0xFFEBDBB2),
    surfaceVariant = Color(0xFF32302F),
    onSurfaceVariant = Color(0xFFA89984),
    surfaceTint = Color(0xFF1D2021),
    inverseSurface = Color(0xFFEBDBB2),
    inverseOnSurface = Color(0xFF1D2021),
    outline = Color(0xFF665C54),
    outlineVariant = Color(0xFF3C3836),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3C3836),
    surfaceDim = Color(0xFF141617),
    surfaceContainerLowest = Color(0xFF141617),
    surfaceContainerLow = Color(0xFF1D2021),
    surfaceContainer = Color(0xFF282828),
    surfaceContainerHigh = Color(0xFF32302F),
    surfaceContainerHighest = Color(0xFF3C3836),
)

private val LinuxLight = lightColorScheme(
    primary = Color(0xFF9D6A0B),
    onPrimary = Color(0xFFF9F5D7),
    primaryContainer = Color(0xFFEBDBB2),
    onPrimaryContainer = Color(0xFF7A520A),
    inversePrimary = Color(0xFFFABD2F),
    secondary = Color(0xFF427B58),
    onSecondary = Color(0xFFF9F5D7),
    secondaryContainer = Color(0xFFE6DEB5),
    onSecondaryContainer = Color(0xFF2E5A3F),
    tertiary = Color(0xFF076678),
    onTertiary = Color(0xFFF9F5D7),
    tertiaryContainer = Color(0xFFD8E2CF),
    onTertiaryContainer = Color(0xFF054C59),
    error = Color(0xFF9D0006),
    onError = Color(0xFFF9F5D7),
    errorContainer = Color(0xFFF4C7BE),
    onErrorContainer = Color(0xFF5E0004),
    background = Color(0xFFF9F5D7),
    onBackground = Color(0xFF282828),
    surface = Color(0xFFF9F5D7),
    onSurface = Color(0xFF282828),
    surfaceVariant = Color(0xFFEBDBB2),
    onSurfaceVariant = Color(0xFF665C54),
    surfaceTint = Color(0xFFF9F5D7),
    inverseSurface = Color(0xFF282828),
    inverseOnSurface = Color(0xFFF9F5D7),
    outline = Color(0xFFA89984),
    outlineVariant = Color(0xFFD5C4A1),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFBE6),
    surfaceDim = Color(0xFFE5DCB8),
    surfaceContainerLowest = Color(0xFFFFFBE6),
    surfaceContainerLow = Color(0xFFF9F5D7),
    surfaceContainer = Color(0xFFF2E5BC),
    surfaceContainerHigh = Color(0xFFEBDBB2),
    surfaceContainerHighest = Color(0xFFE3D2A6),
)

// ── Synthwave: Synthwave '84 neon — hot pink, cyan and sunset yellow over a violet night ──

object Synthwave {
    val pink = Color(0xFFFF2E97)
    val cyan = Color(0xFF36F9F6)
    val yellow = Color(0xFFFEDE5D)
    val orange = Color(0xFFFF8B39)
    val violet = Color(0xFF7B2CBF)
}

private val SynthwaveDark = darkColorScheme(
    primary = Color(0xFFFF4FA8),
    onPrimary = Color(0xFF3D0024),
    primaryContainer = Color(0xFF5E1A4A),
    onPrimaryContainer = Color(0xFFFFD8EA),
    inversePrimary = Color(0xFFC2186F),
    secondary = Color(0xFF36F9F6),
    onSecondary = Color(0xFF003736),
    secondaryContainer = Color(0xFF16405A),
    onSecondaryContainer = Color(0xFFB8FFFD),
    tertiary = Color(0xFFFEDE5D),
    onTertiary = Color(0xFF3A2E00),
    tertiaryContainer = Color(0xFF5A3A1E),
    onTertiaryContainer = Color(0xFFFFE9A8),
    error = Color(0xFFFF6E6E),
    onError = Color(0xFF4A0000),
    errorContainer = Color(0xFF7A1020),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A1033),
    onBackground = Color(0xFFF3E8FF),
    surface = Color(0xFF1A1033),
    onSurface = Color(0xFFF3E8FF),
    surfaceVariant = Color(0xFF34265A),
    onSurfaceVariant = Color(0xFFCBB8EE),
    surfaceTint = Color(0xFFFF4FA8),
    inverseSurface = Color(0xFFF3E8FF),
    inverseOnSurface = Color(0xFF2A1D47),
    outline = Color(0xFF8A74B8),
    outlineVariant = Color(0xFF3F2F66),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A2B60),
    surfaceDim = Color(0xFF140C28),
    surfaceContainerLowest = Color(0xFF110A22),
    surfaceContainerLow = Color(0xFF1F1440),
    surfaceContainer = Color(0xFF241848),
    surfaceContainerHigh = Color(0xFF2C1F55),
    surfaceContainerHighest = Color(0xFF352862),
)

private val SynthwaveLight = lightColorScheme(
    primary = Color(0xFFC2186F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD8EA),
    onPrimaryContainer = Color(0xFF3D0024),
    inversePrimary = Color(0xFFFF4FA8),
    secondary = Color(0xFF00807E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB8F4F2),
    onSecondaryContainer = Color(0xFF002020),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE9A8),
    onTertiaryContainer = Color(0xFF2B1D00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF5FF),
    onBackground = Color(0xFF221A33),
    surface = Color(0xFFFBF5FF),
    onSurface = Color(0xFF221A33),
    surfaceVariant = Color(0xFFEADFF7),
    onSurfaceVariant = Color(0xFF4E4263),
    surfaceTint = Color(0xFFC2186F),
    inverseSurface = Color(0xFF372D4A),
    inverseOnSurface = Color(0xFFF7EEFF),
    outline = Color(0xFF7F7294),
    outlineVariant = Color(0xFFD3C5E6),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF5FF),
    surfaceDim = Color(0xFFE2D6F0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7EEFF),
    surfaceContainer = Color(0xFFF2E7FC),
    surfaceContainerHigh = Color(0xFFECE0F8),
    surfaceContainerHighest = Color(0xFFE6D9F4),
)

// ── Catppuccin (catppuccin.com): Mocha at night, Latte by day — soft pastels, mauve accent ──

object Catppuccin {
    val rosewater = Color(0xFFF5E0DC)
    val mauve = Color(0xFFCBA6F7)
    val pink = Color(0xFFF5C2E7)
    val blue = Color(0xFF89B4FA)
    val teal = Color(0xFF94E2D5)
    val peach = Color(0xFFFAB387)

    /** The pastel underline of the top bar. */
    val ribbon = listOf(rosewater, pink, mauve, blue, teal)
}

private val CatppuccinMocha = darkColorScheme(
    primary = Color(0xFFCBA6F7),
    onPrimary = Color(0xFF1E1E2E),
    primaryContainer = Color(0xFF45385F),
    onPrimaryContainer = Color(0xFFEBDCFF),
    inversePrimary = Color(0xFF8839EF),
    secondary = Color(0xFF89B4FA),
    onSecondary = Color(0xFF11111B),
    secondaryContainer = Color(0xFF2E3B57),
    onSecondaryContainer = Color(0xFFD6E4FF),
    tertiary = Color(0xFFF5C2E7),
    onTertiary = Color(0xFF1E1E2E),
    tertiaryContainer = Color(0xFF55405A),
    onTertiaryContainer = Color(0xFFFBE3F3),
    error = Color(0xFFF38BA8),
    onError = Color(0xFF1E1E2E),
    errorContainer = Color(0xFF5A2B3A),
    onErrorContainer = Color(0xFFFFD9E1),
    background = Color(0xFF1E1E2E),
    onBackground = Color(0xFFCDD6F4),
    surface = Color(0xFF1E1E2E),
    onSurface = Color(0xFFCDD6F4),
    surfaceVariant = Color(0xFF313244),
    onSurfaceVariant = Color(0xFFBAC2DE),
    surfaceTint = Color(0xFFCBA6F7),
    inverseSurface = Color(0xFFCDD6F4),
    inverseOnSurface = Color(0xFF1E1E2E),
    outline = Color(0xFF6C7086),
    outlineVariant = Color(0xFF45475A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF45475A),
    surfaceDim = Color(0xFF11111B),
    surfaceContainerLowest = Color(0xFF11111B),
    surfaceContainerLow = Color(0xFF181825),
    surfaceContainer = Color(0xFF242436),
    surfaceContainerHigh = Color(0xFF2B2B3F),
    surfaceContainerHighest = Color(0xFF313244),
)

private val CatppuccinLatte = lightColorScheme(
    primary = Color(0xFF8839EF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEBDCFF),
    onPrimaryContainer = Color(0xFF2E0A5E),
    inversePrimary = Color(0xFFCBA6F7),
    secondary = Color(0xFF1E66F5),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E3FD),
    onSecondaryContainer = Color(0xFF0A2A66),
    tertiary = Color(0xFFEA76CB),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBE0F3),
    onTertiaryContainer = Color(0xFF5A1048),
    error = Color(0xFFD20F39),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD9DF),
    onErrorContainer = Color(0xFF410010),
    background = Color(0xFFEFF1F5),
    onBackground = Color(0xFF4C4F69),
    surface = Color(0xFFEFF1F5),
    onSurface = Color(0xFF4C4F69),
    surfaceVariant = Color(0xFFDCE0E8),
    onSurfaceVariant = Color(0xFF5C5F77),
    surfaceTint = Color(0xFF8839EF),
    inverseSurface = Color(0xFF4C4F69),
    inverseOnSurface = Color(0xFFEFF1F5),
    outline = Color(0xFF9CA0B0),
    outlineVariant = Color(0xFFCCD0DA),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7F8FB),
    surfaceDim = Color(0xFFDCE0E8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F5F8),
    surfaceContainer = Color(0xFFE6E9EF),
    surfaceContainerHigh = Color(0xFFE0E3EA),
    surfaceContainerHighest = Color(0xFFDCE0E8),
)
