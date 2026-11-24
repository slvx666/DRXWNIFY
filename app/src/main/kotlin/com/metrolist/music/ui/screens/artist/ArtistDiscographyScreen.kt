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
import com.metrolist.music.utils.makeTimeString
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.only

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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
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

    /** The discography as shown is one list: it plays from this track down, release after release. */
    fun playFrom(release: SpotifyAlbum, index: Int) {
        val list = shown
        scope.launch {
            val before = withContext(Dispatchers.IO) {
                list.takeWhile { it.id != release.id }.sumOf { com.metrolist.music.playback.queues.DiscographyQueue.tracksOf(it).size }
            }
            playerConnection?.playQueue(com.metrolist.music.playback.queues.DiscographyQueue(list, before + index, mapper))
        }
    }

    LazyColumn(
        // The header takes the status bar itself; only the player at the bottom is padded for.
        contentPadding = LocalPlayerAwareWindowInsets.current
            .only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom)
            .asPaddingValues(),
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
                    mapper = mapper,
                    onOpen = { navController.navigate("spotify_album/${album.id}") },
                    onPlay = { index -> playFrom(album, index) },
                )
            }
        }
    }
}

/**
 * Stays on top: back and the artist's name (two lines when long) on the first row, which releases
 * and in what order on the second, so the name never has to share its row with them.
 */
@Composable
private fun DiscographyHeader(
    artistName: String,
    filter: ReleaseFilter,
    sort: ReleaseSort,
    onBack: () -> Unit,
    onFilter: (ReleaseFilter) -> Unit,
    onSort: (ReleaseSort) -> Unit,
) {
    var sortOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(end = 16.dp, top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.arrow_back), contentDescription = null) }
            Text(
                artistName,
                style = if (artistName.length > 22) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 6.dp),
        ) {
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(ReleaseFilter.entries) { f ->
                    val on = f == filter
                    Text(
                        stringResource(f.label),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onFilter(f) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
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

/**
 * One release: cover, name, kind · year · tracks, then add / download / share as on any album,
 * then its tracks (looked up when shown). A track plays the discography from there.
 */
@Composable
private fun ReleaseSection(album: SpotifyAlbum, mapper: SpotifyYouTubeMapper, onOpen: () -> Unit, onPlay: (Int) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = com.metrolist.music.LocalDownloadUtil.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val tracks by produceState<List<SpotifyTrack>?>(album.tracks?.items?.filter { !it.isLocal }?.takeIf { it.isNotEmpty() }, album.id) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) { com.metrolist.music.playback.queues.DiscographyQueue.tracksOf(album) }
    }
    var saved by remember(album.id) { mutableStateOf<Boolean?>(null) }
    androidx.compose.runtime.LaunchedEffect(album.id) {
        saved = withContext(Dispatchers.IO) { Catalog.isAlbumSaved(album.id).getOrNull() }
    }
    val sourceId = "album_${album.id}"
    val progress by com.metrolist.music.playback.SpotifyBatchDownload.progressBySource.collectAsState()
    val downloading = progress[sourceId]?.let { it.phase != com.metrolist.music.playback.SpotifyBatchDownload.Phase.DONE } == true

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
    val by = album.artists.joinToString { it.name }
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    // Add to the library (saved in the account), as the album's own page does.
                    IconButton(onClick = {
                        val target = !(saved ?: false)
                        saved = target
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                (if (target) Catalog.saveAlbum(album.id) else Catalog.removeAlbum(album.id)).isSuccess
                            }
                            if (!ok) saved = !target
                        }
                    }) {
                        Icon(painterResource(if (saved == true) R.drawable.done else R.drawable.library_add), contentDescription = null)
                    }
                    // Download the release (into its album folder, as from its page).
                    IconButton(
                        enabled = !tracks.isNullOrEmpty() && !downloading,
                        onClick = {
                            tracks?.let { list ->
                                android.widget.Toast.makeText(context, context.getString(R.string.spotify_download_started, list.size), android.widget.Toast.LENGTH_SHORT).show()
                                com.metrolist.music.playback.SpotifyBatchDownload.start(
                                    appContext = context.applicationContext,
                                    sourceId = sourceId,
                                    tracks = list,
                                    mapper = mapper,
                                    label = album.name,
                                    downloads = downloadUtil.downloads,
                                    database = database,
                                )
                            }
                        },
                    ) {
                        if (downloading) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(painterResource(R.drawable.download), contentDescription = null)
                        }
                    }
                    com.metrolist.music.ui.component.ShareLinkButton(
                        com.metrolist.music.utils.AppLinks.Kind.ALBUM,
                        album.id,
                        album.name,
                        by,
                    )
                }
            }
        }
        val list = tracks
        if (list == null) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            Spacer(Modifier.height(4.dp))
            list.forEachIndexed { i, t ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onPlay(i) }.padding(horizontal = 16.dp, vertical = 8.dp),
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
