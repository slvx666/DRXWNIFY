/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context

/**
 * Tracks whose lyrics the user put in or picked from the search themselves: those are trusted to
 * go into a downloaded file as they are. Lyrics found on their own are written only when matched
 * for sure.
 */
object LyricsPicks {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("lyrics_picks", Context.MODE_PRIVATE)

    fun mark(context: Context, songId: String) {
        prefs(context).edit().putBoolean(songId, true).apply()
    }

    fun isPicked(context: Context, songId: String): Boolean = prefs(context).getBoolean(songId, false)
}
