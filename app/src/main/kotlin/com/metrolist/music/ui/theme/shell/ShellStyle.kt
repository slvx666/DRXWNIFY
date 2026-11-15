/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme.shell

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.metrolist.music.ui.theme.AppTypography

/**
 * The palette of an experimental theme. Only Paper has its own (ink on paper, no accent colour);
 * the others keep the standard Material You one — a single accent on neutral surfaces, nothing
 * many-coloured.
 */
fun shellColorScheme(shell: ShellTheme?, dark: Boolean): ColorScheme? = when (shell) {
    ShellTheme.PAPER -> if (dark) PaperDark else PaperLight
    else -> null
}

/** Paper: headings in a serif, like a printed page; the rest stays as it is. */
fun shellTypography(shell: ShellTheme?): Typography? = when (shell) {
    ShellTheme.PAPER -> AppTypography.let { t ->
        val serif = FontFamily.Serif
        t.copy(
            displayLarge = t.displayLarge.copy(fontFamily = serif),
            displayMedium = t.displayMedium.copy(fontFamily = serif),
            displaySmall = t.displaySmall.copy(fontFamily = serif),
            headlineLarge = t.headlineLarge.copy(fontFamily = serif),
            headlineMedium = t.headlineMedium.copy(fontFamily = serif),
            headlineSmall = t.headlineSmall.copy(fontFamily = serif),
            titleLarge = t.titleLarge.copy(fontFamily = serif),
        )
    }
    else -> null
}

/** Corners: Monolith and Glass rounder (poured / liquid), Paper nearly square (cut paper). */
fun shellShapes(shell: ShellTheme?): Shapes? = when (shell) {
    ShellTheme.MONOLITH -> Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )
    ShellTheme.GLASS -> Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(36.dp),
    )
    ShellTheme.PAPER -> Shapes(
        extraSmall = RoundedCornerShape(2.dp),
        small = RoundedCornerShape(3.dp),
        medium = RoundedCornerShape(4.dp),
        large = RoundedCornerShape(6.dp),
        extraLarge = RoundedCornerShape(8.dp),
    )
    else -> null
}

private val PaperInk = Color(0xFF1C1B19)
private val PaperSheet = Color(0xFFF4F1EA)

private val PaperLight = lightColorScheme(
    primary = Color(0xFF2B2A27),
    onPrimary = PaperSheet,
    primaryContainer = Color(0xFFE2DED4),
    onPrimaryContainer = PaperInk,
    inversePrimary = Color(0xFFD9D5CB),
    secondary = Color(0xFF5A5853),
    onSecondary = PaperSheet,
    secondaryContainer = Color(0xFFE6E2D8),
    onSecondaryContainer = PaperInk,
    tertiary = Color(0xFF6B6860),
    onTertiary = PaperSheet,
    tertiaryContainer = Color(0xFFE9E5DB),
    onTertiaryContainer = PaperInk,
    background = PaperSheet,
    onBackground = PaperInk,
    surface = PaperSheet,
    onSurface = PaperInk,
    surfaceVariant = Color(0xFFE6E2D8),
    onSurfaceVariant = Color(0xFF4E4C47),
    surfaceTint = Color(0xFF2B2A27),
    inverseSurface = Color(0xFF2F2E2B),
    inverseOnSurface = PaperSheet,
    error = Color(0xFF8C2F28),
    onError = PaperSheet,
    errorContainer = Color(0xFFEBD3CF),
    onErrorContainer = Color(0xFF3B0E0A),
    outline = Color(0xFF8E8A80),
    outlineVariant = Color(0xFFCFCABF),
    scrim = Color.Black,
    surfaceBright = Color(0xFFF8F6F0),
    surfaceDim = Color(0xFFE3DFD6),
    surfaceContainerLowest = Color(0xFFFAF8F3),
    surfaceContainerLow = Color(0xFFF0EDE5),
    surfaceContainer = Color(0xFFECE8DF),
    surfaceContainerHigh = Color(0xFFE7E3D9),
    surfaceContainerHighest = Color(0xFFE1DDD3),
)

private val PaperDark = darkColorScheme(
    primary = Color(0xFFE8E4DA),
    onPrimary = Color(0xFF161615),
    primaryContainer = Color(0xFF34332F),
    onPrimaryContainer = Color(0xFFE8E4DA),
    inversePrimary = Color(0xFF2B2A27),
    secondary = Color(0xFFBDB9AF),
    onSecondary = Color(0xFF161615),
    secondaryContainer = Color(0xFF2E2D2A),
    onSecondaryContainer = Color(0xFFE8E4DA),
    tertiary = Color(0xFFA9A59B),
    onTertiary = Color(0xFF161615),
    tertiaryContainer = Color(0xFF2A2927),
    onTertiaryContainer = Color(0xFFE8E4DA),
    background = Color(0xFF161615),
    onBackground = Color(0xFFE8E4DA),
    surface = Color(0xFF161615),
    onSurface = Color(0xFFE8E4DA),
    surfaceVariant = Color(0xFF2E2D2A),
    onSurfaceVariant = Color(0xFFB8B4AA),
    surfaceTint = Color(0xFFE8E4DA),
    inverseSurface = Color(0xFFE8E4DA),
    inverseOnSurface = Color(0xFF161615),
    error = Color(0xFFE0A49C),
    onError = Color(0xFF3B0E0A),
    errorContainer = Color(0xFF5C1E18),
    onErrorContainer = Color(0xFFF2D6D1),
    outline = Color(0xFF7D7A72),
    outlineVariant = Color(0xFF3D3C38),
    scrim = Color.Black,
    surfaceBright = Color(0xFF34332F),
    surfaceDim = Color(0xFF161615),
    surfaceContainerLowest = Color(0xFF111110),
    surfaceContainerLow = Color(0xFF1B1B19),
    surfaceContainer = Color(0xFF1F1F1D),
    surfaceContainerHigh = Color(0xFF262624),
    surfaceContainerHighest = Color(0xFF2E2D2A),
)
