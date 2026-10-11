/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.catalog.LikedSongsCache
import com.metrolist.spotify.models.SpotifyPaging
import com.metrolist.spotify.models.SpotifySavedTrack
import com.metrolist.spotify.models.SpotifyTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SpotifyLikedSongsViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    database: MusicDatabase,
) : ViewModel() {
    val mapper = SpotifyYouTubeMapper(database)

    init {
        com.metrolist.music.playback.LibraryRecents.touch("liked")
    }

    /** Liked tracks the account can't take (kept in the app alone), pointed to from this screen. */
    val localOnlyCount = database.likedSongs(com.metrolist.music.constants.SongSortType.CREATE_DATE, true)
        .map { songs -> com.metrolist.music.playback.LikeTarget.localOnlyAmong(database, songs.map { it.id }).size }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private val _tracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _total = MutableStateFlow(0)
    val total = _total.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    /**
     * True once every page of the list has been fetched. "Download all" waits for it: started
     * earlier, it only saw the first few hundred tracks and had to be pressed again and again.
     */
    private val _allLoaded = MutableStateFlow(false)
    val allLoaded = _allLoaded.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /**
     * True when the list stopped part-way: a page kept failing (weak network, an old phone). The
     * screen then says how much arrived and offers to go on instead of passing a part off as all.
     */
    private val _incomplete = MutableStateFlow(false)
    val incomplete = _incomplete.asStateFlow()

    /** Offset of the first page not loaded yet, where [resume] goes on from. */
    private var nextOffset = 0

    init {
        loadLikedSongs()
    }

    private val account: String get() = Catalog.source?.name ?: "none"

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            Catalog.invalidateCaches()
            // A complete list stays on screen until the new one is complete too.
            loadLikedSongsInternal(quiet = _allLoaded.value && _tracks.value.isNotEmpty())
            _isRefreshing.value = false
        }
    }

    /**
     * The kept list shows at once. Fresh, it is used as is; older, one request tells whether the
     * account changed, and only then the whole list is fetched again — behind the kept one.
     */
    private fun loadLikedSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            val cached = LikedSongsCache.get(account)
            if (cached == null || cached.tracks.isEmpty()) {
                _isLoading.value = true
                loadLikedSongsInternal(quiet = false)
                return@launch
            }
            _tracks.value = cached.tracks
            _total.value = cached.total
            _allLoaded.value = true
            _isLoading.value = false
            if (cached.ageMs < LikedSongsCache.FRESH_MS) return@launch
            if (!updateFromFirstPage(cached)) loadLikedSongsInternal(quiet = true)
        }
    }

    /**
     * Compares the account's first page with the kept list: unchanged, or only new likes on top
     * (added right here), needs no more requests. False when the whole list must be fetched again.
     */
    private suspend fun updateFromFirstPage(cached: LikedSongsCache.Cached): Boolean {
        // No network: the kept list is all there is; asked again on the next opening.
        val page = Catalog.likedSongs(limit = PAGE_SIZE, offset = 0).getOrNull() ?: return true
        val first = page.items.map { it.track }.filter { !it.isLocal }
        val firstIds = first.map { it.id }
        val keptIds = cached.tracks.map { it.id }
        val added = page.total - cached.total
        return when {
            added == 0 && keptIds.take(firstIds.size) == firstIds -> {
                LikedSongsCache.touch(account)
                true
            }
            added in 1..firstIds.size && keptIds.take(firstIds.size - added) == firstIds.drop(added) -> {
                val updated = first.take(added) + cached.tracks
                _tracks.value = updated
                _total.value = page.total
                LikedSongsCache.put(account, updated, page.total)
                true
            }
            else -> false
        }
    }

    /** [quiet]: the kept list stays as is until the new one is complete, and a failure says nothing. */
    private suspend fun loadLikedSongsInternal(quiet: Boolean) {
        _error.value = null
        if (!quiet) {
            _allLoaded.value = false
            _incomplete.value = false
        }
        val complete = try {
            loadAllPages(quiet)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Liked songs loading stopped")
            false
        }
        if (quiet) return
        _incomplete.value = !complete
        _allLoaded.value = complete
    }

    /** Goes on loading from the first missing page, keeping what already arrived. */
    fun resume() {
        if (!_incomplete.value) return
        _incomplete.value = false
        viewModelScope.launch(Dispatchers.IO) {
            val complete = try {
                loadRemainingPages(_tracks.value.toMutableList(), _total.value, emit = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Liked songs loading stopped")
                false
            }
            _incomplete.value = !complete
            _allLoaded.value = complete
        }
    }

    /** A page, asked again a couple of times before giving up: one dropped request used to end the list. */
    private suspend fun pageWithRetries(offset: Int): Result<SpotifyPaging<SpotifySavedTrack>> {
        var result = Catalog.likedSongs(limit = PAGE_SIZE, offset = offset)
        var attempt = 0
        while (result.isFailure && attempt < PAGE_RETRIES) {
            delay(1_000L shl attempt)
            attempt++
            result = Catalog.likedSongs(limit = PAGE_SIZE, offset = offset)
        }
        return result
    }

    /** Loads every page; false when it had to stop before the end. */
    private suspend fun loadAllPages(quiet: Boolean): Boolean {
        var complete = true
        // Liked songs of the connected account(s): Spotify, Yandex Music, or both merged without duplicates.
        pageWithRetries(offset = 0).onSuccess { paging ->
            val firstPage = paging.items
                .map { it.track }
                .filter { !it.isLocal }

            if (!quiet) {
                // Emit first page immediately so the UI shows content within ~500ms
                _total.value = paging.total
                _tracks.value = firstPage
                _isLoading.value = false
            }

            nextOffset = PAGE_SIZE
            complete = loadRemainingPages(firstPage.toMutableList(), paging.total, emit = !quiet)
        }.onFailure { e ->
            complete = false
            Timber.e(e, "Failed to load Spotify liked songs")
            if (!quiet) {
                _error.value = e.message ?: "Failed to load liked songs"
                _isLoading.value = false
            }
        }
        return complete
    }

    /**
     * Fetches the pages from [nextOffset] on, a few in parallel, adding them strictly in order: a
     * failed page stops the list right there (pages after it are dropped and asked for again on
     * [resume]), so it never has gaps. Returns false when it stopped early. A complete list is kept
     * for the next opening; [emit] shows the pages as they arrive.
     */
    private suspend fun loadRemainingPages(allTracks: MutableList<SpotifyTrack>, total: Int, emit: Boolean): Boolean {
        while (nextOffset < total) {
            val offsets = (0 until PARALLEL_GROUP_SIZE)
                .map { nextOffset + it * PAGE_SIZE }
                .filter { it < total }
            val results = coroutineScope {
                offsets.map { offset -> async { pageWithRetries(offset) } }.awaitAll()
            }
            for (result in results) {
                val page = result.getOrElse { e ->
                    Timber.e(e, "Failed to load liked songs page at $nextOffset")
                    if (emit) _tracks.value = allTracks.toList()
                    return false
                }
                allTracks.addAll(page.items.map { it.track }.filter { !it.isLocal })
                nextOffset += PAGE_SIZE
            }
            if (emit) _tracks.value = allTracks.toList()
        }
        Timber.d("SpotifyLikedSongs: Loaded ${allTracks.size} tracks (total=$total)")
        _tracks.value = allTracks.toList()
        _total.value = total
        LikedSongsCache.put(account, allTracks.toList(), total)
        return true
    }

    fun retry() = loadLikedSongs()

    /**
     * Removes a track from the linked Spotify account's Liked Songs. Optimistically drops it from
     * the list so the heart/row disappears immediately; on failure it is restored on next refresh.
     */
    fun unlike(track: SpotifyTrack) {
        val previous = _tracks.value
        _tracks.value = previous.filterNot { it.id == track.id }
        _total.value = (_total.value - 1).coerceAtLeast(0)
        viewModelScope.launch(Dispatchers.IO) {
            Catalog.removeTrack(track.id).onFailure { e ->
                Timber.e(e, "Failed to unlike Spotify track ${track.id}")
                // Restore on failure.
                _tracks.value = previous
                _total.value = previous.size
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 50
        /** How many pages to fetch in parallel per batch. Keeps rate-limit risk low. */
        private const val PARALLEL_GROUP_SIZE = 5
        /** Extra tries per page (after 1 s, then 2 s). */
        private const val PAGE_RETRIES = 2
    }
}
