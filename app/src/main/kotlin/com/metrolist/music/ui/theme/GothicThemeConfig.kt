package com.metrolist.music.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.metrolist.music.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Configuration and asset management for the GOTHIC Theme.
 * Implements medieval cathedral aesthetics, lancet arches, dusk violet stone, and gothic cross decor.
 */
object GothicThemeConfig {
    // ── Exact Gothic Night & Day Colors ────────────────────────────────────────────────
    // Dark Mode (GothicNight: night stone with dusk violet)
    val Background = Color(0xFF0E0C10)
    val Surface = Color(0xFF151219)
    val Elevated = Color(0xFF1B171F)
    val PrimaryText = Color(0xFFE6E0EA)
    val SecondaryText = Color(0xFFA69DAE)
    val MutedText = Color(0xFF6E6575)
    val Border = Color(0xFF332C39)
    val Accent = Color(0xFFB9A6C4)

    // Light Mode (GothicDay: weathered medieval limestone)
    val LightBackground = Color(0xFFDDD9D7)
    val LightSurface = Color(0xFFD7D3D1)
    val LightElevated = Color(0xFFD0CCCA)
    val LightPrimaryText = Color(0xFF1C1820)
    val LightSecondaryText = Color(0xFF4C4551)
    val LightMutedText = Color(0xFF7B7480)
    val LightBorder = Color(0xFFB5AFB6)
    val LightAccent = Color(0xFF574766)

    @Composable
    fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.4f

    fun getBackground(isDark: Boolean) = if (isDark) Background else LightBackground
    fun getSurface(isDark: Boolean) = if (isDark) Surface else LightSurface
    fun getElevated(isDark: Boolean) = if (isDark) Elevated else LightElevated
    fun getPrimaryText(isDark: Boolean) = if (isDark) PrimaryText else LightPrimaryText
    fun getSecondaryText(isDark: Boolean) = if (isDark) SecondaryText else LightSecondaryText
    fun getMutedText(isDark: Boolean) = if (isDark) MutedText else LightMutedText
    fun getBorder(isDark: Boolean) = if (isDark) Border else LightBorder
    fun getAccent(isDark: Boolean) = if (isDark) Accent else LightAccent

    // ── Asset Paths ────────────────────────────────────────────────────────────────────
    const val PATH_HOME_BG = "gothic/backgrounds/home_background.webp"
    const val PATH_HOME_BG_LIGHT = "gothic/backgrounds/home_background_light.webp"
    const val PATH_LIBRARY_BG = "gothic/backgrounds/library_background.webp"
    const val PATH_LIBRARY_BG_LIGHT = "gothic/backgrounds/library_background_light.webp"
    const val PATH_PLAYER_BG = "gothic/backgrounds/player_background.webp"
    const val PATH_PLAYER_BG_LIGHT = "gothic/backgrounds/player_background_light.webp"
    const val PATH_CHAT_BG = "gothic/backgrounds/chat_background.webp"
    const val PATH_CHAT_BG_LIGHT = "gothic/backgrounds/chat_background_light.webp"

    fun getHomeBg(isDark: Boolean) = if (isDark) PATH_HOME_BG else PATH_HOME_BG_LIGHT
    fun getLibraryBg(isDark: Boolean) = if (isDark) PATH_LIBRARY_BG else PATH_LIBRARY_BG_LIGHT
    fun getPlayerBg(isDark: Boolean) = if (isDark) PATH_PLAYER_BG else PATH_PLAYER_BG_LIGHT
    fun getChatBg(isDark: Boolean) = if (isDark) PATH_CHAT_BG else PATH_CHAT_BG_LIGHT

    // ── Typography ──────────────────────────────────────────────────────────────────────
    val TitleFont = GothicFace
    val SerifFont = FontFamily(Font(R.font.satanic_serif, FontWeight.Normal))

    // ── Synchronous LRU / Cached Asset Loader ───────────────────────────────────────────
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun getAssetBitmap(context: Context, path: String): ImageBitmap? {
        return cache.getOrPut(path) {
            try {
                context.assets.open(path).use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    bitmap?.asImageBitmap() ?: return null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
