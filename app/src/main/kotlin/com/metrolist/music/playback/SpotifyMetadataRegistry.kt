/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.util.LruCache
import com.metrolist.spotify.models.SpotifyTrack

/**
 * Maps a resolved YouTube video id back to the originating [SpotifyTrack] so the
 * player can recover Spotify metadata (ISRC, album, etc.) for a queued item.
 *
 * Backed by a bounded, thread-safe [LruCache] so the registry can't grow without
 * limit over a long-lived process (one entry per unique resolved track otherwise
 * lived forever). The eldest entries are evicted once the bound is reached.
 */
object SpotifyMetadataRegistry {
    private const val MAX_ENTRIES = 1024

    /**
     * Media id prefix for a Spotify track that had no confident YouTube match and is played/downloaded
     * from Qobuz instead (resolved by ISRC/metadata at playback time). Distinct from the "qobuz:" cache
     * key prefix. A media id `QOBUZ_FALLBACK_PREFIX + spotifyId` never hits the YouTube path.
     */
    const val QOBUZ_FALLBACK_PREFIX = "qbzfb:"

    fun isQobuzFallbackId(mediaId: String): Boolean = mediaId.startsWith(QOBUZ_FALLBACK_PREFIX)

    private val byYoutubeId = LruCache<String, SpotifyTrack>(MAX_ENTRIES)

    fun register(youtubeId: String, track: SpotifyTrack) {
        byYoutubeId.put(youtubeId, track)
    }

    fun get(youtubeId: String): SpotifyTrack? = byYoutubeId.get(youtubeId)

    fun invalidate(youtubeId: String) {
        byYoutubeId.remove(youtubeId)
    }

    fun clearAll() {
        byYoutubeId.evictAll()
    }
}
