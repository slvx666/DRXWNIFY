/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context

/**
 * The user's own order of a list the app can't change at its source (a catalog album): the tracks
 * dragged into place are shown and played in that order on this phone. Tracks the stored order
 * doesn't know (the album changed) keep their place after the known ones.
 */
object TrackOrderStore {
    private fun prefs(context: Context) = context.getSharedPreferences("track_orders", Context.MODE_PRIVATE)

    fun <T> apply(context: Context, listKey: String, items: List<T>, idOf: (T) -> String): List<T> {
        val stored = prefs(context).getString(listKey, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() }
        if (stored.isNullOrEmpty()) return items
        val position = stored.withIndex().associate { (index, id) -> id to index }
        return items.withIndex()
            .sortedWith(compareBy({ position[idOf(it.value)] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }

    fun save(context: Context, listKey: String, ids: List<String>) {
        prefs(context).edit().putString(listKey, ids.joinToString(SEPARATOR)).apply()
    }

    fun reset(context: Context, listKey: String) {
        prefs(context).edit().remove(listKey).apply()
    }

    fun hasCustomOrder(context: Context, listKey: String): Boolean = prefs(context).contains(listKey)

    private const val SEPARATOR = "\u001F"
}
