/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.metrolist.music.constants.ChipSortTypeKey
import com.metrolist.music.constants.LibraryFilter
import com.metrolist.music.ui.component.CreatePlaylistDialog
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.viewmodels.SpotifyLibraryViewModel

/**
 * Switching library sections (a filter, "Local", "VK"): the new section fades in rising slightly
 * while the old one fades out, instead of the screen swapping in one frame.
 */
private fun <S> AnimatedContentTransitionScope<S>.librarySectionTransition(): ContentTransform =
    (
        fadeIn(tween(SECTION_IN_MS, delayMillis = SECTION_OUT_MS / 2, easing = FastOutSlowInEasing)) +
            slideInVertically(tween(SECTION_IN_MS, easing = FastOutSlowInEasing)) { it / 28 } +
            scaleIn(tween(SECTION_IN_MS, easing = FastOutSlowInEasing), initialScale = 0.985f)
        ) togetherWith fadeOut(tween(SECTION_OUT_MS))

private const val SECTION_IN_MS = 280
private const val SECTION_OUT_MS = 140

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

    // "VK": the connected VK account's own music, next to the other chips.
    val (vkToken) = com.metrolist.music.utils.rememberPreference(com.metrolist.music.constants.VkAccessTokenKey, "")
    var showVk by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(resetRequested) {
        if (resetRequested) showVk = false
    }
    val openVk: (() -> Unit)? = if (vkToken.isNotEmpty()) ({ showVk = true }) else null
    val vkOpen = showVk && vkToken.isNotEmpty()
    if (vkOpen) androidx.activity.compose.BackHandler { showVk = false }

    // Same row proportions everywhere in the library, so the sections don't look like different apps.
    androidx.compose.runtime.CompositionLocalProvider(
        com.metrolist.music.ui.component.LocalListItemSizes provides
            com.metrolist.music.ui.component.LibraryListItemSizes,
    ) {
        AnimatedContent(
            targetState = vkOpen,
            transitionSpec = { librarySectionTransition() },
            label = "librarySection",
            modifier = Modifier.fillMaxSize(),
        ) { vk ->
            when {
                vk -> com.metrolist.music.ui.screens.vk.VkLibraryScreen(
                    navController = navController,
                    onClose = { showVk = false },
                )
                catalogState.isActive -> AccountLibrary(navController, resetRequested, openVk)
                else -> LocalLibrary(navController, resetRequested, openVk)
            }
        }
    }
}

/** The library of the connected Spotify / Yandex Music account, with "Local" one chip away. */
@Composable
private fun AccountLibrary(navController: NavController, resetRequested: Boolean, openVk: (() -> Unit)?) {
    var showLocal by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(resetRequested) {
        if (resetRequested) showLocal = false
    }
    if (showLocal) androidx.activity.compose.BackHandler { showLocal = false }

    AnimatedContent(
        targetState = showLocal,
        transitionSpec = { librarySectionTransition() },
        label = "accountLibrary",
        modifier = Modifier.fillMaxSize(),
    ) { local ->
        if (local) {
            LibraryLocalScreen(
                navController,
                filterContent = {
                    LibraryChips(
                        selected = SpotifyLibraryViewModel.Filter.ALL,
                        localSelected = true,
                        onSelect = { showLocal = false },
                        onClear = { showLocal = false },
                        onLocal = { showLocal = false },
                    )
                },
            )
        } else {
            SpotifyLibraryScreen(navController, onLocalSelected = { showLocal = true }, onVkSelected = openVk)
        }
    }
}

/** The library without a music account: what is saved inside the app. */
@Composable
private fun LocalLibrary(navController: NavController, resetRequested: Boolean, openVk: (() -> Unit)?) {
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

    // The same chip row as the account library: all filters when none is picked; once one is,
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

    AnimatedContent(
        targetState = filterType,
        transitionSpec = { librarySectionTransition() },
        label = "localLibrary",
        modifier = Modifier.fillMaxSize(),
    ) { type ->
        Box(modifier = Modifier.fillMaxSize()) {
            when (type) {
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

                // "Local" lists the on-device / linked auto-collections (Downloaded, Cached, Uploaded,
                // Spotify Liked Songs) with counts; filterContent keeps the top chips to switch back.
                LibraryFilter.LOCAL_FILES -> LibraryLocalScreen(navController, filterContent)
            }
        }
    }
}
