/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.metrolist.music.R
import com.metrolist.music.constants.ChipSortTypeKey
import com.metrolist.music.constants.LibraryFilter
import com.metrolist.music.ui.component.ChipsRow
import com.metrolist.music.ui.screens.library.local.LocalFilesScreen
import com.metrolist.music.utils.rememberEnumPreference

@Composable
fun LibraryScreen(navController: NavController) {
    // With a linked Spotify account the library mirrors Spotify's "My Library" exactly.
    val (spotifyEnabled) = com.metrolist.music.utils.rememberPreference(com.metrolist.music.constants.EnableSpotifyKey, false)
    val (spotifyToken) = com.metrolist.music.utils.rememberPreference(com.metrolist.music.constants.SpotifyAccessTokenKey, "")
    if (spotifyEnabled && spotifyToken.isNotEmpty()) {
        var showLocal by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxSize()) {
            if (showLocal) {
                LibraryLocalScreen(
                    navController,
                    filterContent = {
                        LibraryChips(
                            selected = com.metrolist.music.viewmodels.SpotifyLibraryViewModel.Filter.ALL,
                            localSelected = true,
                            onSelect = { showLocal = false },
                            onClear = { showLocal = false },
                            onLocal = { showLocal = false },
                        )
                    },
                )
                androidx.activity.compose.BackHandler { showLocal = false }
            } else {
                SpotifyLibraryScreen(navController, onLocalSelected = { showLocal = true })
            }
        }
        return
    }

    var filterType by rememberEnumPreference(ChipSortTypeKey, LibraryFilter.LIBRARY)

    // P5: Spotify-style library tabs — exactly four chips: Playlists, Albums, Artists, Local.
    val filterContent = @Composable {
        Row {
            ChipsRow(
                chips = listOf(
                    LibraryFilter.PLAYLISTS to stringResource(R.string.filter_playlists),
                    LibraryFilter.ALBUMS to stringResource(R.string.filter_albums),
                    LibraryFilter.ARTISTS to stringResource(R.string.filter_artists),
                    LibraryFilter.LOCAL_FILES to stringResource(R.string.filter_local),
                ),
                currentValue = filterType,
                onValueUpdate = {
                    filterType = if (filterType == it) LibraryFilter.LIBRARY else it
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (filterType) {
            LibraryFilter.LIBRARY -> LibraryMixScreen(navController, filterContent)
            LibraryFilter.PLAYLISTS -> LibraryPlaylistsScreen(navController, filterContent)
            LibraryFilter.SONGS -> LibrarySongsScreen(
                navController,
                { filterType = LibraryFilter.LIBRARY },
            )
            LibraryFilter.ALBUMS -> LibraryAlbumsScreen(
                navController,
                { filterType = LibraryFilter.LIBRARY },
            )
            LibraryFilter.ARTISTS -> LibraryArtistsScreen(
                navController,
                { filterType = LibraryFilter.LIBRARY },
            )
            LibraryFilter.PODCASTS -> LibraryPodcastsScreen(
                navController,
                { filterType = LibraryFilter.LIBRARY },
            )

            // P5: "Local" now lists the on-device / linked auto-collections (Downloaded, Cached,
            // Uploaded, Spotify Liked Songs) with counts — Spotify-style — instead of the raw
            // device-file browser. filterContent keeps the top chips so the user can switch back.
            LibraryFilter.LOCAL_FILES -> LibraryLocalScreen(navController, filterContent)
        }
    }
}
