/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * When each album, playlist and artist was last opened or played in the app — what the library's
 * "Recents" order means. The streaming account's own order only knows what happened in its own
 * app, so playing an album here never moved it up.
 *
 * Keys: "album:<id>", "playlist:<id>", "artist:<id>", "local:<id>" (app playlist), "liked".
 */
object LibraryRecents {
    private const val MAX_KEPT = 1000

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _version = MutableStateFlow(0)

    /** Bumped on every change, so a sorted list re-sorts. */
    val version: StateFlow<Int> = _version

    fun init(context: Context) {
        prefs = context.getSharedPreferences("library_recents", Context.MODE_PRIVATE)
    }

    fun touch(key: String?) {
        if (key.isNullOrBlank()) return
        val store = prefs ?: return
        val editor = store.edit().putLong(key, System.currentTimeMillis())
        val all = store.all
        if (all.size > MAX_KEPT) {
            all.entries.sortedBy { it.value as? Long ?: 0L }.take(all.size - MAX_KEPT).forEach { editor.remove(it.key) }
        }
        editor.apply()
        _version.value++
    }

    fun album(id: String?) = touch(id?.takeIf { it.isNotBlank() }?.let { "album:$it" })

    fun playlist(id: String?) = touch(id?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("album_")) "album:${it.removePrefix("album_")}" else "playlist:$it" })

    fun artist(id: String?) = touch(id?.takeIf { it.isNotBlank() }?.let { "artist:$it" })

    fun local(id: String?) = touch(id?.takeIf { it.isNotBlank() }?.let { "local:$it" })

    /** When [key] was last opened or played, 0 = never. */
    fun at(key: String): Long = (prefs?.all?.get(key) as? Long) ?: 0L

    fun snapshot(): Map<String, Long> = prefs?.all?.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }?.toMap().orEmpty()
}
