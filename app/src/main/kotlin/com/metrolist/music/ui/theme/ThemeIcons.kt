/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import com.metrolist.music.R
import com.metrolist.music.ui.screens.Screens

/**
 * The experimental themes swap a few icons for their own: the Home and Library tabs and the like
 * heart. Satanic: an inverted pentagram and an inverted cross; Gothic: a lancet window and a cross;
 * Street art: a crown and a spray can; Breakcore: a crossed-out box, glitch bars, a broken heart.
 * The same places, the same actions — only the drawing differs.
 */
object ThemeIcons {
    /** The tab's icon under the current theme ([fallback] when the theme keeps it). */
    fun nav(screen: Screens, selected: Boolean, fallback: Int): Int {
        val home = screen == Screens.Home
        val library = screen == Screens.Library
        if (!home && !library) return fallback
        return when (ThemeRuntime.preset) {
            AppThemePreset.SATANIC -> if (home) {
                if (selected) R.drawable.theme_pentagram_active else R.drawable.theme_pentagram_outline
            } else {
                if (selected) R.drawable.theme_cross_inverted_filled else R.drawable.theme_cross_inverted_outline
            }
            AppThemePreset.GOTHIC -> if (home) {
                if (selected) R.drawable.theme_arch_filled else R.drawable.theme_arch_outline
            } else {
                if (selected) R.drawable.theme_cross_filled else R.drawable.theme_cross_outline
            }
            AppThemePreset.STREET -> if (home) {
                if (selected) R.drawable.theme_crown_filled else R.drawable.theme_crown_outline
            } else {
                if (selected) R.drawable.theme_spray_filled else R.drawable.theme_spray_outline
            }
            AppThemePreset.BREAKCORE -> if (home) {
                if (selected) R.drawable.theme_crossed_box_filled else R.drawable.theme_crossed_box_outline
            } else {
                R.drawable.theme_glitch_bars
            }
            else -> fallback
        }
    }

    /**
     * The Satanic like. On the light theme the filled heart gets a thin ring a little away from it:
     * its cut-through lines along the edge read as a white outline on the white background.
     */
    fun satanicLike(liked: Boolean): Int = when {
        !liked -> R.drawable.theme_heart_outline
        ThemeRuntime.isDark -> R.drawable.theme_heart_dagger_filled
        else -> R.drawable.theme_heart_dagger_filled_light
    }

    /** The Satanic like (a tall heart, a dagger when liked) needs more height than a square icon. */
    val likeIsTall: Boolean get() = ThemeRuntime.preset == AppThemePreset.SATANIC

    /** The like icon (the usual heart, synced to the account) under the current theme; null keeps the heart. */
    fun like(liked: Boolean): Int? = when (ThemeRuntime.preset) {
        // A heart; liked, a dagger through it, the heart filled and its lines cut through (white on
        // the dark theme like the drawing, black on the light one). Tinted like any icon.
        AppThemePreset.SATANIC -> satanicLike(liked)
        AppThemePreset.GOTHIC -> if (liked) R.drawable.theme_cross_filled else R.drawable.theme_cross_outline
        AppThemePreset.STREET -> if (liked) R.drawable.theme_crown_filled else R.drawable.theme_crown_outline
        AppThemePreset.BREAKCORE -> if (liked) R.drawable.theme_heart_broken_filled else R.drawable.theme_heart_broken_outline
        else -> null
    }
}
