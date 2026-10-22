/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.metrolist.music.R
import com.metrolist.music.constants.AppIconKey
import com.metrolist.music.constants.EnableDynamicIconKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** The app icons to pick from (Settings → Theme). [CLASSIC] is the regular launcher entry. */
enum class AppIcon(val nameRes: Int, val previewRes: Int, val alias: String?) {
    CLASSIC(R.string.app_icon_classic, R.mipmap.ic_launcher_foreground, null),
    GRUVBOX(R.string.app_theme_gruvbox, R.mipmap.ic_launcher_gruvbox_foreground, "com.metrolist.music.MainActivityGruvbox"),
    LINUX(R.string.app_theme_linux, R.mipmap.ic_launcher_linux_foreground, "com.metrolist.music.MainActivityLinux"),
    SAKURA(R.string.app_theme_sakura, R.mipmap.ic_launcher_sakura_foreground, "com.metrolist.music.MainActivitySakura"),
    SYNTHWAVE(R.string.app_theme_synthwave, R.mipmap.ic_launcher_synthwave_foreground, "com.metrolist.music.MainActivitySynthwave"),
    CATPPUCCIN(R.string.app_theme_catppuccin, R.mipmap.ic_launcher_catppuccin_foreground, "com.metrolist.music.MainActivityCatppuccin"),
    MONO(R.string.app_icon_mono, R.mipmap.ic_launcher_mono_foreground, "com.metrolist.music.MainActivityMono"),
    PAPER(R.string.app_icon_paper, R.mipmap.ic_launcher_paper_foreground, "com.metrolist.music.MainActivityPaper"),
}

object IconUtils {
    private const val DYNAMIC = "com.metrolist.music.MainActivityAlias"
    private const val STATIC = "com.metrolist.music.MainActivityStatic"

    /** Exactly one launcher entry may be enabled, or the app shows up twice in the launcher. */
    private val allAliases = listOf(DYNAMIC, STATIC) + AppIcon.entries.mapNotNull { it.alias }

    /** The "dynamic icon" setting: only the classic icon has the two variants. */
    fun setIcon(context: Context, enabled: Boolean) {
        val icon = savedIcon(context)
        apply(context, icon.alias ?: if (enabled) DYNAMIC else STATIC)
    }

    fun setAppIcon(context: Context, icon: AppIcon, dynamicIcon: Boolean) {
        apply(context, icon.alias ?: if (dynamicIcon) DYNAMIC else STATIC)
    }

    /**
     * At start-up: makes sure the chosen entry is the enabled one. An app updated from a build
     * whose enabled icon entry no longer exists would otherwise have no launcher icon at all.
     */
    fun ensureLauncherEntry(context: Context) {
        runCatching {
            val prefs = runBlocking { context.dataStore.data.first() }
            val icon = AppIcon.entries.firstOrNull { it.name == prefs[AppIconKey] } ?: AppIcon.CLASSIC
            val target = icon.alias ?: if (prefs[EnableDynamicIconKey] != false) DYNAMIC else STATIC
            val pm = context.packageManager
            val state = pm.getComponentEnabledSetting(ComponentName(context, target))
            val enabledByDefault = target == DYNAMIC
            val enabled = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && enabledByDefault)
            if (!enabled) apply(context, target)
        }
    }

    private fun savedIcon(context: Context): AppIcon =
        runCatching { runBlocking { context.dataStore.data.first() } }.getOrNull()
            ?.get(AppIconKey)
            ?.let { name -> AppIcon.entries.firstOrNull { it.name == name } }
            ?: AppIcon.CLASSIC

    private fun apply(context: Context, target: String) {
        val pm = context.packageManager
        // The new entry first: for a moment there are two rather than none (a launcher may drop
        // the app's home-screen shortcut when it briefly has no entry at all).
        pm.setComponentEnabledSetting(
            ComponentName(context, target),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        allAliases.filter { it != target }.forEach { alias ->
            pm.setComponentEnabledSetting(
                ComponentName(context, alias),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
