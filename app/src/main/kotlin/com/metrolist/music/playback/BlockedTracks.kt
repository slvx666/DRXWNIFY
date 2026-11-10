/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.metrolist.music.resolver.FallbackIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks the person never wants to hear ("Block" in a list's selection bar). A blocked track stays
 * in its playlist or album, shown faded with a crossed circle, and the player skips it.
 *
 * Kept by the track's catalog id (one track, not every version of the song): the same track
 * reached as "mfb:<id>", as its matched YouTube video or as a catalog row is one key, so blocking it
 * in one list blocks it everywhere. A track with no catalog id (a YouTube video, a VK track, a file)
 * is kept by its own id. Stored in SharedPreferences, so a backup carries it over.
 */
object BlockedTracks {
    private const val PREFS = "blocked_tracks"
    private const val KEY = "keys"

    private var prefs: SharedPreferences? = null
    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = _keys

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _keys.value = p.getStringSet(KEY, emptySet()).orEmpty().toSet()
    }

    /** The key a media id, a catalog track id or a song row id is blocked under. */
    fun keyOf(id: String): String =
        FallbackIds.catalogIdOf(id)
            ?: SpotifyMetadataRegistry.get(id)?.id?.takeIf { it.isNotBlank() }
            ?: id.removePrefix("spotify:")

    fun isBlocked(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        val set = _keys.value
        return set.isNotEmpty() && (keyOf(id) in set || id in set)
    }

    /** Blocks (or lets play again) the tracks under [keys] (already passed through [keyOf]). */
    fun set(keys: Collection<String>, blocked: Boolean) {
        if (keys.isEmpty()) return
        val next = if (blocked) _keys.value + keys else _keys.value - keys.toSet()
        _keys.value = next
        prefs?.edit()?.putStringSet(KEY, HashSet(next))?.apply()
    }
}

/** Whether the track under [id] is blocked, kept current while shown. */
@Composable
fun rememberIsBlocked(id: String?): Boolean {
    val keys by BlockedTracks.keys.collectAsState()
    if (id.isNullOrBlank() || keys.isEmpty()) return false
    return BlockedTracks.keyOf(id) in keys || id in keys
}
