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
import com.metrolist.music.ui.screens.library.local.LocalFilesScreen
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.metrolist.music.ui.component.CreatePlaylistDialog
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.viewmodels.SpotifyLibraryViewModel

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

    // "VK": the connected VK account's own playlists and added albums, next to the other chips.
    val (vkToken) = com.metrolist.music.utils.rememberPreference(com.metrolist.music.constants.VkAccessTokenKey, "")
    var showVk by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(resetRequested) {
        if (resetRequested) showVk = false
    }
    val openVk: (() -> Unit)? = if (vkToken.isNotEmpty()) ({ showVk = true }) else null
    if (showVk && vkToken.isNotEmpty()) {
        androidx.compose.runtime.CompositionLocalProvider(
            com.metrolist.music.ui.component.LocalListItemSizes provides
                com.metrolist.music.ui.component.LibraryListItemSizes,
        ) {
            com.metrolist.music.ui.screens.vk.VkLibraryScreen(
                navController = navController,
                onClose = { showVk = false },
            )
        }
        androidx.activity.compose.BackHandler { showVk = false }
        return
    }

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
                SpotifyLibraryScreen(navController, onLocalSelected = { showLocal = true }, onVkSelected = openVk)
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

    // The same chip row as the account library: all four filters when none is picked; once one is,
    // only [×] and that filter stay (plus "+" for Playlists), exactly like the Spotify client.
    val filterContent = @Composable {
        LibraryChips(
            selected = when (filterType) {
                LibraryFilter.PLAYLISTS -> SpotifyLibraryViewModel.Filter.PLAYLISTS
                LibraryFilter.ALBUMS -> SpotifyLibraryViewModel.Filter.ALBUMS
                LibraryFilter.ARTISTS -> SpotifyLibraryViewModel.Filter.ARTISTS
                else -> SpotifyLibraryViewModel.Filter.ALL
            },
            localSelected = filterType == LibraryFilter.LOCAL_FILES,
            onSelect = { picked ->
                val target = when (picked) {
                    SpotifyLibraryViewModel.Filter.PLAYLISTS -> LibraryFilter.PLAYLISTS
                    SpotifyLibraryViewModel.Filter.ALBUMS -> LibraryFilter.ALBUMS
                    SpotifyLibraryViewModel.Filter.ARTISTS -> LibraryFilter.ARTISTS
                    SpotifyLibraryViewModel.Filter.ALL -> LibraryFilter.LIBRARY
                }
                filterType = if (filterType == target) LibraryFilter.LIBRARY else target
            },
            onClear = { filterType = LibraryFilter.LIBRARY },
            onLocal = { filterType = LibraryFilter.LOCAL_FILES },
            onCreatePlaylist = { showCreatePlaylistDialog = true },
            onVk = openVk,
        )
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
