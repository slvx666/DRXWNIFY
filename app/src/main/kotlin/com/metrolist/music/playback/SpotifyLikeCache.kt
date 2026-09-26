/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.catalog.Catalog
import com.metrolist.spotify.models.SpotifyTrack
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

    /**
     * The liked tracks themselves, not just their ids: the library search needs titles and artists,
     * and they arrive in the same pages the ids come from, so keeping them costs no extra request.
     */
    private val _likedTracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val likedTracks: StateFlow<List<SpotifyTrack>> = _likedTracks.asStateFlow()

    /** The set of Spotify track ids currently known to be liked. Observe this to render hearts. */
    val liked: StateFlow<Set<String>> = _liked.asStateFlow()

    /** Ids we've already resolved (liked or not), so we don't re-query them. */
    private val known = HashSet<String>()
    private val mutex = Mutex()

    /**
     * Likes / unlikes made in the app, with when. The account's own list lags behind a change by a
     * few seconds, so a library load that finishes right after an unlike still lists the track: those
     * fresh choices win over what the load brought, instead of the heart lighting up again.
     */
    private val recentChoices = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private const val CHOICE_WINS_MS = 2 * 60 * 1000L

    private fun withRecentChoices(loaded: Set<String>): Set<String> {
        val now = System.currentTimeMillis()
        recentChoices.entries.removeIf { now - it.value.second > CHOICE_WINS_MS }
        if (recentChoices.isEmpty()) return loaded
        val result = loaded.toMutableSet()
        recentChoices.forEach { (id, choice) -> if (choice.first) result += id else result -= id }
        return result
    }

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

    /** Titles are only kept for as many tracks as a search can sensibly look through. */
    private const val MAX_KEPT_TRACKS = 5_000

    suspend fun ensureLoaded(@Suppress("UNUSED_PARAMETER") spotifyIds: List<String>) {
        // The per-id REST check (GET /me/tracks/contains) was called from every list and quickly hit
        // Spotify's 429 rate limit — after which no hearts showed at all. Instead load the whole Liked
        // Songs library once (GQL, paged) and answer every id from memory; refresh occasionally.
        if (!Catalog.isActive) return
        if (System.currentTimeMillis() - libraryLoadedAt < REFRESH_MS) return
        loadMutex.withLock {
            if (System.currentTimeMillis() - libraryLoadedAt < REFRESH_MS) return
            val all = HashSet<String>()
            val tracks = mutableListOf<SpotifyTrack>()
            var offset = 0
            while (offset < MAX_TRACKS) {
                val page = Catalog.likedSongs(limit = PAGE, offset = offset).getOrElse { e ->
                    Timber.w(e, "SpotifyLikeCache: likedSongs page failed at offset=$offset")
                    if (all.isEmpty()) return // keep previous state; retry on next call
                    null
                } ?: break
                val ids = page.items.mapNotNull { it.track.id.takeIf { id -> id.isNotBlank() } }
                all.addAll(ids)
                if (tracks.size < MAX_KEPT_TRACKS) tracks += page.items.map { it.track }
                offset += PAGE
                if (page.items.size < PAGE || (page.total in 1..offset)) break
            }
            mutex.withLock { known.clear(); known.addAll(all) }
            // Likes and unlikes just made in the app win over the (lagging) account list.
            _liked.value = withRecentChoices(all)
            _likedTracks.value = tracks
            libraryLoadedAt = System.currentTimeMillis()
            Timber.d("SpotifyLikeCache: loaded ${all.size} liked tracks")
        }
    }

    /** Optimistically reflect a like/unlike so all observing screens update immediately. */
    fun setLiked(spotifyId: String?, liked: Boolean) {
        if (spotifyId.isNullOrBlank()) return
        synchronized(known) { known.add(spotifyId) }
        recentChoices[spotifyId] = liked to System.currentTimeMillis()
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
