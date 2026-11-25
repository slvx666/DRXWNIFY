/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The near-colourless palettes: Monochrome and the experimental Satanic, Gothic, Street art and
 * Breakcore. Each is neutral surfaces in steps plus at most one quiet accent — nothing bright,
 * nothing many-coloured. Built from a few values ([Tone]) so the full Material scheme stays
 * consistent (containers step up evenly, text keeps its contrast).
 */
internal class Tone(
    /** The page. */
    val page: Color,
    /** Five container steps, lowest → highest. */
    val steps: List<Color>,
    val ink: Color,
    val inkSoft: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val outline: Color,
    val outlineSoft: Color,
    val error: Color,
)

internal fun Tone.scheme(dark: Boolean): ColorScheme {
    val (lowest, low, mid, high, highest) = steps
    return if (dark) {
        darkColorScheme(
            primary = accent, onPrimary = onAccent,
            primaryContainer = accentContainer, onPrimaryContainer = onAccentContainer,
            inversePrimary = onAccent,
            secondary = inkSoft, onSecondary = page,
            secondaryContainer = high, onSecondaryContainer = ink,
            tertiary = inkSoft, onTertiary = page,
            tertiaryContainer = highest, onTertiaryContainer = ink,
            error = error, onError = page,
            errorContainer = highest, onErrorContainer = error,
            background = page, onBackground = ink,
            surface = page, onSurface = ink,
            surfaceVariant = high, onSurfaceVariant = inkSoft,
            surfaceTint = accent,
            inverseSurface = ink, inverseOnSurface = page,
            outline = outline, outlineVariant = outlineSoft,
            scrim = Color.Black,
            surfaceBright = highest, surfaceDim = page,
            surfaceContainerLowest = lowest, surfaceContainerLow = low,
            surfaceContainer = mid, surfaceContainerHigh = high, surfaceContainerHighest = highest,
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = onAccent,
            primaryContainer = accentContainer, onPrimaryContainer = onAccentContainer,
            inversePrimary = onAccent,
            secondary = inkSoft, onSecondary = page,
            secondaryContainer = high, onSecondaryContainer = ink,
            tertiary = inkSoft, onTertiary = page,
            tertiaryContainer = highest, onTertiaryContainer = ink,
            error = error, onError = page,
            errorContainer = highest, onErrorContainer = error,
            background = page, onBackground = ink,
            surface = page, onSurface = ink,
            surfaceVariant = high, onSurfaceVariant = inkSoft,
            surfaceTint = accent,
            inverseSurface = ink, inverseOnSurface = page,
            outline = outline, outlineVariant = outlineSoft,
            scrim = Color.Black,
            surfaceBright = lowest, surfaceDim = highest,
            surfaceContainerLowest = lowest, surfaceContainerLow = low,
            surfaceContainer = mid, surfaceContainerHigh = high, surfaceContainerHighest = highest,
        )
    }
}

private fun c(v: Long) = Color(v)

// ── Monochrome: black and white as far as it goes. Day is not white but a soft grey paper. ──

internal val MonoNight = Tone(
    page = c(0xFF0A0A0A),
    steps = listOf(c(0xFF060606), c(0xFF121212), c(0xFF171717), c(0xFF1F1F1F), c(0xFF292929)),
    ink = c(0xFFF2F2F2), inkSoft = c(0xFFA8A8A8),
    accent = c(0xFFFFFFFF), onAccent = c(0xFF000000),
    accentContainer = c(0xFF2C2C2C), onAccentContainer = c(0xFFFFFFFF),
    outline = c(0xFF6B6B6B), outlineSoft = c(0xFF333333),
    error = c(0xFFE0E0E0),
)

internal val MonoDay = Tone(
    page = c(0xFFE3E1DC),
    steps = listOf(c(0xFFEAE8E4), c(0xFFDEDCD7), c(0xFFD8D6D1), c(0xFFD1CFCA), c(0xFFC9C7C2)),
    ink = c(0xFF111111), inkSoft = c(0xFF4A4947),
    accent = c(0xFF151515), onAccent = c(0xFFEDEBE6),
    accentContainer = c(0xFFC4C2BD), onAccentContainer = c(0xFF111111),
    outline = c(0xFF7A7875), outlineSoft = c(0xFFB8B6B1),
    error = c(0xFF2A2A2A),
)

// ── Satanic: black metal's black and white — pure black nights, ash-grey days, no red at all. ──

internal val SatanicNight = Tone(
    page = c(0xFF080808),
    steps = listOf(c(0xFF080808), c(0xFF111111), c(0xFF171717), c(0xFF222222), c(0xFF2C2C2C)),
    ink = c(0xFFE5E5E5), inkSoft = c(0xFFA0A0A0),
    accent = c(0xFFD0D0D0), onAccent = c(0xFF080808),
    accentContainer = c(0xFF171717), onAccentContainer = c(0xFFE5E5E5),
    outline = c(0xFF4A4A4A), outlineSoft = c(0xFF282828),
    error = c(0xFFE5E5E5),
)

// Day is an old yellowed satanic book: one paper for every page (the library background's), the
// bars, cards and buttons in the mini player's older, yellower paper, all in black ink.
internal val SatanicDay = Tone(
    page = c(0xFFD4CFC0),
    steps = listOf(c(0xFFD8D3C6), c(0xFFD1CBBC), c(0xFFCEC7B4), c(0xFFC6BFAC), c(0xFFBDB6A3)),
    ink = c(0xFF0A0A0A), inkSoft = c(0xFF3F3C36),
    accent = c(0xFF0A0A0A), onAccent = c(0xFFE3DFD3),
    accentContainer = c(0xFFB5AE9C), onAccentContainer = c(0xFF0A0A0A),
    outline = c(0xFF6E6A60), outlineSoft = c(0xFFABA595),
    error = c(0xFF1F1F1F),
)

// Dusk (the middle mode) is the same book, far older: the paper has gone brown and withered, as if
// it had lain in a damp crypt for a century and is falling apart. Still dark ink on paper — a light
// scheme — only much gloomier. Mirrored by the Dusk* colours in SatanicThemeConfig.
internal val SatanicDusk = Tone(
    page = c(0xFFA0957B),
    steps = listOf(c(0xFFA69B82), c(0xFF9C9077), c(0xFF968A71), c(0xFF8E826A), c(0xFF867A62)),
    ink = c(0xFF0B0907), inkSoft = c(0xFF2C271F),
    accent = c(0xFF0B0907), onAccent = c(0xFFB2A78D),
    accentContainer = c(0xFF847860), onAccentContainer = c(0xFF0B0907),
    outline = c(0xFF463F32), outlineSoft = c(0xFF7D725B),
    error = c(0xFF1C1611),
)

// ── Gothic: night stone with a hint of dusk violet; day is grey stone. Quiet, no grandeur. ──

internal val GothicNight = Tone(
    page = c(0xFF0E0C10),
    steps = listOf(c(0xFF0A090C), c(0xFF151219), c(0xFF1B171F), c(0xFF221D27), c(0xFF2B2531)),
    ink = c(0xFFE6E0EA), inkSoft = c(0xFFA69DAE),
    accent = c(0xFFB9A6C4), onAccent = c(0xFF1E1724),
    accentContainer = c(0xFF372D40), onAccentContainer = c(0xFFEDE3F2),
    outline = c(0xFF6E6575), outlineSoft = c(0xFF332C39),
    error = c(0xFFD7B8C4),
)

internal val GothicDay = Tone(
    page = c(0xFFDDD9D7),
    steps = listOf(c(0xFFE4E1DF), c(0xFFD7D3D1), c(0xFFD0CCCA), c(0xFFC8C3C2), c(0xFFBFBABA)),
    ink = c(0xFF1C1820), inkSoft = c(0xFF4C4551),
    accent = c(0xFF574766), onAccent = c(0xFFEFEAF2),
    accentContainer = c(0xFFC7BDD0), onAccentContainer = c(0xFF261D2E),
    outline = c(0xFF7B7480), outlineSoft = c(0xFFB5AFB6),
    error = c(0xFF6B3A48),
)

// ── Street art: black wall and concrete, one spray colour (a dusty pink) for the tags. ──

object Street {
    /** The spray paint: tags, drips, splatter, halos. */
    val marker = Color(0xFFE38BD2)
    val markerDeep = Color(0xFF9A3C88)
}

internal val StreetNight = Tone(
    page = c(0xFF121212),
    steps = listOf(c(0xFF0D0D0D), c(0xFF191919), c(0xFF1F1F1F), c(0xFF282828), c(0xFF323232)),
    ink = c(0xFFEDEDED), inkSoft = c(0xFFA3A3A3),
    accent = Street.marker, onAccent = c(0xFF141414),
    accentContainer = c(0xFF3A2236), onAccentContainer = c(0xFFF7D3EE),
    outline = c(0xFF6A6A6A), outlineSoft = c(0xFF333333),
    error = c(0xFFE6B3A3),
)

internal val StreetDay = Tone(
    page = c(0xFFD7D6D2),
    steps = listOf(c(0xFFDEDDD9), c(0xFFD1D0CC), c(0xFFCAC9C5), c(0xFFC2C1BD), c(0xFFB9B8B4)),
    ink = c(0xFF141414), inkSoft = c(0xFF464543),
    accent = Street.markerDeep, onAccent = c(0xFFF7E9F4),
    accentContainer = c(0xFFE7C3DF), onAccentContainer = c(0xFF34102D),
    outline = c(0xFF73726E), outlineSoft = c(0xFFAEADA9),
    error = c(0xFF7A3423),
)

// ── Breakcore: cold night with a dusty lilac, like a worn VHS cover; day is cold grey. ──

object Breakcore {
    val lilac = Color(0xFFA69BD6)
    val lilacDeep = Color(0xFF4C4480)
}

internal val BreakcoreNight = Tone(
    page = c(0xFF08080C),
    steps = listOf(c(0xFF050508), c(0xFF0F0F15), c(0xFF14141B), c(0xFF1B1B24), c(0xFF23232E)),
    ink = c(0xFFE4E4EC), inkSoft = c(0xFF9A9AB0),
    accent = Breakcore.lilac, onAccent = c(0xFF14121E),
    accentContainer = c(0xFF2C2842), onAccentContainer = c(0xFFE6E1FA),
    outline = c(0xFF62627A), outlineSoft = c(0xFF2A2A36),
    error = c(0xFFD6B4C9),
)

internal val BreakcoreDay = Tone(
    page = c(0xFFD2D2D8),
    steps = listOf(c(0xFFD9D9DF), c(0xFFCDCDD3), c(0xFFC6C6CD), c(0xFFBEBEC6), c(0xFFB5B5BE)),
    ink = c(0xFF121218), inkSoft = c(0xFF45455A),
    accent = Breakcore.lilacDeep, onAccent = c(0xFFEDEBF7),
    accentContainer = c(0xFFC2BDE0), onAccentContainer = c(0xFF1E1A3A),
    outline = c(0xFF72728A), outlineSoft = c(0xFFABABBA),
    error = c(0xFF6E3A55),
)
