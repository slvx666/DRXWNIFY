/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.album

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import com.metrolist.music.ui.component.coverSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.only
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.playback.SpotifyBatchDownload
import com.metrolist.music.playback.queues.SpotifyPlaylistQueue
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.ItemThumbnail
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.LocalDatabase
import com.metrolist.music.viewmodels.SpotifyAlbumViewModel
import com.metrolist.spotify.SpotifyMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.ui.menu.PlayerMenu
import com.metrolist.music.utils.toSongItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyAlbumScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: SpotifyAlbumViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = com.metrolist.music.ui.component.LocalMenuState.current
    val database = LocalDatabase.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val downloadUtil = LocalDownloadUtil.current
    // Only THIS album's batch — the progress bar used to appear on every album.
    val allDownloadProgress by SpotifyBatchDownload.progressBySource.collectAsState()
    val downloadSourceId = "album_${viewModel.albumId}"
    val downloadProgress = allDownloadProgress[downloadSourceId]

    val album by viewModel.album.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()

    // Downloaded state per track: SpotifyTrack has no YT id, so map spotifyId → resolved youtubeId
    // via the spotify_match cache, then observe the live download map keyed on youtube id.
    val downloads by downloadUtil.visibleDownloads.collectAsState()
    val spotifyToYt by produceState<Map<String, String>>(initialValue = emptyMap(), tracks, downloadProgress) {
        value = if (tracks.isEmpty()) {
            emptyMap()
        } else {
            withContext(Dispatchers.IO) {
                database.getSpotifyMatchesBySpotifyIds(tracks.map { it.id })
                    .associate { it.spotifyId to it.youtubeId }
            }
        }
    }

    // Which album tracks are in the user's Spotify likes (for the heart badge).
    val likedTracks by com.metrolist.music.playback.SpotifyLikeCache.liked.collectAsState()
    LaunchedEffect(tracks) {
        if (tracks.isNotEmpty()) {
            com.metrolist.music.playback.SpotifyLikeCache.ensureLoaded(tracks.map { it.id })
        }
    }

    val currentSpotifyId by produceState<String?>(initialValue = null, mediaMetadata?.id) {
        value = com.metrolist.music.playback.SpotifyMetadataRegistry.catalogIdOf(database, mediaMetadata?.id)
    }

    val lazyListState = rememberLazyListState()
    val selection = com.metrolist.music.ui.component.rememberTrackSelection()

    /** Downloads [toDownload] (the whole album, or the tracks ticked in the selection). */
    fun downloadTracks(toDownload: List<com.metrolist.spotify.models.SpotifyTrack>) {
        if (toDownload.isEmpty()) return
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.spotify_download_started, toDownload.size),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        // Background scope: keep downloading even after the user opens
        // another album (previously this was cancelled on navigation).
        val appContext = context.applicationContext
        SpotifyBatchDownload.start(
            appContext = appContext,
            sourceId = downloadSourceId,
            tracks = toDownload,
            mapper = viewModel.mapper,
            label = album?.name ?: "",
            downloads = downloadUtil.downloads,
            database = database,
            onFinished = { result ->
                android.widget.Toast.makeText(
                    appContext,
                    appContext.getString(R.string.spotify_dl_finished, result.current, result.skipped, result.failed),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            item(key = "header") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    val thumbnailUrl = album?.images
                        ?.firstOrNull { (it.width ?: 0) >= 300 }?.url
                        ?: album?.images?.firstOrNull()?.url

                    if (thumbnailUrl != null) {
                        // Tap: full screen (zoomable). Long press: saved to the gallery.
                        val coverViewer = com.metrolist.music.ui.component.rememberCoverViewerState()
                        val coverName = listOfNotNull(album?.artists?.firstOrNull()?.name, album?.name).joinToString(" - ")
                        val largestUrl = album?.images?.maxByOrNull { it.width ?: 0 }?.url ?: thumbnailUrl
                        coverViewer.Content()
                        AsyncImage(
                            model = thumbnailUrl,
                            contentDescription = album?.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(240.dp)
                                .coverSource(coverViewer)
                                .clip(RoundedCornerShape(ThumbnailCornerRadius))
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { coverViewer.open(largestUrl, coverName) },
                                        onLongPress = { coverViewer.showActions(largestUrl, coverName) },
                                    )
                                },
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Text(
                        text = album?.name ?: "",
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = album?.artists?.joinToString { it.name } ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    val metaInfo = buildList {
                        album?.albumType?.replaceFirstChar { it.uppercase() }?.let { add(it) }
                        album?.releaseDate?.take(4)?.let { add(it) }
                        if (tracks.isNotEmpty()) {
                            add(pluralStringResource(R.plurals.n_song, tracks.size, tracks.size))
                        }
                    }
                    if (metaInfo.isNotEmpty()) {
                        Text(
                            text = metaInfo.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val downloadedCount = com.metrolist.music.ui.component.rememberDownloadedCount(tracks)
                    if (!isLoading && tracks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    val albumId = album?.id ?: return@Button
                                    if (tracks.isEmpty()) return@Button
                                    playerConnection.playQueue(
                                        SpotifyPlaylistQueue(
                                            playlistId = "album_$albumId",
                                            initialTracks = tracks,
                                            startIndex = 0,
                                            mapper = viewModel.mapper,
                                        )
                                    )
                                },
                            ) {
                                Icon(
                                    painterResource(R.drawable.play),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(stringResource(R.string.play))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            val isDownloading = downloadProgress != null
                            val startAlbumDownload: () -> Unit = { downloadTracks(tracks) }
                            if (isDownloading) {
                                // Cancel this album's batch download.
                                OutlinedButton(onClick = { SpotifyBatchDownload.cancel(downloadSourceId) }) {
                                    Icon(painterResource(R.drawable.close), null, Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.size(8.dp))
                                    Text(stringResource(R.string.cancel))
                                }
                            } else com.metrolist.music.ui.component.DownloadOrShare(
                                downloaded = downloadedCount,
                                total = tracks.size,
                                downloadedIds = { SpotifyBatchDownload.downloadedMediaIds(database, tracks) },
                                onDownloadRest = startAlbumDownload,
                            ) { OutlinedButton(
                                onClick = startAlbumDownload,
                            ) {
                                Icon(
                                    painterResource(R.drawable.download),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(stringResource(R.string.action_download))
                            } }

                            // Like = album saved in the account's library (Spotify "Your Library" /
                            // Yandex likes). State is read from the account, so saving elsewhere shows here.
                            val albumSaved by viewModel.isSaved.collectAsState()
                            if (viewModel.canSave) {
                                Spacer(modifier = Modifier.width(4.dp))
                                androidx.compose.material3.IconButton(
                                    onClick = {
                                        viewModel.toggleSaved { error ->
                                            android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    enabled = albumSaved != null,
                                ) {
                                    Icon(
                                        painterResource(if (albumSaved == true) R.drawable.favorite else R.drawable.favorite_border),
                                        contentDescription = stringResource(
                                            if (albumSaved == true) R.string.remove_from_library else R.string.add_to_library,
                                        ),
                                        tint = if (albumSaved == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }

                        // What is on the device already, and what the running batch is doing.
                        com.metrolist.music.ui.component.BatchDownloadProgress(
                            progress = downloadProgress,
                            downloaded = downloadedCount,
                            total = tracks.size,
                        )
                    }
                }
            }

            if (isLoading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (error != null) {
                item(key = "error") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = error ?: "",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { viewModel.retry() },
                        ) {
                            Text(stringResource(R.string.retry_button))
                        }
                    }
                }
            }

            if (!isLoading && error == null && tracks.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.spotify_no_tracks),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            itemsIndexed(
                items = tracks,
                key = { index, track -> "track_${track.id}_$index" },
            ) { index, track ->
                val thumbnailUrl = SpotifyMapper.getTrackThumbnail(track)

                val isActive = currentSpotifyId != null && currentSpotifyId == track.id
                val trackDownloadState = (downloads[com.metrolist.music.resolver.FallbackIds.of(track.id)]
                    ?: spotifyToYt[track.id]?.let { downloads[it] })?.state
                val trackLiked = likedTracks.contains(track.id) ||
                    com.metrolist.music.playback.rememberCatalogTrackLiked(database, track.id, spotifyToYt[track.id])
                ListItem(
                    title = track.name,
                    subtitle = joinByBullet(
                        track.artists.joinToString { it.name },
                        makeTimeString(track.durationMs.toLong()),
                    ),
                    isActive = isActive,
                    badges = {
                        // Heart before the artist name if the track is in the user's Spotify likes.
                        if (trackLiked) {
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
                            com.metrolist.music.ui.component.Icon.Download(trackDownloadState)
                            com.metrolist.music.ui.component.TrackSelectionCheck(selection.active, selection.isSelected(track.id)) {
                                selection.toggle(track.id)
                            }
                        }
                    },
                    thumbnailContent = {
                        ItemThumbnail(
                            thumbnailUrl = thumbnailUrl,
                            isActive = isActive,
                            isPlaying = isPlaying,
                            shape = RoundedCornerShape(ThumbnailCornerRadius),
                            modifier = Modifier.size(ListThumbnailSize),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                if (selection.active) {
                                    selection.toggle(track.id)
                                    return@combinedClickable
                                }
                                val albumId = album?.id ?: return@combinedClickable
                                playerConnection.playQueue(
                                    SpotifyPlaylistQueue(
                                        playlistId = "album_$albumId",
                                        initialTracks = tracks,
                                        startIndex = index,
                                        mapper = viewModel.mapper,
                                    )
                                )
                            },
                            onLongClick = {
                                menuState.show {
                                    PlayerMenu(
                                        mediaMetadata = track.toSongItem().toMediaMetadata(),
                                        spotifyTrack = track,
                                        navController = navController,
                                        isCurrentTrack = false,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        )
                        .animateItem(),
                )
            }
            item(key = "selection_space") { com.metrolist.music.ui.component.TrackSelectionSpacer(selection) }
        }

        com.metrolist.music.ui.component.TrackSelectionBar(
            selection = selection,
            onDownload = { keys -> downloadTracks(tracks.filter { it.id in keys }); selection.stop() },
            onShare = { keys ->
                val picked = tracks.filter { it.id in keys }
                com.metrolist.music.ui.component.SelectedTracks.share(context, downloadUtil) {
                    com.metrolist.music.ui.component.SelectedTracks.mediaIdsOf(database, picked)
                }
            },
            onDelete = { keys ->
                val picked = tracks.filter { it.id in keys }
                com.metrolist.music.ui.component.SelectedTracks.delete(context, downloadUtil) {
                    com.metrolist.music.ui.component.SelectedTracks.mediaIdsOf(database, picked)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(LocalPlayerAwareWindowInsets.current.only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom).asPaddingValues()),
        )

        TopAppBar(
            title = { Text(album?.name ?: stringResource(R.string.albums)) },
            actions = {
                com.metrolist.music.ui.component.TrackSelectionActions(selection, tracks.map { it.id })
            },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
        )
    }
}
