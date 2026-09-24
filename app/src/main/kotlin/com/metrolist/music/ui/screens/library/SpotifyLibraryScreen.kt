/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.extensions.matchesNormalizedQuery
import com.metrolist.music.extensions.normalizeForSearch
import com.metrolist.music.ui.component.CreatePlaylistDialog
import com.metrolist.music.ui.component.LibrarySearchEmptyPlaceholder
import com.metrolist.music.ui.component.LibrarySearchHeader
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.SpotifyLibraryViewModel
import com.metrolist.music.viewmodels.SpotifyLibraryViewModel.Filter
import com.metrolist.music.viewmodels.SpotifyLibraryViewModel.Sort
import com.metrolist.spotify.models.SpotifyLibraryEntry
import java.net.URLEncoder

private val SpotifyGreen = Color(0xFF1ED760)
private val LibraryGridKey = booleanPreferencesKey("spotify_library_grid")

/**
 * Spotify-style "My Library": chips (Playlists / Albums / Artists / Local), a "Recents" sort row with
 * a list/grid toggle, then pinned items followed by everything else in Spotify's own order. Albums and
 * playlists use rounded squares, artists circles; the subtitle shows a green pin (if pinned) and
 * "Type • creator".
 */
@Composable
fun SpotifyLibraryScreen(
    navController: NavController,
    onLocalSelected: () -> Unit,
    onVkSelected: (() -> Unit)? = null,
    viewModel: SpotifyLibraryViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val loading by viewModel.loading.collectAsState()
    var grid by rememberPreference(LibraryGridKey, false)
    val keyboardController = LocalSoftwareKeyboardController.current

    var showCreatePlaylistDialog by rememberSaveable { mutableStateOf(false) }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onPlaylistCreated = { playlistId ->
                showCreatePlaylistDialog = false
                navController.navigate("local_playlist/$playlistId")
            },
        )
    }

    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    val searchQuery by viewModel.searchQuery.collectAsState()
    val normalizedQuery = remember(searchQuery) { searchQuery.normalizeForSearch() }
    val filteredEntries = remember(entries, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            entries
        } else {
            entries.filter { entry ->
                matchesNormalizedQuery(normalizedQuery, entry.name, entry.creator)
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = isSearchActive || filter != Filter.ALL) {
        if (isSearchActive) {
            isSearchActive = false
            viewModel.updateSearchQuery("")
        } else {
            viewModel.clearFilter()
        }
    }

    // Tapping the Library tab again: back to the unfiltered list, scrolled to the top.
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val resetRequested = backStackEntry?.savedStateHandle
        ?.getStateFlow("scrollToTop", false)
        ?.collectAsState()
    androidx.compose.runtime.LaunchedEffect(resetRequested?.value) {
        if (resetRequested?.value == true) {
            if (isSearchActive) {
                isSearchActive = false
                viewModel.updateSearchQuery("")
            }
            viewModel.clearFilter()
            gridState.animateScrollToItem(0)
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = if (grid) GridCells.Fixed(3) else GridCells.Fixed(1),
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "chips", span = { GridItemSpan(maxLineSpan) }) {
            LibraryChips(
                selected = filter,
                localSelected = false,
                onSelect = { viewModel.toggleFilter(it) },
                onClear = { viewModel.clearFilter() },
                onLocal = onLocalSelected,
                onCreatePlaylist = { showCreatePlaylistDialog = true },
                onVk = onVkSelected,
            )
        }
        item(key = "sort", span = { GridItemSpan(maxLineSpan) }) {
            SortRow(
                sort = sort,
                grid = grid,
                isSearchActive = isSearchActive,
                searchQuery = searchQuery,
                onSearchQueryChange = viewModel::updateSearchQuery,
                onSearchActiveChange = { isSearchActive = it },
                keyboardController = keyboardController,
                onSort = viewModel::setSort,
                onToggleGrid = { grid = !grid },
            )
        }

        if (filteredEntries.isEmpty() && isSearchActive && normalizedQuery.isNotBlank()) {
            item(key = "empty_search", span = { GridItemSpan(maxLineSpan) }) {
                LibrarySearchEmptyPlaceholder(
                    text = stringResource(R.string.no_results_found),
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
        items(filteredEntries, key = { it.uri.ifBlank { it.id } }) { entry ->
            val onClick = { openEntry(navController, entry) }
            // Switching filters fades rows in/out and slides the ones that stay into place,
            // instead of the list snapping to its new contents.
            Box(
                Modifier.animateItem(
                    fadeInSpec = androidx.compose.animation.core.tween(260),
                    placementSpec = androidx.compose.animation.core.spring(
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                    ),
                    fadeOutSpec = androidx.compose.animation.core.tween(160),
                ),
            ) {
                if (grid) LibraryGridCell(entry, onClick) else LibraryRow(entry, onClick)
            }
        }
        // The app's own playlists show at once; the account's library arrives later. Until it has,
        // a spinner under the list says more is coming.
        if (loading) {
            item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/** Chip row. With nothing selected all chips show; once one is selected the others slide away and a
 *  close chip appears — tapping the selected chip (or the close chip) returns to everything. */
@Composable
fun LibraryChips(
    selected: Filter,
    localSelected: Boolean,
    onSelect: (Filter) -> Unit,
    onClear: () -> Unit,
    onLocal: () -> Unit,
    onCreatePlaylist: (() -> Unit)? = null,
    vkSelected: Boolean = false,
    /** Shows the "VK" chip (the account's VK playlists); null while VK isn't connected. */
    onVk: (() -> Unit)? = null,
) {
    val labels = listOf(
        Filter.PLAYLISTS to stringResource(R.string.filter_playlists),
        Filter.ALBUMS to stringResource(R.string.filter_albums),
        Filter.ARTISTS to stringResource(R.string.filter_artists),
    )
    val localLabel = stringResource(R.string.filter_local)
    val state = when {
        vkSelected -> "vk"
        localSelected -> "local"
        else -> selected.name
    }
    AnimatedContent(
        targetState = state,
        transitionSpec = { (fadeIn() + slideInHorizontally { it / 6 }) togetherWith fadeOut() },
        label = "libraryChips",
    ) { current ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (current == Filter.ALL.name) {
                // Order: Albums, Artists, Local, Playlists, VK.
                labels.filter { it.first != Filter.PLAYLISTS }
                    .forEach { (f, label) -> Chip(label, selected = false) { onSelect(f) } }
                Chip(localLabel, selected = false, onClick = onLocal)
                labels.first { it.first == Filter.PLAYLISTS }.let { (f, label) ->
                    Chip(label, selected = false) { onSelect(f) }
                }
                onVk?.let { Chip("VK", selected = false, onClick = it) }
            } else {
                CloseChip(onClick = onClear)
                if (current == "vk") {
                    Chip("VK", selected = true, onClick = onClear)
                } else if (current == "local") {
                    Chip(localLabel, selected = true, onClick = onClear)
                } else {
                    val f = Filter.valueOf(current)
                    Chip(labels.first { it.first == f }.second, selected = true) { onSelect(f) }
                    if (f == Filter.PLAYLISTS && onCreatePlaylist != null) {
                        IconButton(
                            onClick = onCreatePlaylist,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.add),
                                contentDescription = stringResource(R.string.create_playlist),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) SpotifyGreen else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun CloseChip(onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.close), null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SortRow(
    sort: Sort,
    grid: Boolean,
    isSearchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    onSort: (Sort) -> Unit,
    onToggleGrid: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val sortLabel = stringResource(
        when (sort) {
            Sort.RECENTS -> R.string.lib_sort_recents
            Sort.RECENTLY_ADDED -> R.string.lib_sort_recently_added
            Sort.ALPHABETICAL -> R.string.lib_sort_alphabetical
            Sort.CREATOR -> R.string.lib_sort_creator
        },
    )
    LibrarySearchHeader(
        isSearchActive = isSearchActive,
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        onBack = {
            onSearchActiveChange(false)
            onSearchQueryChange("")
        },
        keyboardController = keyboardController,
        modifier = Modifier.padding(start = 16.dp),
    ) {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { menu = true }.padding(vertical = 8.dp),
            ) {
                Icon(painterResource(R.drawable.arrow_downward), null, Modifier.size(14.dp))
                Icon(painterResource(R.drawable.arrow_upward), null, Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(sortLabel, style = MaterialTheme.typography.labelLarge)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf(
                    Sort.RECENTS to R.string.lib_sort_recents,
                    Sort.RECENTLY_ADDED to R.string.lib_sort_recently_added,
                    Sort.ALPHABETICAL to R.string.lib_sort_alphabetical,
                    Sort.CREATOR to R.string.lib_sort_creator,
                ).forEach { (s, res) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(res),
                                color = if (s == sort) SpotifyGreen else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = { onSort(s); menu = false },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = { onSearchActiveChange(true) },
            modifier = Modifier.padding(start = 8.dp).size(40.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.search),
                contentDescription = stringResource(R.string.search),
            )
        }
        IconButton(
            onClick = onToggleGrid,
            modifier = Modifier.padding(end = 8.dp).size(40.dp),
        ) {
            Icon(
                painter = painterResource(if (grid) R.drawable.list else R.drawable.grid_view),
                contentDescription = stringResource(
                    if (grid) R.string.switch_to_list_view else R.string.switch_to_grid_view,
                ),
            )
        }
    }
}

@Composable
private fun entrySubtitle(entry: SpotifyLibraryEntry): String {
    val type = when (entry.kind) {
        SpotifyLibraryEntry.Kind.PLAYLIST, SpotifyLibraryEntry.Kind.LIKED_SONGS -> stringResource(R.string.lib_type_playlist)
        SpotifyLibraryEntry.Kind.ARTIST -> stringResource(R.string.lib_type_artist)
        SpotifyLibraryEntry.Kind.FOLDER -> stringResource(R.string.lib_type_folder)
        SpotifyLibraryEntry.Kind.ALBUM -> stringResource(
            when (entry.albumType) {
                "single" -> R.string.lib_type_single
                "ep" -> R.string.lib_type_ep
                "compilation" -> R.string.lib_type_compilation
                else -> R.string.lib_type_album
            },
        )
    }
    val creator = when {
        // Own playlist: its size says more than an owner name it does not have.
        entry.uri.startsWith(SpotifyLibraryViewModel.LOCAL_PLAYLIST_URI_PREFIX) ->
            pluralStringResource(R.plurals.n_song, entry.totalCount, entry.totalCount)
        else -> when (entry.kind) {
        SpotifyLibraryEntry.Kind.FOLDER ->
            entry.totalCount.takeIf { it > 0 }?.let { stringResource(R.string.lib_folder_count, it) }
        SpotifyLibraryEntry.Kind.ARTIST -> null
        else -> entry.creator
        }
    }
    return if (creator.isNullOrBlank()) type else "$type • $creator"
}

@Composable
private fun EntryArtwork(entry: SpotifyLibraryEntry, modifier: Modifier) {
    val shape: Shape = if (entry.kind == SpotifyLibraryEntry.Kind.ARTIST) CircleShape else RoundedCornerShape(4.dp)
    when (entry.kind) {
        SpotifyLibraryEntry.Kind.LIKED_SONGS -> Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.clip(shape).background(
                Brush.linearGradient(listOf(Color(0xFF4A11F5), Color(0xFFC4EFD9))),
            ),
        ) {
            Icon(painterResource(R.drawable.favorite), null, tint = Color.White, modifier = Modifier.fillMaxSize(0.4f))
        }
        SpotifyLibraryEntry.Kind.FOLDER -> Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Icon(painterResource(R.drawable.folder), null, modifier = Modifier.fillMaxSize(0.4f))
        }
        // A playlist with no cover of its own falls back to an icon; its artwork (the last track
        // added to it) arrives as imageUrl like any other entry's.
        else -> if (entry.imageUrl == null && entry.kind == SpotifyLibraryEntry.Kind.PLAYLIST) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Icon(painterResource(R.drawable.queue_music), null, modifier = Modifier.fillMaxSize(0.4f))
            }
        } else {
            AsyncImage(
                model = entry.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }
}

@Composable
private fun PinnedSubtitle(entry: SpotifyLibraryEntry, maxLines: Int = 1) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (entry.pinned) {
            Icon(
                painterResource(R.drawable.ic_push_pin),
                contentDescription = null,
                tint = SpotifyGreen,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = entrySubtitle(entry),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LibraryRow(entry: SpotifyLibraryEntry, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        EntryArtwork(entry, Modifier.size(64.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            PinnedSubtitle(entry)
        }
    }
}

@Composable
private fun LibraryGridCell(entry: SpotifyLibraryEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        EntryArtwork(entry, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(6.dp))
        Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        PinnedSubtitle(entry)
    }
}

private fun openEntry(navController: NavController, entry: SpotifyLibraryEntry) {
    // Playlists made inside the app live in the local database, not in the connected account.
    if (entry.uri.startsWith(SpotifyLibraryViewModel.LOCAL_PLAYLIST_URI_PREFIX)) {
        navController.navigate("local_playlist/${entry.id}")
        return
    }
    when (entry.kind) {
        SpotifyLibraryEntry.Kind.LIKED_SONGS -> navController.navigate("spotify_liked_songs")
        SpotifyLibraryEntry.Kind.PLAYLIST -> navController.navigate("spotify_playlist/${entry.id}")
        SpotifyLibraryEntry.Kind.ALBUM -> navController.navigate("spotify_album/${entry.id}")
        SpotifyLibraryEntry.Kind.ARTIST -> navController.navigate("spotify_artist/${entry.id}")
        SpotifyLibraryEntry.Kind.FOLDER -> navController.navigate(
            "spotify_folder/${URLEncoder.encode(entry.uri, "UTF-8")}?name=${URLEncoder.encode(entry.name, "UTF-8")}",
        )
    }
}


/**
 * Chip row of the library's VK section: a "VK ×" pill that leaves it, then what VK offers —
 * tracks, albums, playlists. Compact on purpose, so the whole row fits one line on a phone.
 */
@Composable
fun VkLibraryChips(
    tab: com.metrolist.music.viewmodels.VkLibraryTab,
    onTab: (com.metrolist.music.viewmodels.VkLibraryTab) -> Unit,
    onClose: () -> Unit,
) {
    val tabs = listOf(
        com.metrolist.music.viewmodels.VkLibraryTab.TRACKS to stringResource(R.string.vk_tab_tracks),
        com.metrolist.music.viewmodels.VkLibraryTab.ALBUMS to stringResource(R.string.filter_albums),
        com.metrolist.music.viewmodels.VkLibraryTab.PLAYLISTS to stringResource(R.string.filter_playlists),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClose),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Text("VK", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(4.dp))
                Icon(painterResource(R.drawable.close), contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        tabs.forEach { (value, label) ->
            val selected = value == tab
            Surface(
                shape = RoundedCornerShape(50),
                color = if (selected) SpotifyGreen else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onTab(value) },
            ) {
                Text(
                    text = label,
                    color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}
