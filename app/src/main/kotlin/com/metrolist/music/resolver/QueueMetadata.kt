/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver

import com.metrolist.music.models.MediaMetadata
import java.util.concurrent.ConcurrentHashMap

/**
 * What every item in the player's queue is (title, artists, length), by media id. A catalog track
 * queued as "mfb:<id>" often has no saved row, and the in-memory catalog registry is gone after a
 * restart — but the queue restored from disk still carries this metadata. Without it the audio search
 * had nothing to look for ("no metadata to find audio") and nothing in a restored queue played.
 */
object QueueMetadata {
    private val byId = ConcurrentHashMap<String, MediaMetadata>()

    fun register(items: List<MediaMetadata>) {
        items.forEach { if (it.title.isNotBlank()) byId[it.id] = it }
        // Bounded: keep the most recent queues only.
        if (byId.size > MAX) byId.keys.take(byId.size - MAX).forEach(byId::remove)
    }

    fun get(mediaId: String): MediaMetadata? = byId[mediaId]

    /** A search query for [mediaId] from the queue's own metadata, or null. */
    fun queryFor(mediaId: String): AudioQuery? {
        val m = byId[mediaId] ?: return null
        val artists = m.artists.map { it.name }.filter { it.isNotBlank() }
        if (artists.isEmpty()) return null
        return AudioQuery(
            catalogId = FallbackIds.catalogIdOf(mediaId),
            title = m.title,
            artists = artists,
            album = m.album?.title,
            durationMs = m.duration.toLong().coerceAtLeast(0) * 1000,
            isrc = m.isrc,
        )
    }

    private const val MAX = 6000
}
