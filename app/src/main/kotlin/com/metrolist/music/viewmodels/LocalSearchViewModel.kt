/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.LocalItem
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.Song
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocalSearchViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
) : ViewModel() {
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(LocalFilter.ALL)

    /** Resolves a catalog track to something playable when one is tapped in the results. */
    val mapper = com.metrolist.music.playback.SpotifyYouTubeMapper(database)

    /** Liked tracks of the connected account that match the query. */
    val catalogTracks = MutableStateFlow<List<com.metrolist.spotify.models.SpotifyTrack>>(emptyList())

    /** Saved playlists, albums and artists of the connected account that match the query. */
    val catalogEntries = MutableStateFlow<List<com.metrolist.spotify.models.SpotifyLibraryEntry>>(emptyList())

    init {
        // "Library" means the whole library: what is on the device AND what the linked music
        // account holds. The account can't search inside itself, so it is filtered here.
        viewModelScope.launch {
            combine(
                query,
                filter,
                com.metrolist.music.catalog.CatalogLibrarySearch.likedTracks,
                com.metrolist.music.catalog.CatalogLibrarySearch.entries,
            ) { q, f, _, _ -> q to f }
                .collectLatest { (q, f) ->
                    if (q.isBlank() || !com.metrolist.music.catalog.Catalog.isActive) {
                        catalogTracks.value = emptyList()
                        catalogEntries.value = emptyList()
                        return@collectLatest
                    }
                    // Kicks off loading if needed; the results below are computed from whatever is
                    // already in memory and recomputed by this very flow once more arrives.
                    com.metrolist.music.catalog.CatalogLibrarySearch.ensureLoaded()
                    val limit = if (f == LocalFilter.ALL) PREVIEW_SIZE else 50
                    catalogTracks.value = when (f) {
                        LocalFilter.ALL, LocalFilter.SONG ->
                            com.metrolist.music.catalog.CatalogLibrarySearch.searchTracks(q, limit)
                        else -> emptyList()
                    }
                    val kinds = when (f) {
                        LocalFilter.ALL -> setOf(
                            com.metrolist.spotify.models.SpotifyLibraryEntry.Kind.ALBUM,
                            com.metrolist.spotify.models.SpotifyLibraryEntry.Kind.ARTIST,
                            com.metrolist.spotify.models.SpotifyLibraryEntry.Kind.PLAYLIST,
                        )
                        LocalFilter.ALBUM -> setOf(com.metrolist.spotify.models.SpotifyLibraryEntry.Kind.ALBUM)
                        LocalFilter.ARTIST -> setOf(com.metrolist.spotify.models.SpotifyLibraryEntry.Kind.ARTIST)
                        LocalFilter.PLAYLIST -> setOf(com.metrolist.spotify.models.SpotifyLibraryEntry.Kind.PLAYLIST)
                        LocalFilter.SONG -> emptySet()
                    }
                    catalogEntries.value = if (kinds.isEmpty()) {
                        emptyList()
                    } else {
                        com.metrolist.music.catalog.CatalogLibrarySearch.searchEntries(q, kinds, limit * 2)
                    }
                }
        }
    }

    val result =
        combine(
            query,
            filter,
            context.dataStore.data.map { it[HideVideoSongsKey] ?: false }.distinctUntilChanged()
        ) { query, filter, hideVideoSongs ->
            Triple(query, filter, hideVideoSongs)
        }.flatMapLatest { (query, filter, hideVideoSongs) ->
            if (query.isEmpty()) {
                flowOf(LocalSearchResult("", filter, emptyMap()))
            } else {
                when (filter) {
                    LocalFilter.ALL ->
                        combine(
                            database.searchLibrarySongs(query, PREVIEW_SIZE),
                            database.searchLibraryAlbums(query, PREVIEW_SIZE),
                            database.searchLibraryArtists(query, PREVIEW_SIZE),
                            database.searchPlaylists(query, PREVIEW_SIZE),
                        ) { songs, albums, artists, playlists ->
                            val filteredSongs = if (hideVideoSongs) songs.filter { !it.song.isVideo } else songs
                            filteredSongs + albums + artists + playlists
                        }

                    LocalFilter.SONG -> database.searchLibrarySongs(query).map { songs ->
                        if (hideVideoSongs) songs.filter { !it.song.isVideo } else songs
                    }
                    LocalFilter.ALBUM -> database.searchLibraryAlbums(query)
                    LocalFilter.ARTIST -> database.searchLibraryArtists(query)
                    LocalFilter.PLAYLIST -> database.searchPlaylists(query)
                }.map { list ->
                    LocalSearchResult(
                        query = query,
                        filter = filter,
                        map =
                        list.groupBy {
                            when (it) {
                                is Song -> LocalFilter.SONG
                                is Album -> LocalFilter.ALBUM
                                is Artist -> LocalFilter.ARTIST
                                is Playlist -> LocalFilter.PLAYLIST
                            }
                        },
                    )
                }
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            LocalSearchResult("", filter.value, emptyMap())
        )

    companion object {
        const val PREVIEW_SIZE = 5
    }
}

enum class LocalFilter {
    ALL,
    SONG,
    ALBUM,
    ARTIST,
    PLAYLIST,
}

data class LocalSearchResult(
    val query: String,
    val filter: LocalFilter,
    val map: Map<LocalFilter, List<LocalItem>>,
)
