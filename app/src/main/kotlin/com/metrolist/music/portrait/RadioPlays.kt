/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.portrait

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/**
 * Which plays came from the endless radio rather than from the user's own choice: the history in
 * the database doesn't say, and the portrait counts radio a third. Kept as "track|day" for a year.
 */
object RadioPlays {
    private const val KEEP_DAYS = 400L
    private var prefs: SharedPreferences? = null

    @Volatile
    private var keys: MutableSet<String> = HashSet()

    fun init(context: Context) {
        val p = context.getSharedPreferences("radio_plays", Context.MODE_PRIVATE)
        prefs = p
        val oldest = LocalDate.now().minusDays(KEEP_DAYS)
        val kept = p.getStringSet("keys", emptySet()).orEmpty().filterTo(HashSet()) { key ->
            runCatching { !LocalDate.parse(key.substringAfterLast('|')).isBefore(oldest) }.getOrDefault(false)
        }
        keys = kept
        p.edit().putStringSet("keys", HashSet(kept)).apply()
    }

    fun mark(songId: String) {
        val key = "$songId|${LocalDate.now()}"
        synchronized(this) {
            if (!keys.add(key)) return
            prefs?.edit()?.putStringSet("keys", HashSet(keys))?.apply()
        }
    }

    fun isRadio(songId: String, day: LocalDate): Boolean = "$songId|$day" in keys
}
