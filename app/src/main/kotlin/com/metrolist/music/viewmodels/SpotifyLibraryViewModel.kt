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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Backs the Spotify-style "My Library": Spotify's own order (pinned → Recents), filters and sort. */
@HiltViewModel
class SpotifyLibraryViewModel @Inject constructor(
    private val database: MusicDatabase,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    private val likedSongsTitle = context.getString(com.metrolist.music.R.string.liked_songs)

    enum class Filter(val gql: String?) { ALL(null), PLAYLISTS("Playlists"), ALBUMS("Albums"), ARTISTS("Artists") }
    /** Spotify's four orders, plus release year for albums; each can be flipped ([reversed]). */
    enum class Sort { RECENTS, DATE_ADDED, NAME, CREATOR, YEAR }

    private val _filter = MutableStateFlow(Filter.ALL)
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    private val prefs = context.getSharedPreferences("library_sort", android.content.Context.MODE_PRIVATE)

    private val _sort = MutableStateFlow(
        prefs.getString("sort", null)?.let { runCatching { Sort.valueOf(it) }.getOrNull() } ?: Sort.RECENTS,
    )
    val sort: StateFlow<Sort> = _sort.asStateFlow()

    private val _reversed = MutableStateFlow(prefs.getBoolean("reversed", false))
    val reversed: StateFlow<Boolean> = _reversed.asStateFlow()

    /** With the Year order on Albums: only albums released in this year (null = every year). */
    private val _yearFilter = MutableStateFlow<Int?>(null)
    val yearFilter: StateFlow<Int?> = _yearFilter.asStateFlow()

    /**
     * Release years read album by album, for libraries that don't tell them with the list (Spotify's
     * may not): album id → year. Asked only while the Year order is on, a few at a time.
     */
    private val fetchedYears = MutableStateFlow<Map<String, Int>>(emptyMap())
    private var yearJob: Job? = null

    /**
     * The account this library shows: null = the main one; another linked account when the
     * library is that account's own tab. Set once by the screen ([start]) before anything loads.
     */
    private val _tabSource = MutableStateFlow<com.metrolist.music.constants.MetadataSource?>(null)
    private val isTab: Boolean get() = _tabSource.value != null

    /** The account the lists come from right now. */
    private fun accountSource(): com.metrolist.music.constants.MetadataSource? = _tabSource.value ?: Catalog.source

    @Volatile
    private var started = false

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

    /**
     * Without a music account the same library shows what is saved in the app itself: liked songs,
     * saved albums and followed artists (with an account those come from the account). With one, it
     * shows what the account's catalog doesn't have (see [com.metrolist.music.catalog.CrossCatalog]):
     * the app's liked songs when some of them are missing there, and such artists, marked so.
     */
    private val appCollection: StateFlow<List<SpotifyLibraryEntry>> =
        combine(
            Catalog.state,
            database.likedSongsCount(),
            database.albumsLiked(com.metrolist.music.constants.AlbumSortType.CREATE_DATE, true),
            // All followed artists, Spotify ones too (artistsBookmarked() keeps YouTube and local ones only).
            database.artistsBookmarkedByCreateDateAsc().map { it.reversed() },
            com.metrolist.music.catalog.CrossCatalog.missing,
        ) { catalog, likedCount, albums, artists, missing ->
            if (catalog.isActive) return@combine notInAccount(catalog.source, likedCount, artists, missing)
            buildList {
                add(
                    SpotifyLibraryEntry(
                        kind = SpotifyLibraryEntry.Kind.LIKED_SONGS,
                        id = "liked",
                        uri = APP_LIKED_URI,
                        name = likedSongsTitle,
                        totalCount = likedCount,
                        pinned = true,
                    ),
                )
                albums.forEach { album ->
                    add(
                        SpotifyLibraryEntry(
                            kind = SpotifyLibraryEntry.Kind.ALBUM,
                            id = album.album.id,
                            uri = APP_ALBUM_URI_PREFIX + album.album.id,
                            name = album.album.title,
                            creator = album.artists.joinToString { it.name }.ifBlank { null },
                            imageUrl = album.album.thumbnailUrl,
                            addedAt = (album.album.bookmarkedAt ?: album.album.likedDate)?.toString(),
                            year = album.album.year,
                        ),
                    )
                }
                artists.forEach { artist ->
                    add(
                        SpotifyLibraryEntry(
                            kind = SpotifyLibraryEntry.Kind.ARTIST,
                            id = artist.artist.id,
                            uri = APP_ARTIST_URI_PREFIX + artist.artist.id,
                            name = artist.artist.name,
                            imageUrl = artist.artist.thumbnailUrl,
                            addedAt = artist.artist.bookmarkedAt?.toString(),
                        ),
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * With an account: every like made in the app ("All liked songs", the account's own list comes
     * from the account), saying how many of them the account doesn't have, and followed artists the
     * account's catalog doesn't have, marked so.
     */
    private fun notInAccount(
        source: com.metrolist.music.constants.MetadataSource?,
        likedCount: Int,
        artists: List<com.metrolist.music.db.entities.Artist>,
        missing: Set<String>,
    ): List<SpotifyLibraryEntry> {
        val mark = context.getString(
            when (source) {
                com.metrolist.music.constants.MetadataSource.YANDEX -> com.metrolist.music.R.string.not_in_yandex
                com.metrolist.music.constants.MetadataSource.VK -> com.metrolist.music.R.string.not_in_vk
                else -> com.metrolist.music.R.string.not_in_spotify
            },
        )
        val missingArtists = artists.filter { a ->
            com.metrolist.music.catalog.CrossCatalog.catalogArtistId(a.artist)?.let { "a:$it" in missing } == true
        }
        return buildList {
            // Every heart, on the account or not: with Spotify the only liked list, with Yandex Music
            // beside the account's own (asked for on 2026-11-04).
            if (likedCount > 0) {
                add(
                    SpotifyLibraryEntry(
                        kind = SpotifyLibraryEntry.Kind.LIKED_SONGS,
                        id = "app_liked",
                        uri = APP_LIKED_URI,
                        name = context.getString(com.metrolist.music.R.string.liked_songs),
                        totalCount = likedCount,
                        pinned = true,
                    ),
                )
            }
            missingArtists.forEach { artist ->
                add(
                    SpotifyLibraryEntry(
                        kind = SpotifyLibraryEntry.Kind.ARTIST,
                        id = artist.artist.id,
                        uri = APP_ARTIST_URI_PREFIX + artist.artist.id,
                        name = artist.artist.name,
                        creator = mark,
                        imageUrl = artist.artist.thumbnailUrl,
                        addedAt = artist.artist.bookmarkedAt?.toString(),
                    ),
                )
            }
        }
    }

    /** Every album entry before the year filter, years filled in where they were read later. */
    private val unfilteredEntries: StateFlow<List<SpotifyLibraryEntry>> =
        combine(
            combine(cache, com.metrolist.music.playback.LibraryRecents.version, ::Pair),
            _filter,
            combine(_sort, fetchedYears, ::Pair),
            _reversed,
            combine(localPlaylists, appCollection, _tabSource, ::Triple),
        ) { (c, _), f, (s, years), reversed, (localAll, appAll, tab) ->
            // An account's own tab shows that account only: the app's playlists and likes belong to the main library.
            val local = if (tab != null) emptyList() else localAll
            val app = if (tab != null) emptyList() else appAll
            val own = if (f == Filter.ALL || f == Filter.PLAYLISTS) local else emptyList()
            val saved = app.filter { entry ->
                when (f) {
                    Filter.ALL -> true
                    Filter.PLAYLISTS -> entry.kind == SpotifyLibraryEntry.Kind.LIKED_SONGS
                    Filter.ALBUMS -> entry.kind == SpotifyLibraryEntry.Kind.ALBUM
                    Filter.ARTISTS -> entry.kind == SpotifyLibraryEntry.Kind.ARTIST
                }
            }
            // Spotify's own "Liked songs" is left out: the app's "All liked songs" holds them (and
            // the likes made in the app). Yandex Music's stays: what is on the account, beside all.
            val yandex = tab != null || Catalog.state.value.source != com.metrolist.music.constants.MetadataSource.SPOTIFY
            val account = c[f].orEmpty().filterNot { e ->
                !yandex && e.kind == SpotifyLibraryEntry.Kind.LIKED_SONGS && e.uri != APP_LIKED_URI
            }
            // Spotify keeps a second album id for some re-delivered releases; both can end up saved.
            val merged = (saved + account).distinctBy { e ->
                if (e.kind == SpotifyLibraryEntry.Kind.ALBUM) "album:" + e.name.trim().lowercase() + "|" + e.creator.orEmpty().trim().lowercase() else e.kind.name + e.id
            }
            val withYears = merged.map { e ->
                if (e.kind == SpotifyLibraryEntry.Kind.ALBUM && e.year == null) years[e.id]?.let { e.copy(year = it) } ?: e else e
            }
            // The Year order belongs to Albums; elsewhere the list keeps the usual recents order.
            val effective = if (s == Sort.YEAR && f != Filter.ALBUMS) Sort.RECENTS else s
            applySort(own, withYears, effective, reversed)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Whether the Year order is in effect (it is offered on Albums only). */
    val yearSortActive: StateFlow<Boolean> =
        combine(_sort, _filter) { s, f -> s == Sort.YEAR && f == Filter.ALBUMS }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The years the user's albums come from, newest first: the choices of the year filter. */
    val availableYears: StateFlow<List<Int>> =
        combine(unfilteredEntries, yearSortActive) { list, active ->
            if (!active) emptyList() else list.mapNotNull { it.year }.distinct().sortedDescending()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val entries: StateFlow<List<SpotifyLibraryEntry>> =
        combine(unfilteredEntries, yearSortActive, _yearFilter) { list, active, year ->
            if (!active || year == null) list else list.filter { it.year == year }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setYearFilter(year: Int?) {
        _yearFilter.value = year
    }

    /** Reads the release years the library didn't bring, while the Year order is on. */
    private fun fillYears() {
        if (yearJob?.isActive == true) return
        yearJob = viewModelScope.launch(Dispatchers.IO) {
            val missing = unfilteredEntries.value
                .filter { it.kind == SpotifyLibraryEntry.Kind.ALBUM && it.year == null && it.id.isNotBlank() }
                .filterNot { it.uri.startsWith(APP_ALBUM_URI_PREFIX) || it.id in fetchedYears.value }
                .map { it.id }
                .distinct()
            if (missing.isEmpty()) return@launch
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            missing.chunked(12).forEach { chunk ->
                val found = kotlinx.coroutines.coroutineScope {
                    chunk.map { id ->
                        async {
                            gate.acquire()
                            try {
                                id to Catalog.album(id).getOrNull()?.releaseDate?.take(4)?.toIntOrNull()
                            } finally {
                                gate.release()
                            }
                        }
                    }.map { it.await() }
                }
                val add = found.mapNotNull { (id, year) -> year?.let { id to it } }.toMap()
                if (add.isNotEmpty()) fetchedYears.value = fetchedYears.value + add
            }
        }
    }

    private fun accountLikedTitle() = context.getString(com.metrolist.music.ui.component.catalogLikedSongsTitleRes())

    /**
     * Starts the library for the main account ([tab] null) or for another linked account's tab.
     * Called by the screen once; the lists load only then, so a tab never flashes the main library.
     */
    fun start(tab: com.metrolist.music.constants.MetadataSource?) {
        if (started) return
        started = true
        _tabSource.value = tab
        load(Filter.ALL)
        if (tab != null) return
        viewModelScope.launch(Dispatchers.IO) { com.metrolist.music.catalog.CrossCatalog.fillArtistPhotos(database) }
        // What was liked or followed in the app before goes to the account too, once per account.
        viewModelScope.launch(Dispatchers.IO) { com.metrolist.music.catalog.CrossCatalog.syncApp(database) }
    }

    init {
        // The Year order on Albums: the years the library didn't bring are read in the background.
        viewModelScope.launch {
            combine(yearSortActive, unfilteredEntries.map { it.size }, ::Pair).collect { (active, _) ->
                if (active) fillYears() else _yearFilter.value = null
            }
        }
        // A like, a follow or a saved album (here or on any other screen) shows in the list at once,
        // not when the saved copy grows old.
        viewModelScope.launch {
            Catalog.libraryVersion.drop(1).collect {
                if (!started) return@collect
                cache.value = cache.value.filterKeys { it == _filter.value }
                load(_filter.value, force = true)
            }
        }
    }

    /** Tapping the selected chip again clears it (back to everything), like the Spotify client. */
    fun toggleFilter(f: Filter) {
        val next = if (_filter.value == f) Filter.ALL else f
        _filter.value = next
        if (cache.value[next] == null && started) load(next)
    }

    fun clearFilter() {
        _filter.value = Filter.ALL
    }

    /** Another order starts from its usual direction; the arrow next to it flips it. */
    fun setSort(s: Sort) {
        if (_sort.value != s) {
            _sort.value = s
            _reversed.value = false
            save()
        }
    }

    fun toggleReversed() {
        _reversed.value = !_reversed.value
        save()
    }

    private fun save() {
        prefs.edit().putString("sort", _sort.value.name).putBoolean("reversed", _reversed.value).apply()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun refresh() {
        // Reloads through the version watch in init; the list stays on screen while the new one loads.
        Catalog.invalidateCaches()
    }

    private fun load(f: Filter, force: Boolean = false) {
        if (!force && loadJob?.isActive == true && loadingFilter == f) return
        // Only the chip on screen loads: an earlier one still running used to switch the spinner
        // off when it finished, and Artists then sat empty with no sign it was still loading.
        loadJob?.cancel()
        _loading.value = false
        loadingFilter = f
        val job = viewModelScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            // What was loaded last time shows at once; a fresh copy needs no request at all.
            val source = accountSource()
            val account = source?.name ?: "none"
            val cached = com.metrolist.music.catalog.LibraryCache.get(f.name, account)
            if (cached != null) {
                cache.value = cache.value + (f to cached.first)
                if (!force && cached.second < com.metrolist.music.catalog.LibraryCache.FRESH_MS) return@launch
            }
            // The spinner only while there is nothing to show; a refresh behind the list is silent.
            _loading.value = cached == null
            try {
                // The library of the connected account(s): Spotify, Yandex Music or both merged.
                // Without one there is nothing to fetch (the app's own collection is local).
                if (source == null || !Catalog.ensureAuthenticated(source)) return@launch
                val all = mutableListOf<SpotifyLibraryEntry>()
                var offset = 0
                while (offset < MAX_ITEMS) {
                    val page = Catalog.myLibraryOf(source, filter = f.gql, limit = PAGE, offset = offset).getOrElse {
                        Timber.w(it, "SpotifyLibrary: page failed (filter=$f offset=$offset)")
                        null
                    } ?: break
                    all += page.items
                    offset += PAGE
                    if (page.items.isEmpty() || offset >= page.total) break
                }
                // Cut short (another chip opened meanwhile): what was read is incomplete and must not
                // replace the saved library — a cancelled load used to save a half list, and saved
                // albums disappeared from the library.
                kotlinx.coroutines.yield()
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
                // A failed refresh (no network) keeps the previous copy instead of an empty library.
                if (cleaned.isNotEmpty() || cached == null) {
                    cache.value = cache.value + (f to cleaned)
                    com.metrolist.music.catalog.LibraryCache.put(f.name, account, cleaned)
                }
            } finally {
                if (loadJob === coroutineContext[kotlinx.coroutines.Job]) _loading.value = false
            }
        }
        loadJob = job
        job.start()
    }

    /** The filter [loadJob] is loading. */
    @Volatile
    private var loadingFilter: Filter? = null

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
            // What was opened or played here most recently first; the rest in the account's own
            // order (its recently played / added), the app's playlists leading that part.
            Sort.RECENTS -> {
                val touched = com.metrolist.music.playback.LibraryRecents.snapshot()
                (own + rest).withIndex()
                    .sortedWith(
                        compareByDescending<IndexedValue<SpotifyLibraryEntry>> { touched[recentsKey(it.value)] ?: 0L }
                            .thenBy { it.index },
                    )
                    .map { it.value }
            }
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
            // Newest release first; within a year by artist, then title. Unknown years go last.
            Sort.YEAR -> {
                val (known, unknown) = (own + rest).partition { it.year != null }
                val byYear = known.sortedWith(
                    compareByDescending<SpotifyLibraryEntry> { it.year }
                        .thenBy(collator) { it.creator ?: it.name }
                        .thenBy(collator) { it.name },
                )
                return pinned + (if (reversed) byYear.asReversed() else byYear) + unknown
            }
        }
        return pinned + if (reversed) sorted.asReversed() else sorted
    }

    private fun recentsKey(entry: SpotifyLibraryEntry): String = when (entry.kind) {
        SpotifyLibraryEntry.Kind.ALBUM -> "album:" + entry.id
        SpotifyLibraryEntry.Kind.ARTIST -> "artist:" + entry.id
        SpotifyLibraryEntry.Kind.LIKED_SONGS -> "liked"
        else -> if (entry.uri.startsWith(LOCAL_PLAYLIST_URI_PREFIX)) "local:" + entry.id else "playlist:" + entry.id
    }

    companion object {
        /** Marks an entry that lives in this app's database, not in the connected account. */
        const val LOCAL_PLAYLIST_URI_PREFIX = "meld:playlist:"
        const val APP_LIKED_URI = "meld:liked"
        const val APP_ALBUM_URI_PREFIX = "meld:album:"
        const val APP_ARTIST_URI_PREFIX = "meld:artist:"

        private const val PAGE = 50
        private const val MAX_ITEMS = 1000
    }
}
