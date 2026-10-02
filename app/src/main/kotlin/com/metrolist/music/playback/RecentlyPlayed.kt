/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.content.SharedPreferences
import com.metrolist.spotify.models.SpotifyTrack

/**
 * The catalog tracks that actually played in the last [KEEP_DAYS] days, so a queue that picks songs
 * by itself (similar artists after an album, radios) doesn't bring back what was just heard. Kept by
 * catalog id and by "artist|title", since the same song also lives on singles and compilations
 * under other ids.
 */
object RecentlyPlayed {
    private const val KEEP_DAYS = 3L
    private const val MAX_KEPT = 2000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("recently_played", Context.MODE_PRIVATE)
    }

    fun keyOf(artist: String?, title: String): String =
        "k:" + (artist.orEmpty() + "|" + title).lowercase().replace(Regex("\\s*[(\\[].*"), "").trim()

    fun keyOf(track: SpotifyTrack): String = keyOf(track.artists.firstOrNull()?.name, track.name)

    fun record(track: SpotifyTrack) {
        val store = prefs ?: return
        val now = System.currentTimeMillis()
        val editor = store.edit()
            .putLong("id:" + track.id, now)
            .putLong(keyOf(track), now)
        val all = store.all.entries.map { it.key to (it.value as? Long ?: 0L) }
        val cutoff = now - KEEP_DAYS * DAY_MS
        all.filter { it.second < cutoff }.forEach { editor.remove(it.first) }
        val alive = all.filter { it.second >= cutoff }
        if (alive.size + 2 > MAX_KEPT) {
            alive.sortedBy { it.second }.take(alive.size + 2 - MAX_KEPT).forEach { editor.remove(it.first) }
        }
        editor.apply()
    }

    /** Whether [track] (or the same song under another id) played recently. */
    fun contains(track: SpotifyTrack, recent: Set<String> = snapshot()): Boolean =
        ("id:" + track.id) in recent || keyOf(track) in recent

    /** Everything recorded in the last [KEEP_DAYS] days, for checking many tracks at once. */
    fun snapshot(): Set<String> {
        val store = prefs ?: return emptySet()
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * DAY_MS
        return store.all.entries.filter { (it.value as? Long ?: 0L) >= cutoff }.mapTo(HashSet()) { it.key }
    }
}
