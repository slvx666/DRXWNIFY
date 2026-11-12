/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.metrolist.music.constants.MetadataSource

/**
 * The colour of each music service (Spotify green, Yandex Music yellow, VK blue) — the library's
 * selected chips, sort mark and pins are painted in the main account's. Under a themed look the
 * brand colour gives way to the theme's own shade of it, so it doesn't shout over the palette:
 * Gruvbox's soft blue for VK, its green and yellow for the others, and so on.
 */
object ServiceColors {
    val SpotifyGreen = Color(0xFF1ED760)
    val YandexYellow = Color(0xFFFFCC00)
    val VkBlue = Color(0xFF2787F5)

    fun brand(source: MetadataSource): Color = when (source) {
        MetadataSource.SPOTIFY -> SpotifyGreen
        MetadataSource.YANDEX -> YandexYellow
        MetadataSource.VK -> VkBlue
    }

    /** The service's colour under [preset] (dark or light background). */
    fun themed(source: MetadataSource, preset: AppThemePreset, dark: Boolean): Color = when (preset) {
        AppThemePreset.STANDARD -> brand(source)
        AppThemePreset.GRUVBOX -> when (source) {
            MetadataSource.SPOTIFY -> if (dark) Gruvbox.green else Color(0xFF79740E)
            MetadataSource.YANDEX -> if (dark) Gruvbox.yellow else Color(0xFFB57614)
            MetadataSource.VK -> if (dark) Gruvbox.blue else Color(0xFF076678)
        }
        AppThemePreset.CATPPUCCIN -> when (source) {
            MetadataSource.SPOTIFY -> if (dark) Color(0xFFA6E3A1) else Color(0xFF40A02B)
            MetadataSource.YANDEX -> if (dark) Color(0xFFF9E2AF) else Color(0xFFDF8E1D)
            MetadataSource.VK -> if (dark) Color(0xFF89B4FA) else Color(0xFF1E66F5)
        }
        AppThemePreset.SYNTHWAVE -> when (source) {
            MetadataSource.SPOTIFY -> Color(0xFF72F1B8)
            MetadataSource.YANDEX -> Color(0xFFFEDE5D)
            MetadataSource.VK -> Color(0xFF36F9F6)
        }
        AppThemePreset.SAKURA -> when (source) {
            MetadataSource.SPOTIFY -> if (dark) Color(0xFF9CCB9A) else Color(0xFF4F8A4C)
            MetadataSource.YANDEX -> if (dark) Color(0xFFE8C77A) else Color(0xFFA67C1F)
            MetadataSource.VK -> if (dark) Color(0xFF9DB8E0) else Color(0xFF4A6FA5)
        }
        AppThemePreset.LINUX -> when (source) {
            MetadataSource.SPOTIFY -> if (dark) Color(0xFF8AE234) else Color(0xFF4E9A06)
            MetadataSource.YANDEX -> if (dark) Color(0xFFFCE94F) else Color(0xFFC4A000)
            MetadataSource.VK -> if (dark) Color(0xFF729FCF) else Color(0xFF3465A4)
        }
    }

    /** Text on top of [color]: black on light shades, white on dark ones. */
    fun onColor(color: Color): Color = if (color.luminance() > 0.45f) Color.Black else Color.White
}

/** The colour of [source] in the current theme. */
@Composable
fun serviceColor(source: MetadataSource): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return ServiceColors.themed(source, LocalAppThemePreset.current, dark)
}
