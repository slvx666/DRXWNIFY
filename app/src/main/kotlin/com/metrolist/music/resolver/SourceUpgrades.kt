/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Better-sounding versions found after a track had already started (the AUTO mode keeps searching
 * once the first exact match plays). The version becomes the track's choice for its next play and
 * download; the track that is playing keeps what it has, and the player shows a small green arrow
 * next to its bitrate so the user can switch by hand ("Track version").
 */
object SourceUpgrades {
    data class Upgrade(val match: ProviderMatch, val kbps: Int)

    private val _available = MutableStateFlow<Map<String, Upgrade>>(emptyMap())

    /** catalog id → the better version found. */
    val available: StateFlow<Map<String, Upgrade>> = _available

    fun offer(catalogId: String, match: ProviderMatch, kbps: Int) {
        _available.value = (_available.value + (catalogId to Upgrade(match, kbps))).let { all ->
            // Only the latest few matter (the arrow is for the track playing now and the next ones).
            if (all.size > MAX_KEPT) all.entries.drop(all.size - MAX_KEPT).associate { it.key to it.value } else all
        }
    }

    fun clear(catalogId: String) {
        if (catalogId in _available.value) _available.value = _available.value - catalogId
    }

    private const val MAX_KEPT = 200
}
