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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
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
    viewModel: SpotifyLibraryViewModel = hiltViewModel(),
) {
    val filter by viewModel.filter.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val loading by viewModel.loading.collectAsState()
    var grid by rememberPreference(LibraryGridKey, false)

    androidx.activity.compose.BackHandler(enabled = filter != Filter.ALL) { viewModel.clearFilter() }

    LazyVerticalGrid(
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
            )
        }
        item(key = "sort", span = { GridItemSpan(maxLineSpan) }) {
            SortRow(
                sort = sort,
                grid = grid,
                onSort = viewModel::setSort,
                onToggleGrid = { grid = !grid },
            )
        }
        if (loading && entries.isEmpty()) {
            item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        items(entries, key = { it.uri.ifBlank { it.id } }) { entry ->
            val onClick = { openEntry(navController, entry) }
            if (grid) LibraryGridCell(entry, onClick) else LibraryRow(entry, onClick)
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
) {
    val labels = listOf(
        Filter.PLAYLISTS to stringResource(R.string.filter_playlists),
        Filter.ALBUMS to stringResource(R.string.filter_albums),
        Filter.ARTISTS to stringResource(R.string.filter_artists),
    )
    val localLabel = stringResource(R.string.filter_local)
    val state = if (localSelected) "local" else selected.name
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
                labels.forEach { (f, label) -> Chip(label, selected = false) { onSelect(f) } }
                Chip(localLabel, selected = false, onClick = onLocal)
            } else {
                CloseChip(onClick = onClear)
                if (current == "local") {
                    Chip(localLabel, selected = true, onClick = onClear)
                } else {
                    val f = Filter.valueOf(current)
                    Chip(labels.first { it.first == f }.second, selected = true) { onSelect(f) }
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
private fun SortRow(sort: Sort, grid: Boolean, onSort: (Sort) -> Unit, onToggleGrid: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val sortLabel = stringResource(
        when (sort) {
            Sort.RECENTS -> R.string.lib_sort_recents
            Sort.RECENTLY_ADDED -> R.string.lib_sort_recently_added
            Sort.ALPHABETICAL -> R.string.lib_sort_alphabetical
            Sort.CREATOR -> R.string.lib_sort_creator
        },
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
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
        Icon(
            painter = painterResource(if (grid) R.drawable.list else R.drawable.grid_view),
            contentDescription = null,
            modifier = Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onToggleGrid),
        )
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
    val creator = when (entry.kind) {
        SpotifyLibraryEntry.Kind.FOLDER ->
            entry.totalCount.takeIf { it > 0 }?.let { stringResource(R.string.lib_folder_count, it) }
        SpotifyLibraryEntry.Kind.ARTIST -> null
        else -> entry.creator
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
        else -> AsyncImage(
            model = entry.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
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

