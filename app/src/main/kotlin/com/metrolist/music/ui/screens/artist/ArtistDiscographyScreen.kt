/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.artist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.toSongItem
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
                    navController = navController,
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
    var filterOpen by remember { mutableStateOf(false) }
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
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
        ) {
            // The release kind: one button that opens the list (all, albums, singles and EPs, compilations).
            Box(Modifier.weight(1f)) {
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

/**
 * One release as on its own page: cover, name, kind · year · tracks, add / download / share, what
 * is downloaded and what the download is doing, then its tracks (liked, downloaded, plays) and how
 * long it all is. A track plays the discography from there; a long press opens its menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReleaseSection(
    album: SpotifyAlbum,
    mapper: SpotifyYouTubeMapper,
    navController: NavController,
    onOpen: () -> Unit,
    onPlay: (Int) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = com.metrolist.music.LocalDownloadUtil.current
    val playerConnection = LocalPlayerConnection.current
    val menuState = com.metrolist.music.ui.component.LocalMenuState.current
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
    val allProgress by com.metrolist.music.playback.SpotifyBatchDownload.progressBySource.collectAsState()
    val downloadProgress = allProgress[sourceId]
    val downloading = downloadProgress != null
    val downloads by downloadUtil.visibleDownloads.collectAsState()
    val spotifyToYt by produceState<Map<String, String>>(emptyMap(), tracks, downloading) {
        val list = tracks
        value = if (list.isNullOrEmpty()) emptyMap() else withContext(Dispatchers.IO) {
            database.getSpotifyMatchesBySpotifyIds(list.map { it.id }).associate { it.spotifyId to it.youtubeId }
        }
    }
    val likedTracks by com.metrolist.music.playback.SpotifyLikeCache.liked.collectAsState()
    androidx.compose.runtime.LaunchedEffect(tracks) {
        tracks?.takeIf { it.isNotEmpty() }?.let { list -> com.metrolist.music.playback.SpotifyLikeCache.ensureLoaded(list.map { it.id }) }
    }
    val nowId = playerConnection?.mediaMetadata?.collectAsState()?.value?.id
    val isPlaying = playerConnection?.isPlaying?.collectAsState()?.value == true
    val currentSpotifyId by produceState<String?>(null, nowId) {
        value = com.metrolist.music.playback.SpotifyMetadataRegistry.catalogIdOf(database, nowId)
    }
    val trackList = tracks.orEmpty()
    val downloadedCount = com.metrolist.music.ui.component.rememberDownloadedCount(trackList)

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
    fun startDownload() {
        val list = tracks?.takeIf { it.isNotEmpty() } ?: return
        com.metrolist.music.ui.component.BatchMarks.started(context, sourceId)
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
                    // Download as on the album's page: downloading shows its progress (a tap cancels),
                    // all downloaded shows the mark, otherwise it downloads the rest.
                    val allDownloaded = trackList.isNotEmpty() && downloadedCount >= trackList.size
                    IconButton(
                        enabled = trackList.isNotEmpty(),
                        onClick = {
                            when {
                                downloading -> com.metrolist.music.playback.SpotifyBatchDownload.cancel(sourceId)
                                allDownloaded -> Unit
                                else -> startDownload()
                            }
                        },
                    ) {
                        when {
                            downloading -> Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Icon(painterResource(R.drawable.close), contentDescription = stringResource(R.string.cancel), modifier = Modifier.size(14.dp))
                            }
                            allDownloaded -> Icon(painterResource(R.drawable.offline), contentDescription = null)
                            else -> Icon(painterResource(R.drawable.download), contentDescription = stringResource(R.string.action_download))
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
        if (trackList.isNotEmpty()) {
            // What is on the device already, and what the running download is doing.
            com.metrolist.music.ui.component.BatchDownloadProgress(
                progress = downloadProgress,
                downloaded = downloadedCount,
                total = trackList.size,
            )
            com.metrolist.music.ui.component.ResumeUnfinishedBatch(sourceId, trackList, downloading, downloads, spotifyToYt) { startDownload() }
        }
        val list = tracks
        if (list == null) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            Spacer(Modifier.height(4.dp))
            list.forEachIndexed { i, track ->
                val isActive = currentSpotifyId != null && currentSpotifyId == track.id
                val downloadState = com.metrolist.music.ui.component.bestDownloadState(
                    downloads[com.metrolist.music.resolver.FallbackIds.of(track.id)],
                    spotifyToYt[track.id]?.let { downloads[it] },
                )
                val liked = likedTracks.contains(track.id) ||
                    com.metrolist.music.playback.rememberCatalogTrackLiked(database, track.id, spotifyToYt[track.id])
                com.metrolist.music.ui.component.BlockedAware(track.id) {
                    com.metrolist.music.ui.component.ListItem(
                        title = track.name,
                        subtitle = com.metrolist.music.utils.joinByBullet(
                            track.artists.joinToString { it.name },
                            makeTimeString(track.durationMs.toLong()),
                        ),
                        isActive = isActive,
                        badges = {
                            if (liked) {
                                Icon(
                                    painterResource(R.drawable.favorite),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp).padding(end = 2.dp),
                                )
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                track.playCount?.takeIf { it > 0 }?.let { plays ->
                                    Text(
                                        text = com.metrolist.music.playback.SpotifyPlays.format(plays),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        modifier = Modifier.padding(end = 6.dp),
                                    )
                                }
                                // The downloaded-mark's slot is always kept, so the counts line up.
                                Box(modifier = Modifier.width(20.dp), contentAlignment = Alignment.CenterEnd) {
                                    com.metrolist.music.ui.component.Icon.Download(downloadState)
                                }
                            }
                        },
                        thumbnailContent = {
                            com.metrolist.music.ui.component.ItemThumbnail(
                                thumbnailUrl = com.metrolist.spotify.SpotifyMapper.getTrackThumbnail(track)
                                    ?: album.images.minByOrNull { it.width ?: Int.MAX_VALUE }?.url,
                                isActive = isActive,
                                isPlaying = isPlaying,
                                shape = RoundedCornerShape(com.metrolist.music.constants.ThumbnailCornerRadius),
                                modifier = Modifier.size(com.metrolist.music.constants.ListThumbnailSize),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onPlay(i) },
                                onLongClick = {
                                    menuState.show {
                                        com.metrolist.music.ui.menu.PlayerMenu(
                                            mediaMetadata = track.toSongItem().toMediaMetadata(),
                                            spotifyTrack = track,
                                            albumQueue = {
                                                com.metrolist.music.playback.queues.SpotifyPlaylistQueue(
                                                    playlistId = "album_${album.id}",
                                                    initialTracks = list,
                                                    startIndex = i,
                                                    mapper = mapper,
                                                )
                                            },
                                            navController = navController,
                                            isCurrentTrack = false,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }
                                },
                            ),
                    )
                }
            }
            // How long the release is, as at the foot of an album.
            val totalMs = list.sumOf { it.durationMs.toLong() }
            if (totalMs > 0) {
                Text(
                    listOf(
                        androidx.compose.ui.res.pluralStringResource(R.plurals.n_song, list.size, list.size),
                        makeTimeString(totalMs),
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
