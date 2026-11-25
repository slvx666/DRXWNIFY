package com.metrolist.music.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.metrolist.music.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized configuration for the SATANIC Theme.
 * Implements Stage 7: Colors and Theme System with exact reference values.
 */
object SatanicThemeConfig {
    // ── Exact Reference Colors ──────────────────────────────────────────────────────────
    // Dark Mode (SatanicNight)
    val Background = Color(0xFF080808)
    val Surface = Color(0xFF111111)
    val ElevatedSurface = Color(0xFF171717)
    val Elevated = ElevatedSurface
    val PrimaryText = Color(0xFFE5E5E5)
    val SecondaryText = Color(0xFFA0A0A0)
    val MutedText = Color(0xFF777777)
    val Border = Color(0xFF4A4A4A)
    val Accent = Color(0xFFD0D0D0)

    /**
     * The middle mode (dark, not pure black): the light mode's old book, far older and gloomier.
     * Set by the app from the theme settings; every Light* value and light background below then
     * resolves to its Dusk counterpart, so the screens drawn "light" get the withered paper.
     */
    var dusk by mutableStateOf(false)

    // Light Mode (SatanicDay - ash stone & aged parchment)
    private val DayBackground = Color(0xFFD4CFC0)
    private val DaySurface = Color(0xFFCEC7B4)
    private val DayElevated = Color(0xFFC6BFAC)
    private val DayPrimaryText = Color(0xFF0A0A0A)
    private val DaySecondaryText = Color(0xFF3F3C36)
    private val DayMutedText = Color(0xFF6E6A60)
    private val DayBorder = Color(0xFF6E6A60)
    private val DayAccent = Color(0xFF0A0A0A)

    // Dusk Mode (SatanicDusk - withered, browned, crumbling book); matches SatanicDusk in MonoThemes.
    private val DuskBackground = Color(0xFFA0957B)
    private val DuskSurface = Color(0xFF968A71)
    private val DuskElevated = Color(0xFF8E826A)
    private val DuskPrimaryText = Color(0xFF0B0907)
    private val DuskSecondaryText = Color(0xFF2C271F)
    private val DuskMutedText = Color(0xFF4A4234)
    private val DuskBorder = Color(0xFF463F32)
    private val DuskAccent = Color(0xFF0B0907)

    val LightBackground: Color get() = if (dusk) DuskBackground else DayBackground
    val LightSurface: Color get() = if (dusk) DuskSurface else DaySurface
    val LightElevated: Color get() = if (dusk) DuskElevated else DayElevated
    val LightPrimaryText: Color get() = if (dusk) DuskPrimaryText else DayPrimaryText
    val LightSecondaryText: Color get() = if (dusk) DuskSecondaryText else DaySecondaryText
    val LightMutedText: Color get() = if (dusk) DuskMutedText else DayMutedText
    val LightBorder: Color get() = if (dusk) DuskBorder else DayBorder
    val LightAccent: Color get() = if (dusk) DuskAccent else DayAccent

    @Composable
    fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.isDarkScheme()

    fun getBackground(isDark: Boolean) = if (isDark) Background else LightBackground
    fun getSurface(isDark: Boolean) = if (isDark) Surface else LightSurface
    fun getElevated(isDark: Boolean) = if (isDark) Elevated else LightElevated
    fun getPrimaryText(isDark: Boolean) = if (isDark) PrimaryText else LightPrimaryText
    fun getSecondaryText(isDark: Boolean) = if (isDark) SecondaryText else LightSecondaryText
    fun getMutedText(isDark: Boolean) = if (isDark) MutedText else LightMutedText
    fun getBorder(isDark: Boolean) = if (isDark) Border else LightBorder
    fun getAccent(isDark: Boolean) = if (isDark) Accent else LightAccent

    // ── Asset Paths (located in assets/satanic/...) ──────────────────────────────────────
    const val PATH_HOME_BG = "satanic/backgrounds/home_background.webp"
    const val PATH_HOME_BG_LIGHT = "satanic/backgrounds/home_background_light.webp"
    const val PATH_LIBRARY_BG = "satanic/backgrounds/library_background.webp"
    const val PATH_LIBRARY_BG_LIGHT = "satanic/backgrounds/library_background_light.webp"
    const val PATH_PLAYER_BG = "satanic/backgrounds/player_background.webp"
    const val PATH_PLAYER_BG_LIGHT = "satanic/backgrounds/player_background_light.webp"
    const val PATH_CHAT_BG = "satanic/backgrounds/chat_background.webp"
    const val PATH_CHAT_BG_LIGHT = "satanic/backgrounds/chat_background_light.webp"

    const val PATH_HOME_BG_DUSK = "satanic/backgrounds/home_background_dusk.webp"
    const val PATH_LIBRARY_BG_DUSK = "satanic/backgrounds/library_background_dusk.webp"
    const val PATH_PLAYER_BG_DUSK = "satanic/backgrounds/player_background_dusk.webp"
    const val PATH_CHAT_BG_DUSK = "satanic/backgrounds/chat_background_dusk.webp"

    fun getHomeBg(isDark: Boolean) = if (isDark) PATH_HOME_BG else if (dusk) PATH_HOME_BG_DUSK else PATH_HOME_BG_LIGHT
    fun getLibraryBg(isDark: Boolean) = if (isDark) PATH_LIBRARY_BG else if (dusk) PATH_LIBRARY_BG_DUSK else PATH_LIBRARY_BG_LIGHT
    fun getPlayerBg(isDark: Boolean) = if (isDark) PATH_PLAYER_BG else if (dusk) PATH_PLAYER_BG_DUSK else PATH_PLAYER_BG_LIGHT
    fun getChatBg(isDark: Boolean) = if (isDark) PATH_CHAT_BG else if (dusk) PATH_CHAT_BG_DUSK else PATH_CHAT_BG_LIGHT

    const val PATH_ALBUM_FRAME = "satanic/player/album_frame.png"
    const val PATH_PLAY_BUTTON_FRAME = "satanic/player/play_button_frame.png"
    const val PATH_CONTROL_BUTTON_FRAME = "satanic/player/control_button_frame.png"
    const val PATH_PROGRESS_DECORATION = "satanic/player/progress_decoration.png"

    const val PATH_MINI_PLAYER_FRAME = "satanic/components/mini_player_frame.png"
    const val PATH_SECTION_DIVIDER = "satanic/components/section_divider.png"
    const val PATH_HEADER_DECORATION = "satanic/components/header_decoration.png"
    const val PATH_ACTIVE_TAB_DECORATION = "satanic/components/active_tab_decoration.png"
    const val PATH_NAVIGATION_UNDERLINE = "satanic/components/navigation_underline.png"

    const val PATH_BLACK_GRAIN = "satanic/textures/black_grain.webp"
    const val PATH_SURFACE_SCRATCHES = "satanic/textures/surface_scratches.webp"
    const val PATH_PRINT_DISTRESS = "satanic/textures/print_distress.webp"

    // ── Typography ──────────────────────────────────────────────────────────────────────
    /** Classic elegant serif font with full Cyrillic + Latin support (Cormorant Garamond). */
    val SerifFont = FontFamily(Font(R.font.satanic_serif, FontWeight.Normal))
    val SerifFontMedium = FontFamily(Font(R.font.satanic_serif, FontWeight.Medium))
    val SerifFontBold = FontFamily(Font(R.font.satanic_serif, FontWeight.Bold))

    /** Blackletter / Gothic accent face. */
    val GothicAccentFont = FontFamily(Font(R.font.metal_mania, FontWeight.Normal))

    /** Dual font with Gothic Latin and full Cyrillic support fallback. */
    val TitleFont = FontFamily(
        Font(R.font.metal_mania),
        Font(R.font.satanic_serif),
    )

    // ── Synchronous LRU / Cached Asset Loader ───────────────────────────────────────────
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun getAssetBitmap(context: Context, path: String): ImageBitmap? {
        return cache.getOrPut(path) {
            try {
                context.assets.open(path).use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                } ?: return null
            } catch (e: Exception) {
                null
            }
        }
    }

    fun loadBitmapAsset(context: Context, path: String): BitmapPainter? {
        val bmp = getAssetBitmap(context, path) ?: return null
        return BitmapPainter(bmp)
    }

    @Composable
    fun rememberAsset(path: String): ImageBitmap? {
        val context = LocalContext.current
        return remember(path) { getAssetBitmap(context, path) }
    }

    @Composable
    fun rememberPainter(path: String): BitmapPainter? {
        val bmp = rememberAsset(path) ?: return null
        return remember(bmp) { BitmapPainter(bmp) }
    }
}
