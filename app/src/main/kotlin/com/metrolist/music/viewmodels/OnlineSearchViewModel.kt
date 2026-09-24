/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.models.filterYoutubeShorts
import com.metrolist.innertube.pages.SearchSummary
import com.metrolist.innertube.pages.SearchSummaryPage
import com.metrolist.music.constants.EnableSpotifyKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HideYoutubeShortsKey
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.utils.SpotifyTokenManager
import com.metrolist.music.constants.UseSpotifySearchKey
import com.metrolist.music.models.ItemsPage
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import com.metrolist.music.utils.toAlbumItem
import com.metrolist.music.utils.toArtistItem
import com.metrolist.music.utils.toPlaylistItem
import com.metrolist.music.utils.toSongItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.music.catalog.Catalog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class OnlineSearchViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    savedStateHandle: SavedStateHandle,
    database: MusicDatabase,
) : ViewModel() {

    val spotifyYouTubeMapper = SpotifyYouTubeMapper(database)
    val query = try {
        URLDecoder.decode(savedStateHandle.get<String>("query")!!, "UTF-8")
    } catch (e: IllegalArgumentException) {
        savedStateHandle.get<String>("query")!!
    }
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()

    /**
     * Whether this search is using Spotify as its source.
     * Exposed to the UI so it can show the correct filter chips.
     */
    val isSpotifySearch = MutableStateFlow(false)

    /** Which catalog answers this search: Spotify, or Yandex Music when that was picked. */
    val searchTarget = MutableStateFlow(com.metrolist.music.constants.MetadataSource.SPOTIFY)

    /**
     * Spotify-specific filter: maps to the API "type" parameter.
     * null = show all types (summary mode)
     */
    val spotifyFilter = MutableStateFlow<String?>(null)

    private suspend fun loadSummaryPage() {
        if (summaryPage == null) {
            YouTube
                .searchSummary(query)
                .onSuccess {
                    val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                    val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
                    val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
                    summaryPage =
                        it.filterExplicit(hideExplicit)
                          .filterVideoSongs(hideVideoSongs)
                          .filterYoutubeShorts(hideYoutubeShorts)
                }.onFailure {
                    reportException(it)
                }
        }
    }


    init {
        viewModelScope.launch(Dispatchers.IO) {
            val useSpotify = shouldUseSpotifySearch()
            isSpotifySearch.value = useSpotify

            if (useSpotify) {
                initSpotifySearch()
            } else {
                initYouTubeSearch()
            }
        }
    }

    /**
     * Catalog search (Spotify and/or Yandex Music, merged without duplicates) whenever a music account
     * owns the metadata: always for Yandex Music, and for Spotify when "use Spotify for search" is on.
     */
    private suspend fun shouldUseSpotifySearch(): Boolean {
        val prefs = context.dataStore.data.first()
        // "YouTube Music" picked in the search box wins over the connected account: it is how
        // artists that exist on no streaming service are found.
        val source = prefs[com.metrolist.music.constants.SearchSourceKey]
            ?.let { name -> com.metrolist.music.constants.SearchSource.entries.firstOrNull { it.name == name } }
        if (source == com.metrolist.music.constants.SearchSource.YOUTUBE) return false
        searchTarget.value = if (
            source == com.metrolist.music.constants.SearchSource.YANDEX && Catalog.state.value.yandexConnected
        ) {
            com.metrolist.music.constants.MetadataSource.YANDEX
        } else {
            com.metrolist.music.constants.MetadataSource.SPOTIFY
        }
        // With no account at all, Spotify's public catalog is still searched (anonymously) — it is
        // the better search, and YouTube is one tap away in the picker. initSpotifySearch() falls
        // back to YouTube by itself if that doesn't work out.
        return true
    }

    private fun initYouTubeSearch() {
        viewModelScope.launch {
            filter.collect { filter ->
                if (filter == null) {
                    loadSummaryPage()
                } else if (filter == YouTube.SearchFilter.FILTER_EPISODE) {
                    // The FILTER_EPISODE API returns episodes in a format that differs from the
                    // summary search: playlistItemData is absent and the subtitle structure is
                    // different, making reliable isEpisode detection fail for many items.
                    // Reuse the "Episodes" section from the summary page instead — it is already
                    // parsed correctly by fromMusicResponsiveListItemRenderer and guaranteed to
                    // show the same results as the episodes section in the "All" filter.
                    if (viewStateMap[filter.value] == null) {
                        loadSummaryPage()
                        summaryPage?.let { page ->
                            val episodes = page.summaries
                                .firstOrNull { it.title == "Episodes" }
                                ?.items
                                .orEmpty()
                            viewStateMap[filter.value] = ItemsPage(episodes, null)
                        }
                    }
                } else {
                    if (viewStateMap[filter.value] == null) {
                        YouTube
                            .search(query, filter)
                            .onSuccess { result ->
                                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                                val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
                                val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
                                viewStateMap[filter.value] =
                                    ItemsPage(
                                        result.items
                                            .distinctBy { it.id }
                                            .filterExplicit(hideExplicit)
                                            .filterVideoSongs(hideVideoSongs)
                                            .filterYoutubeShorts(hideYoutubeShorts),
                                        result.continuation,
                                    )
                            }.onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    private fun initSpotifySearch() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!Catalog.ensureSearchable(searchTarget.value)) {
                Timber.w("SearchVM: Spotify auth failed, falling back to YouTube")
                isSpotifySearch.value = false
                initYouTubeSearch()
                return@launch
            }

            // Load summary (all types) immediately
            loadSpotifySummary()

            // Observe Spotify filter changes for filtered searches
            spotifyFilter.collect { filterType ->
                if (filterType != null) {
                    loadSpotifyFiltered(filterType)
                }
            }
        }
    }

    /**
     * Relevance: results are ordered by how literally they match the query (exact name > prefix >
     * whole word > substring) and unrelated "similar" items are dropped when real matches exist —
     * see [com.metrolist.spotify.SearchRelevance].
     */
    private fun List<com.metrolist.spotify.models.SpotifyTrack>.ranked() =
        com.metrolist.spotify.SearchRelevance.rankTracks(query, this)

    private fun List<com.metrolist.spotify.models.SpotifyAlbum>.rankedAlbums() =
        com.metrolist.spotify.SearchRelevance.rankAlbums(query, this)

    private fun List<com.metrolist.spotify.models.SpotifyArtist>.rankedArtists() =
        com.metrolist.spotify.SearchRelevance.rankArtists(query, this)

    private fun List<com.metrolist.spotify.models.SpotifyPlaylist>.rankedPlaylists() =
        com.metrolist.spotify.SearchRelevance.rankPlaylists(query, this)

    private suspend fun loadSpotifySummary() {
        if (summaryPage != null) return

        val hideExplicit = context.dataStore.get(HideExplicitKey, false)

        // Try full search first; if deserialization fails (e.g. null playlist items),
        // retry without playlists as a fallback
        val result = Catalog.searchIn(
            target = searchTarget.value,
            query = query,
            types = listOf("track", "album", "artist", "playlist"),
            limit = 10,
        ).getOrElse { firstError ->
            Timber.w(firstError, "SearchVM: Full Spotify search failed, retrying without playlists")
            Catalog.searchIn(
            target = searchTarget.value,
                query = query,
                types = listOf("track", "album", "artist"),
                limit = 10,
            ).getOrElse { secondError ->
                Timber.e(secondError, "SearchVM: Spotify search failed completely")
                reportException(secondError)
                return
            }
        }

        val summaries = mutableListOf<SearchSummary>()

        result.tracks?.items?.filter { it.id.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }?.let { tracks ->
                val items: List<YTItem> = tracks
                    .filter { !hideExplicit || !it.explicit }
                    .ranked()
                    .map { it.toSongItem() }
                if (items.isNotEmpty()) summaries.add(SearchSummary(title = "Songs", items = items))
            }
        result.albums?.items?.filter { it.id.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }?.let { albums ->
                // "Albums" should mean albums — Spotify lumps singles/EPs/compilations under the same
                // album type, so split them out into their own section (matches the artist screen).
                // A real "Album" is album_type == "album" with more than one track. Spotify tags
                // some 1-track releases as albums; those belong under Singles & EPs. total_tracks == 0
                // means "unknown" (not returned by this payload) — keep those in Albums, don't drop.
                val (realAlbums, otherReleases) = albums.rankedAlbums().partition {
                    it.albumType.equals("album", ignoreCase = true) && it.totalTracks != 1
                }
                if (realAlbums.isNotEmpty()) {
                    summaries.add(SearchSummary(title = "Albums", items = realAlbums.map { it.toAlbumItem() }))
                }
                if (otherReleases.isNotEmpty()) {
                    summaries.add(SearchSummary(title = "Singles & EPs", items = otherReleases.map { it.toAlbumItem() }))
                }
            }
        result.artists?.items?.filter { it.id.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }?.let { artists ->
                val rankedArtists = artists.rankedArtists()
                val items: List<YTItem> = rankedArtists.map { it.toArtistItem() }
                if (items.isNotEmpty()) {
                    // Typing an artist's exact name shows that artist first, above songs.
                    val section = SearchSummary(title = "Artists", items = items)
                    if (com.metrolist.spotify.SearchRelevance.isArtistQuery(query, rankedArtists)) {
                        summaries.add(0, section)
                    } else {
                        summaries.add(section)
                    }
                }
            }
        result.playlists?.items?.filter { it.id.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }?.let { playlists ->
                val items: List<YTItem> = playlists.rankedPlaylists().map { it.toPlaylistItem() }
                if (items.isNotEmpty()) summaries.add(SearchSummary(title = "Playlists", items = items))
            }

        summaryPage = SearchSummaryPage(summaries = summaries)
    }

    private suspend fun loadSpotifyFiltered(filterType: String) {
        if (viewStateMap[filterType] != null) return

        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
        val offset = 0
        val limit = 20

        Catalog.searchIn(
            target = searchTarget.value,
            query = query,
            types = listOf(filterType),
            limit = limit,
            offset = offset,
        ).onSuccess { result ->
            val items: List<YTItem> = when (filterType) {
                "track" -> result.tracks?.items
                    ?.filter { !hideExplicit || !it.explicit }
                    ?.ranked()
                    ?.map { it.toSongItem() } ?: emptyList()
                "album" -> result.albums?.items?.rankedAlbums()?.map { it.toAlbumItem() } ?: emptyList()
                "artist" -> result.artists?.items?.rankedArtists()?.map { it.toArtistItem() } ?: emptyList()
                "playlist" -> result.playlists?.items?.rankedPlaylists()?.map { it.toPlaylistItem() } ?: emptyList()
                else -> emptyList()
            }

            // Spotify paging: if we got limit items, there are likely more
            val hasMore = when (filterType) {
                "track" -> (result.tracks?.items?.size ?: 0) >= limit
                "album" -> (result.albums?.items?.size ?: 0) >= limit
                "artist" -> (result.artists?.items?.size ?: 0) >= limit
                "playlist" -> (result.playlists?.items?.size ?: 0) >= limit
                else -> false
            }

            viewStateMap[filterType] = ItemsPage(
                items = items.distinctBy { it.id },
                // Encode offset in continuation string for Spotify pagination
                continuation = if (hasMore) "spotify:$filterType:${offset + limit}" else null,
            )
        }.onFailure {
            Timber.e(it, "SearchVM: Spotify filtered search failed for type=$filterType")
            reportException(it)
        }
    }

    fun loadMore() {
        if (isSpotifySearch.value) {
            loadMoreSpotify()
        } else {
            loadMoreYouTube()
        }
    }

    private fun loadMoreYouTube() {
        val filterValue = filter.value?.value ?: return
        viewModelScope.launch {
            val viewState = viewStateMap[filterValue] ?: return@launch
            val continuation = viewState.continuation ?: return@launch
            val searchResult =
                YouTube.searchContinuation(continuation).getOrNull() ?: return@launch
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
            val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
            val newItems = searchResult.items
                .filterExplicit(hideExplicit)
                .filterVideoSongs(hideVideoSongs)
                .filterYoutubeShorts(hideYoutubeShorts)
            viewStateMap[filterValue] = ItemsPage(
                (viewState.items + newItems).distinctBy { it.id },
                searchResult.continuation
            )
        }
    }

    private fun loadMoreSpotify() {
        val filterType = spotifyFilter.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val viewState = viewStateMap[filterType] ?: return@launch
            val continuation = viewState.continuation ?: return@launch

            // Parse continuation: "spotify:type:offset"
            val parts = continuation.split(":")
            if (parts.size != 3) return@launch
            val offset = parts[2].toIntOrNull() ?: return@launch
            val limit = 20
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)

            if (!Catalog.ensureSearchable(searchTarget.value)) return@launch

            Catalog.searchIn(
            target = searchTarget.value,
                query = query,
                types = listOf(filterType),
                limit = limit,
                offset = offset,
            ).onSuccess { result ->
                val newItems: List<YTItem> = when (filterType) {
                    "track" -> result.tracks?.items
                        ?.filter { !hideExplicit || !it.explicit }
                        ?.ranked()
                        ?.map { it.toSongItem() } ?: emptyList()
                    "album" -> result.albums?.items?.rankedAlbums()?.map { it.toAlbumItem() } ?: emptyList()
                    "artist" -> result.artists?.items?.rankedArtists()?.map { it.toArtistItem() } ?: emptyList()
                    "playlist" -> result.playlists?.items?.rankedPlaylists()?.map { it.toPlaylistItem() } ?: emptyList()
                    else -> emptyList()
                }

                val hasMore = when (filterType) {
                    "track" -> (result.tracks?.items?.size ?: 0) >= limit
                    "album" -> (result.albums?.items?.size ?: 0) >= limit
                    "artist" -> (result.artists?.items?.size ?: 0) >= limit
                    "playlist" -> (result.playlists?.items?.size ?: 0) >= limit
                    else -> false
                }

                viewStateMap[filterType] = ItemsPage(
                    (viewState.items + newItems).distinctBy { it.id },
                    if (hasMore) "spotify:$filterType:${offset + limit}" else null,
                )
            }.onFailure {
                Timber.e(it, "SearchVM: Spotify loadMore failed")
                reportException(it)
            }
        }
    }

}
