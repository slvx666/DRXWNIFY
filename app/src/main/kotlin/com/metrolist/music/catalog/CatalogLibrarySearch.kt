/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.catalog

import com.metrolist.music.playback.SpotifyLikeCache
import com.metrolist.spotify.models.SpotifyLibraryEntry
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * "My library" as something that can be searched offline of the account's own search API.
 *
 * Neither Spotify nor Yandex Music can search inside a user's own library, so the library is held in
 * memory instead: the liked tracks (loaded by [SpotifyLikeCache], which already pages through them)
 * and the saved playlists, albums and artists. Library search then filters those lists locally, next
 * to the results from the device's own database.
 */
object CatalogLibrarySearch {
    private val _entries = MutableStateFlow<List<SpotifyLibraryEntry>>(emptyList())
    val entries: StateFlow<List<SpotifyLibraryEntry>> = _entries.asStateFlow()

    val likedTracks: StateFlow<List<SpotifyTrack>> get() = SpotifyLikeCache.likedTracks

    private val mutex = Mutex()

    @Volatile
    private var loadedAt = 0L

    private const val TTL_MS = 10 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _loading = MutableStateFlow(false)

    /** True while the account's library is being fetched, so a search can say "still loading". */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * Starts loading the library if it isn't fresh and returns immediately: liked songs can be
     * thousands of tracks, and a search must not wait for them. Whoever reads [likedTracks] or
     * [entries] is updated as soon as the data lands.
     */
    fun ensureLoaded(force: Boolean = false) {
        if (!Catalog.isActive) return
        if (!force && System.currentTimeMillis() - loadedAt < TTL_MS) return
        scope.launch {
            mutex.withLock {
                if (!force && System.currentTimeMillis() - loadedAt < TTL_MS) return@withLock
                _loading.value = true
                runCatching { SpotifyLikeCache.ensureLoaded(emptyList()) }
                    .onFailure { Timber.w(it, "CatalogLibrarySearch: liked songs failed") }
                runCatching { Catalog.prewarmLibrary() }
                    .onSuccess { if (it.isNotEmpty()) _entries.value = it }
                    .onFailure { Timber.w(it, "CatalogLibrarySearch: library failed") }
                loadedAt = System.currentTimeMillis()
                _loading.value = false
            }
        }
    }

    fun searchTracks(query: String, limit: Int): List<SpotifyTrack> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return likedTracks.value.asSequence()
            .filter { track ->
                track.name.lowercase().contains(needle) ||
                    track.artists.any { it.name.lowercase().contains(needle) } ||
                    track.album?.name?.lowercase()?.contains(needle) == true
            }
            .take(limit)
            .toList()
    }

    fun searchEntries(query: String, kinds: Set<SpotifyLibraryEntry.Kind>, limit: Int): List<SpotifyLibraryEntry> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return _entries.value.asSequence()
            .filter { it.kind in kinds }
            .filter { it.name.lowercase().contains(needle) || it.creator?.lowercase()?.contains(needle) == true }
            .take(limit)
            .toList()
    }
}
