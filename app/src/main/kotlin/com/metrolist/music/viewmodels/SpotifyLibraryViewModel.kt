/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.constants.PlaylistSortType
import com.metrolist.music.db.MusicDatabase
import com.metrolist.spotify.models.SpotifyLibraryEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Backs the Spotify-style "My Library": Spotify's own order (pinned → Recents), filters and sort. */
@HiltViewModel
class SpotifyLibraryViewModel @Inject constructor(
    database: MusicDatabase,
    @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
) : ViewModel() {

    enum class Filter(val gql: String?) { ALL(null), PLAYLISTS("Playlists"), ALBUMS("Albums"), ARTISTS("Artists") }
    /** Each order can be flipped ([reversed]): newest/oldest first, A→Z/Z→A. */
    enum class Sort { DATE_ADDED, NAME, CREATOR }

    private val _filter = MutableStateFlow(Filter.ALL)
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    private val prefs = context.getSharedPreferences("library_sort", android.content.Context.MODE_PRIVATE)

    private val _sort = MutableStateFlow(
        prefs.getString("sort", null)?.let { runCatching { Sort.valueOf(it) }.getOrNull() } ?: Sort.DATE_ADDED,
    )
    val sort: StateFlow<Sort> = _sort.asStateFlow()

    private val _reversed = MutableStateFlow(prefs.getBoolean("reversed", false))
    val reversed: StateFlow<Boolean> = _reversed.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Raw entries per filter, kept so switching chips is instant after the first load. */
    private val cache = MutableStateFlow<Map<Filter, List<SpotifyLibraryEntry>>>(emptyMap())
    private var loadJob: Job? = null

    /**
     * Playlists made inside the app. They belong to no streaming account, so the library of the
     * connected account never mentions them — without this they would be created and then vanish.
     * They are shown first, above the account's own playlists.
     */
    private val localPlaylists: StateFlow<List<SpotifyLibraryEntry>> =
        combine(
            database.playlists(PlaylistSortType.CREATE_DATE, descending = true),
            database.playlistLatestThumbnails(),
        ) { playlists, covers ->
            val coverOf = covers.associate { it.playlistId to it.thumbnailUrl }
            playlists
                .filter { it.playlist.isEditable }
                .map { playlist ->
                    SpotifyLibraryEntry(
                        kind = SpotifyLibraryEntry.Kind.PLAYLIST,
                        id = playlist.id,
                        uri = LOCAL_PLAYLIST_URI_PREFIX + playlist.id,
                        name = playlist.playlist.name,
                        creator = null,
                        // The cover is the artwork of the track added last, nothing fancier.
                        imageUrl = playlist.playlist.thumbnailUrl
                            ?: coverOf[playlist.id]
                            ?: playlist.thumbnails.firstOrNull(),
                        totalCount = playlist.songCount,
                    )
                }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val entries: StateFlow<List<SpotifyLibraryEntry>> =
        combine(cache, _filter, _sort, _reversed, localPlaylists) { c, f, s, reversed, local ->
            val own = if (f == Filter.ALL || f == Filter.PLAYLISTS) local else emptyList()
            applySort(own, c[f].orEmpty(), s, reversed)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    /** Picking the current order again flips it; another order starts from its usual direction. */
    fun setSort(s: Sort) {
        if (_sort.value == s) {
            _reversed.value = !_reversed.value
        } else {
            _sort.value = s
            _reversed.value = false
        }
        prefs.edit().putString("sort", _sort.value.name).putBoolean("reversed", _reversed.value).apply()
    }

    fun toggleReversed() = setSort(_sort.value)

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
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

    private fun applySort(
        own: List<SpotifyLibraryEntry>,
        list: List<SpotifyLibraryEntry>,
        s: Sort,
        reversed: Boolean,
    ): List<SpotifyLibraryEntry> {
        // Pinned items always stay on top, in the account's order.
        val (pinned, rest) = list.partition { it.pinned }
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
        val sorted = when (s) {
            // Newest first. Entries without a date keep the account's own order (already newest
            // first); the app's own playlists, newest first as well, lead.
            Sort.DATE_ADDED -> own + rest.withIndex()
                .sortedWith(
                    compareByDescending<IndexedValue<SpotifyLibraryEntry>> { it.value.addedAt.orEmpty() }
                        .thenBy { it.index },
                )
                .map { it.value }
            Sort.NAME -> (own + rest).sortedWith(compareBy(collator) { it.name })
            Sort.CREATOR -> (own + rest).sortedWith(
                compareBy<SpotifyLibraryEntry, String>(collator) { it.creator ?: it.name }
                    .thenBy(collator) { it.name },
            )
        }
        return pinned + if (reversed) sorted.asReversed() else sorted
    }

    companion object {
        /** Marks an entry that lives in this app's database, not in the connected account. */
        const val LOCAL_PLAYLIST_URI_PREFIX = "meld:playlist:"

        private const val PAGE = 50
        private const val MAX_ITEMS = 1000
    }
}
