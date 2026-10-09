/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app speaks Russian (the default) or English, whatever the phone's language. The choice is
 * kept in [com.metrolist.music.constants.AppLanguageKey]; on Android 13+ it is handed to the system
 * as the app's own language (also shown in the system's per-app language settings).
 */
object AppLanguage {
    const val RUSSIAN = "ru"
    const val ENGLISH = "en"
    val CHOICES = listOf(RUSSIAN, ENGLISH)

    /** The saved choice; anything else (unset, "system", a language no longer offered) is Russian. */
    fun resolve(saved: String?): String = saved?.takeIf { it in CHOICES } ?: RUSSIAN

    fun label(tag: String): String = when (tag) {
        ENGLISH -> "English"
        else -> "Русский"
    }

    /** Puts [tag] in force for [activity]; may recreate it once. */
    fun apply(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = activity.getSystemService(LocaleManager::class.java) ?: return
            if (manager.applicationLocales.toLanguageTags() != tag) {
                manager.applicationLocales = LocaleList.forLanguageTags(tag)
            }
        } else {
            setAppLocale(activity, Locale.forLanguageTag(tag))
        }
    }

    /** A change from the settings: saved by the caller, applied here (older Android redraws the screen). */
    fun change(context: Context, tag: String) {
        val activity = context.findActivity() ?: return
        apply(activity, tag)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) activity.recreate()
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is android.content.ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
