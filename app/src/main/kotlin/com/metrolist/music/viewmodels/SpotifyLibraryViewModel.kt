/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.catalog.Catalog
import com.metrolist.spotify.models.SpotifyLibraryEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Backs the Spotify-style "My Library": Spotify's own order (pinned → Recents), filters and sort. */
@HiltViewModel
class SpotifyLibraryViewModel @Inject constructor() : ViewModel() {

    enum class Filter(val gql: String?) { ALL(null), PLAYLISTS("Playlists"), ALBUMS("Albums"), ARTISTS("Artists") }
    enum class Sort { RECENTS, RECENTLY_ADDED, ALPHABETICAL, CREATOR }

    private val _filter = MutableStateFlow(Filter.ALL)
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    private val _sort = MutableStateFlow(Sort.RECENTS)
    val sort: StateFlow<Sort> = _sort.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Raw entries per filter, kept so switching chips is instant after the first load. */
    private val cache = MutableStateFlow<Map<Filter, List<SpotifyLibraryEntry>>>(emptyMap())
    private var loadJob: Job? = null

    val entries: StateFlow<List<SpotifyLibraryEntry>> =
        combine(cache, _filter, _sort) { c, f, s -> applySort(c[f].orEmpty(), s) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        load(Filter.ALL)
    }

    /** Tapping the selected chip again clears it (back to everything), like the Spotify client. */
    fun toggleFilter(f: Filter) {
        val next = if (_filter.value == f) Filter.ALL else f
        _filter.value = next
        if (cache.value[next] == null) load(next)
    }

    fun clearFilter() {
        _filter.value = Filter.ALL
    }

    fun setSort(s: Sort) {
        _sort.value = s
    }

    fun refresh() {
        Catalog.invalidateCaches()
        cache.value = emptyMap()
        load(_filter.value, force = true)
    }

    private fun load(f: Filter, force: Boolean = false) {
        if (!force && loadJob?.isActive == true && _filter.value == f) return
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            try {
                // The library of the connected account(s): Spotify, Yandex Music or both merged.
                if (!Catalog.ensureAuthenticated()) return@launch
                val all = mutableListOf<SpotifyLibraryEntry>()
                var offset = 0
                while (offset < MAX_ITEMS) {
                    val page = Catalog.myLibrary(filter = f.gql, limit = PAGE, offset = offset).getOrElse {
                        Timber.w(it, "SpotifyLibrary: page failed (filter=$f offset=$offset)")
                        null
                    } ?: break
                    all += page.items
                    offset += PAGE
                    if (page.items.isEmpty() || offset >= page.total) break
                }
                // The Liked Songs pseudo-playlist belongs to Playlists and "All" only.
                val cleaned = all.distinctBy { it.uri }.filter { e ->
                    when (f) {
                        Filter.ALL -> true
                        Filter.PLAYLISTS -> e.kind == SpotifyLibraryEntry.Kind.PLAYLIST ||
                            e.kind == SpotifyLibraryEntry.Kind.LIKED_SONGS ||
                            e.kind == SpotifyLibraryEntry.Kind.FOLDER
                        Filter.ALBUMS -> e.kind == SpotifyLibraryEntry.Kind.ALBUM
                        Filter.ARTISTS -> e.kind == SpotifyLibraryEntry.Kind.ARTIST
                    }
                }
                cache.value = cache.value + (f to cleaned)
            } finally {
                _loading.value = false
            }
        }
    }

    private fun applySort(list: List<SpotifyLibraryEntry>, s: Sort): List<SpotifyLibraryEntry> {
        // Pinned items always stay on top, in Spotify's order.
        val (pinned, rest) = list.partition { it.pinned }
        val sortedRest = when (s) {
            Sort.RECENTS -> rest
            Sort.RECENTLY_ADDED -> rest.sortedByDescending { it.addedAt.orEmpty() }
            Sort.ALPHABETICAL -> rest.sortedBy { it.name.lowercase() }
            Sort.CREATOR -> rest.sortedBy { (it.creator ?: it.name).lowercase() }
        }
        return pinned + sortedRest
    }

    private companion object {
        const val PAGE = 50
        const val MAX_ITEMS = 1000
    }
}
