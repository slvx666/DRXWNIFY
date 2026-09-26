/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.content.SharedPreferences

/**
 * What "For you" has already handed out, kept for [KEEP_DAYS] days across app starts. Spotify's own
 * mixes change slowly, so without this the same handful of tracks came back day after day.
 */
object ForYouSeen {
    private const val KEEP_DAYS = 10L
    private const val MAX_KEPT = 3000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("for_you_seen", Context.MODE_PRIVATE)
    }

    /** Track ids handed out in the last [KEEP_DAYS] days. */
    fun recent(): Set<String> {
        val store = prefs ?: return emptySet()
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * DAY_MS
        return store.all.entries.filter { (it.value as? Long ?: 0L) >= cutoff }.mapTo(HashSet()) { it.key }
    }

    /** When [id] was last handed out (0 = never). */
    fun seenAt(id: String): Long = (prefs?.all?.get(id) as? Long) ?: 0L

    fun record(ids: Collection<String>) {
        val store = prefs ?: return
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val editor = store.edit()
        ids.forEach { editor.putLong(it, now) }
        // Old entries and the overflow go, oldest first.
        val cutoff = now - KEEP_DAYS * DAY_MS
        val all = store.all.entries.map { it.key to (it.value as? Long ?: 0L) }
        all.filter { it.second < cutoff }.forEach { editor.remove(it.first) }
        val alive = all.filter { it.second >= cutoff }
        if (alive.size + ids.size > MAX_KEPT) {
            alive.sortedBy { it.second }.take(alive.size + ids.size - MAX_KEPT).forEach { editor.remove(it.first) }
        }
        editor.apply()
    }
}
