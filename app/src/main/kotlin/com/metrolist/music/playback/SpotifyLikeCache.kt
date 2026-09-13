/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.spotify.Spotify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Process-wide cache of which Spotify track ids are in the linked account's Liked Songs.
 *
 * The app already mirrors app-side likes to Spotify, but the reverse — tracks liked directly on
 * Spotify — had no way to show a heart in the UI (the local DB `liked` flag was never set for them).
 * This cache is consulted by Spotify-sourced lists (top tracks, albums, search) so those tracks show
 * a filled heart, and is updated optimistically when the user likes/unlikes so every screen stays in
 * sync without a round-trip.
 *
 * State is in-memory only (rebuilt per process); it queries [Spotify.tracksSaved] lazily for ids it
 * hasn't seen yet, in batches, and never re-queries a known id.
 */
object SpotifyLikeCache {
    private val _liked = MutableStateFlow<Set<String>>(emptySet())

    /** The set of Spotify track ids currently known to be liked. Observe this to render hearts. */
    val liked: StateFlow<Set<String>> = _liked.asStateFlow()

    /** Ids we've already resolved (liked or not), so we don't re-query them. */
    private val known = HashSet<String>()
    private val mutex = Mutex()

    fun isLiked(spotifyId: String?): Boolean = spotifyId != null && _liked.value.contains(spotifyId)

    /**
     * Ensures the liked-state of [spotifyIds] is loaded, querying Spotify only for ids not seen
     * before. Safe to call repeatedly (e.g. from a screen's LaunchedEffect); no-op when nothing new.
     */
    @Volatile private var libraryLoadedAt = 0L
    private val loadMutex = Mutex()
    private const val REFRESH_MS = 10 * 60 * 1000L
    private const val PAGE = 50
    private const val MAX_TRACKS = 10_000

    suspend fun ensureLoaded(@Suppress("UNUSED_PARAMETER") spotifyIds: List<String>) {
        // The per-id REST check (GET /me/tracks/contains) was called from every list and quickly hit
        // Spotify's 429 rate limit — after which no hearts showed at all. Instead load the whole Liked
        // Songs library once (GQL, paged) and answer every id from memory; refresh occasionally.
        if (!Spotify.isAuthenticated()) return
        if (System.currentTimeMillis() - libraryLoadedAt < REFRESH_MS) return
        loadMutex.withLock {
            if (System.currentTimeMillis() - libraryLoadedAt < REFRESH_MS) return
            val all = HashSet<String>()
            var offset = 0
            while (offset < MAX_TRACKS) {
                val page = Spotify.likedSongs(limit = PAGE, offset = offset).getOrElse { e ->
                    Timber.w(e, "SpotifyLikeCache: likedSongs page failed at offset=$offset")
                    if (all.isEmpty()) return // keep previous state; retry on next call
                    null
                } ?: break
                val ids = page.items.mapNotNull { it.track.id.takeIf { id -> id.isNotBlank() } }
                all.addAll(ids)
                offset += PAGE
                if (page.items.size < PAGE || (page.total in 1..offset)) break
            }
            mutex.withLock { known.clear(); known.addAll(all) }
            // Keep optimistic likes made while loading.
            _liked.value = all
            libraryLoadedAt = System.currentTimeMillis()
            Timber.d("SpotifyLikeCache: loaded ${all.size} liked tracks")
        }
    }

    /** Optimistically reflect a like/unlike so all observing screens update immediately. */
    fun setLiked(spotifyId: String?, liked: Boolean) {
        if (spotifyId.isNullOrBlank()) return
        synchronized(known) { known.add(spotifyId) }
        _liked.value = if (liked) _liked.value + spotifyId else _liked.value - spotifyId
    }

    /** Seed a batch of ids as liked without a network call (e.g. the Liked Songs list). */
    fun markLiked(spotifyIds: Collection<String>) {
        val clean = spotifyIds.filter { it.isNotBlank() }
        if (clean.isEmpty()) return
        synchronized(known) { known.addAll(clean) }
        _liked.value = _liked.value + clean
    }
}
