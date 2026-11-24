/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.metrolist.music.playback.queues.Queue

/**
 * The list screen that is open right now: an album, a playlist, liked tracks. A track queued by hand
 * while its list is open (from a menu, a swipe, a selection — whichever way) remembers the list: when
 * the queue runs out on it, that list goes on from after the track (an album then into
 * recommendations from it), instead of recommendations straight away.
 */
object OpenAlbum {
    @Volatile private var owner: Any? = null
    @Volatile private var queueFrom: ((String) -> Queue?)? = null

    /** [queueFrom]: the list as a queue starting at the player item with this media id, or null when it isn't on the list. */
    fun open(owner: Any, queueFrom: (String) -> Queue?) {
        this.owner = owner
        this.queueFrom = queueFrom
    }

    fun close(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            queueFrom = null
        }
    }

    fun queueFrom(mediaId: String): Queue? = runCatching { queueFrom?.invoke(mediaId) }.getOrNull()

    /** A player media id as a catalog track id ("mfb:…" / a YouTube match of a Spotify track → the Spotify id). */
    fun catalogIdOf(mediaId: String, matches: Map<String, String> = emptyMap()): String =
        SpotifyMetadataRegistry.get(mediaId)?.id
            ?: com.metrolist.music.resolver.FallbackIds.catalogIdOf(mediaId)
            ?: matches.entries.firstOrNull { it.value == mediaId }?.key
            ?: mediaId
}

/** Registers the screen's list with [OpenAlbum] while it is shown. */
@Composable
fun OpenListEffect(queueFrom: (String) -> Queue?) {
    val current by rememberUpdatedState(queueFrom)
    DisposableEffect(Unit) {
        val token = Any()
        OpenAlbum.open(token) { mediaId -> current(mediaId) }
        onDispose { OpenAlbum.close(token) }
    }
}
