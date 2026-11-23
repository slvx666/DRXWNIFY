/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.artist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.music.playback.queues.SpotifyPlaylistQueue
import com.metrolist.music.utils.makeTimeString
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class ReleaseFilter(val label: Int) {
    ALL(R.string.disc_all),
    ALBUMS(R.string.albums),
    SINGLES(R.string.singles_and_eps),
    COMPILATIONS(R.string.disc_compilations),
}

private enum class ReleaseSort(val label: Int) {
    NEWEST(R.string.disc_sort_newest),
    OLDEST(R.string.disc_sort_oldest),
    NAME(R.string.disc_sort_name),
}

/**
 * The artist's whole discography, as Spotify's "Show all" lays it out: a header that stays on top
 * (the artist; which releases; in what order — newest first by default), then each release with
 * its cover, kind and year, a play button and its tracks. A tap on a track plays its release from
 * there.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistDiscographyScreen(navController: NavController, artistId: String) {
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val mapper = remember(database) { SpotifyYouTubeMapper(database) }
    var filter by rememberSaveable { mutableStateOf(ReleaseFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(ReleaseSort.NEWEST) }

    val artistName by produceState("", artistId) {
        value = withContext(Dispatchers.IO) { Catalog.artist(artistId).getOrNull()?.name.orEmpty() }
    }
    val releases by produceState<List<SpotifyAlbum>?>(null, artistId) {
        value = withContext(Dispatchers.IO) {
            Catalog.artistReleases(artistId).getOrNull().orEmpty().distinctBy { it.id }
        }
    }
    val shown = remember(releases, filter, sort) {
        val list = releases.orEmpty().filter { a ->
            when (filter) {
                ReleaseFilter.ALL -> true
                ReleaseFilter.ALBUMS -> a.albumType.equals("album", ignoreCase = true)
                ReleaseFilter.SINGLES -> a.albumType.equals("single", ignoreCase = true) || a.albumType.equals("ep", ignoreCase = true)
                ReleaseFilter.COMPILATIONS -> a.albumType.equals("compilation", ignoreCase = true)
            }
        }
        when (sort) {
            ReleaseSort.NEWEST -> list.sortedByDescending { it.releaseDate.orEmpty() }
            ReleaseSort.OLDEST -> list.sortedBy { it.releaseDate.orEmpty().ifEmpty { "9999" } }
            ReleaseSort.NAME -> list.sortedBy { it.name.lowercase() }
        }
    }

    LazyColumn(
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        stickyHeader(key = "header") {
            DiscographyHeader(
                artistName = artistName,
                filter = filter,
                sort = sort,
                onBack = { navController.navigateUp() },
                onFilter = { filter = it },
                onSort = { sort = it },
            )
        }
        when {
            releases == null -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            shown.isEmpty() -> item(key = "empty") {
                Text(
                    stringResource(R.string.disc_nothing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
            else -> items(shown, key = { it.id }) { album ->
                ReleaseSection(
                    album = album,
                    onOpen = { navController.navigate("spotify_album/${album.id}") },
                    onPlay = { tracks, index ->
                        playerConnection?.playQueue(
                            SpotifyPlaylistQueue(
                                playlistId = "album_${album.id}",
                                initialTracks = tracks,
                                startIndex = index,
                                mapper = mapper,
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun DiscographyHeader(
    artistName: String,
    filter: ReleaseFilter,
    sort: ReleaseSort,
    onBack: () -> Unit,
    onFilter: (ReleaseFilter) -> Unit,
    onSort: (ReleaseSort) -> Unit,
) {
    var filterOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.arrow_back), contentDescription = null) }
            Text(
                artistName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { filterOpen = true }.padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(stringResource(filter.label), style = MaterialTheme.typography.labelLarge)
                    Icon(painterResource(R.drawable.expand_more), null, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                    ReleaseFilter.entries.forEach { f ->
                        DropdownMenuItem(text = { Text(stringResource(f.label)) }, onClick = { onFilter(f); filterOpen = false })
                    }
                }
            }
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { sortOpen = true }.padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(stringResource(sort.label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(painterResource(R.drawable.list), null, modifier = Modifier.padding(start = 4.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    ReleaseSort.entries.forEach { s ->
                        DropdownMenuItem(text = { Text(stringResource(s.label)) }, onClick = { onSort(s); sortOpen = false })
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

/** One release: cover, name, kind · year · tracks, a play button, then its tracks (looked up when shown). */
@Composable
private fun ReleaseSection(album: SpotifyAlbum, onOpen: () -> Unit, onPlay: (List<SpotifyTrack>, Int) -> Unit) {
    val tracks by produceState<List<SpotifyTrack>?>(album.tracks?.items?.filter { !it.isLocal }?.takeIf { it.isNotEmpty() }, album.id) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) { Catalog.album(album.id).getOrNull()?.tracks?.items?.filter { !it.isLocal } }.orEmpty()
    }
    val kind = stringResource(
        when (album.albumType?.lowercase()) {
            "single" -> if ((album.totalTracks.takeIf { it > 0 } ?: tracks?.size ?: 1) > 3) R.string.disc_ep else R.string.disc_single
            "ep" -> R.string.disc_ep
            "compilation" -> R.string.disc_compilation
            else -> R.string.chat_album
        },
    )
    val year = album.releaseDate?.take(4).orEmpty()
    val count = album.totalTracks.takeIf { it > 0 } ?: tracks?.size ?: 0
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 4.dp)) {
            AsyncImage(
                model = album.images.maxByOrNull { it.width ?: 0 }?.url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(album.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(kind, year.takeIf { it.isNotBlank() }, androidx.compose.ui.res.pluralStringResource(R.plurals.n_song, count, count).takeIf { count > 0 })
                        .joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface)
                        .clickable(enabled = !tracks.isNullOrEmpty()) { tracks?.let { onPlay(it, 0) } },
                ) {
                    Icon(painterResource(R.drawable.play), null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(24.dp))
                }
            }
        }
        val list = tracks
        if (list == null) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            Spacer(Modifier.height(8.dp))
            list.forEachIndexed { i, t ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onPlay(list, i) }.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(24.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(t.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            t.artists.joinToString { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(makeTimeString(t.durationMs.toLong()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
