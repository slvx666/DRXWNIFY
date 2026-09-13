/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.album

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyAlbumScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: SpotifyAlbumViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val downloadUtil = LocalDownloadUtil.current
    val downloadProgress by SpotifyBatchDownload.progress.collectAsState()

    val album by viewModel.album.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()

    // Downloaded state per track: SpotifyTrack has no YT id, so map spotifyId → resolved youtubeId
    // via the spotify_match cache, then observe the live download map keyed on youtube id.
    val downloads by downloadUtil.downloads.collectAsState()
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

    // "Add to Spotify" state: reflects whether the album is in the linked account's library.
    val spotifyAuthed = remember { com.metrolist.spotify.Spotify.isAuthenticated() }
    var isAlbumSaved by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(album?.id, spotifyAuthed) {
        val id = album?.id
        if (spotifyAuthed && id != null) {
            com.metrolist.spotify.Spotify.isAlbumSaved(id).onSuccess { isAlbumSaved = it }
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
        val ytId = mediaMetadata?.id
        value = if (ytId != null) {
            withContext(Dispatchers.IO) { database.getSpotifyMatchByYouTubeId(ytId)?.spotifyId }
        } else {
            null
        }
    }

    val lazyListState = rememberLazyListState()

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
                        AsyncImage(
                            model = thumbnailUrl,
                            contentDescription = album?.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(240.dp)
                                .clip(RoundedCornerShape(ThumbnailCornerRadius)),
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

                    if (!isLoading && tracks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row {
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
                            OutlinedButton(
                                enabled = !isDownloading,
                                onClick = {
                                    val toDownload = tracks
                                    if (toDownload.isEmpty()) return@OutlinedButton
                                    val albumLabel = album?.name ?: ""
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
                                        tracks = toDownload,
                                        mapper = viewModel.mapper,
                                        label = albumLabel,
                                        downloads = downloadUtil.downloads,
                                        onFinished = { result ->
                                            android.widget.Toast.makeText(
                                                appContext,
                                                appContext.getString(
                                                    R.string.spotify_dl_finished,
                                                    result.current, result.skipped, result.failed,
                                                ),
                                                android.widget.Toast.LENGTH_LONG,
                                            ).show()
                                        },
                                    )
                                },
                            ) {
                                Icon(
                                    painterResource(R.drawable.download),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(stringResource(R.string.action_download))
                            }

                            // Add-to-Spotify toggle (only when a Spotify account is linked).
                            // P11: always render the toggle once authed — previously it was hidden
                            // until the saved-state probe returned, so on a slow/failed probe the
                            // user had no way to save the album. Default to "not saved" until known.
                            if (spotifyAuthed) {
                                val saved = isAlbumSaved ?: false
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        val id = album?.id ?: return@OutlinedButton
                                        val target = !saved
                                        isAlbumSaved = target
                                        coroutineScope.launch {
                                            val res = if (target) {
                                                com.metrolist.spotify.Spotify.saveAlbum(id)
                                            } else {
                                                com.metrolist.spotify.Spotify.removeAlbum(id)
                                            }
                                            res.onFailure {
                                                // Revert and surface the failure so a silent
                                                // sync error is visible instead of a stuck icon.
                                                isAlbumSaved = saved
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Spotify: ${it.message ?: "sync failed"}",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        painterResource(
                                            if (saved) R.drawable.library_add_check else R.drawable.library_add,
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }

                        // Staged progress: Searching → Downloading → Formatting, with skipped/failed.
                        downloadProgress?.let { p ->
                            Spacer(modifier = Modifier.height(12.dp))
                            val text = when (p.phase) {
                                SpotifyBatchDownload.Phase.SEARCHING ->
                                    stringResource(R.string.spotify_dl_searching, p.current, p.total)
                                SpotifyBatchDownload.Phase.DOWNLOADING ->
                                    stringResource(R.string.spotify_dl_downloading, p.current, p.total)
                                SpotifyBatchDownload.Phase.FORMATTING ->
                                    stringResource(R.string.spotify_dl_formatting)
                                SpotifyBatchDownload.Phase.DONE ->
                                    stringResource(R.string.spotify_dl_downloading, p.current, p.total)
                            }
                            val extra = buildList {
                                if (p.skipped > 0) add("⃠ ${p.skipped}")
                                if (p.failed > 0) add("✕ ${p.failed}")
                            }.joinToString("  ")
                            Text(
                                text = if (extra.isEmpty()) text else "$text   $extra",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { p.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
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
                val trackDownloadState = spotifyToYt[track.id]?.let { downloads[it]?.state }
                val trackLiked = likedTracks.contains(track.id)
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
                        com.metrolist.music.ui.component.Icon.Download(trackDownloadState)
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
                        .clickable {
                            val albumId = album?.id ?: return@clickable
                            playerConnection.playQueue(
                                SpotifyPlaylistQueue(
                                    playlistId = "album_$albumId",
                                    initialTracks = tracks,
                                    startIndex = index,
                                    mapper = viewModel.mapper,
                                )
                            )
                        }
                        .animateItem(),
                )
            }
        }

        TopAppBar(
            title = { Text(album?.name ?: stringResource(R.string.albums)) },
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
