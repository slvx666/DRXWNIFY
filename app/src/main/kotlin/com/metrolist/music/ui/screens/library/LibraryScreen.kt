/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.metrolist.music.R
import com.metrolist.music.constants.ChipSortTypeKey
import com.metrolist.music.constants.LibraryFilter
import com.metrolist.music.ui.component.ChipsRow
import com.metrolist.music.ui.screens.library.local.LocalFilesScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.ui.component.CreatePlaylistDialog
import com.metrolist.music.utils.rememberEnumPreference

@Composable
fun LibraryScreen(navController: NavController) {
    // With a linked Spotify and/or Yandex Music account the library mirrors that account's "My Library"
    // (both merged without duplicates when both are linked and combined).
    val catalogState by com.metrolist.music.catalog.Catalog.state.collectAsState()

    // Tapping the Library tab again puts it back the way it looks when first opened: no filter and
    // not inside "Local". The lists themselves scroll to the top on the same signal.
    val backStackEntry by navController.currentBackStackEntryAsState()
    val resetRequested by backStackEntry?.savedStateHandle
        ?.getStateFlow("scrollToTop", false)
        ?.collectAsState() ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    if (catalogState.isActive) {
        var showLocal by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(resetRequested) {
            if (resetRequested) showLocal = false
        }
        androidx.compose.runtime.CompositionLocalProvider(
            com.metrolist.music.ui.component.LocalListItemSizes provides
                com.metrolist.music.ui.component.LibraryListItemSizes,
        ) {
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
        }
        return
    }

    var filterType by rememberEnumPreference(ChipSortTypeKey, LibraryFilter.LIBRARY)
    androidx.compose.runtime.LaunchedEffect(resetRequested) {
        if (resetRequested && filterType != LibraryFilter.LIBRARY) filterType = LibraryFilter.LIBRARY
    }

    var showCreatePlaylistDialog by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onPlaylistCreated = { playlistId ->
                showCreatePlaylistDialog = false
                navController.navigate("local_playlist/$playlistId")
            },
        )
    }

    // P5: Spotify-style library tabs — exactly four chips: Playlists, Albums, Artists, Local.
    val filterContent = @Composable {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
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
            if (filterType == LibraryFilter.PLAYLISTS) {
                IconButton(
                    onClick = { showCreatePlaylistDialog = true },
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.add),
                        contentDescription = stringResource(R.string.create_playlist),
                    )
                }
            }
        }
    }

    // Same row proportions as the account library, so the two don't look like different apps.
    androidx.compose.runtime.CompositionLocalProvider(
        com.metrolist.music.ui.component.LocalListItemSizes provides
            com.metrolist.music.ui.component.LibraryListItemSizes,
    ) {
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
}
