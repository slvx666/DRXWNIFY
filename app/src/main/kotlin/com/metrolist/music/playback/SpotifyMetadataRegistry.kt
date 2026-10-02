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

    // Fallback media ids ("mfb:<catalogId>", legacy "qbzfb:") live in resolver.FallbackIds.

    private val byYoutubeId = LruCache<String, SpotifyTrack>(MAX_ENTRIES)

    fun register(youtubeId: String, track: SpotifyTrack) {
        byYoutubeId.put(youtubeId, track)
    }

    fun get(youtubeId: String): SpotifyTrack? = byYoutubeId.get(youtubeId)

    /**
     * The catalog track a player item stands for, so a list can mark the row that is playing: the
     * track the queue registered for this id, the catalog id inside a fallback id, else the saved
     * YouTube match. The reverse match alone missed every `mfb:` item, and for a video matched to
     * two tracks it could name the other one.
     */
    suspend fun catalogIdOf(database: com.metrolist.music.db.MusicDatabase, mediaId: String?): String? {
        if (mediaId == null) return null
        get(mediaId)?.let { return it.id }
        com.metrolist.music.resolver.FallbackIds.catalogIdOf(mediaId)?.let { return it }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { database.getSpotifyMatchByYouTubeId(mediaId)?.spotifyId }.getOrNull()
        }
    }

    fun invalidate(youtubeId: String) {
        byYoutubeId.remove(youtubeId)
    }

    fun clearAll() {
        byYoutubeId.evictAll()
    }
}
