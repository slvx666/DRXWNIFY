/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.widget.Toast
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.net.toUri
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.YouTubeMatchDialog
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberPreference
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Context menu for Spotify tracks that haven't been resolved to a Room [Song] yet.
 * Provides the most common actions: play next, add to queue, change YouTube match,
 * and (when inside a playlist context) remove from playlist.
 *
 * @param onRemoveFromPlaylist When non-null, shows a "Remove from playlist" action.
 *   The callback is invoked when the user confirms removal.
 */
@Composable
fun SpotifyTrackMenu(
    track: SpotifyTrack,
    mapper: SpotifyYouTubeMapper,
    onDismiss: () -> Unit,
    navController: NavController? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val coroutineScope = rememberCoroutineScope()

    // Like state comes from the account's own "Liked Songs", shared with every other heart in the app.
    val likedIds by com.metrolist.music.playback.SpotifyLikeCache.liked.collectAsState()
    val isLiked = track.id in likedIds
    LaunchedEffect(track.id) {
        com.metrolist.music.playback.SpotifyLikeCache.ensureLoaded(listOf(track.id))
    }
    val likeScale by animateFloatAsState(
        targetValue = if (isLiked) 1.15f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 600f),
        label = "likeScale",
    )

    // Download state: the track may be downloaded under its YouTube id or its fallback id.
    val downloadUtil = com.metrolist.music.LocalDownloadUtil.current
    val downloads by downloadUtil.downloads.collectAsState()
    val downloadCandidates by produceState(initialValue = emptyList<String>(), track.id) {
        value = withContext(Dispatchers.IO) {
            listOfNotNull(
                runCatching { database.getSpotifyMatch(track.id)?.youtubeId }.getOrNull(),
                com.metrolist.music.resolver.FallbackIds.of(track.id),
            )
        }
    }
    val downloadedId = downloadCandidates.firstOrNull {
        downloads[it]?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
    }
    val downloadingId = downloadCandidates.firstOrNull {
        val state = downloads[it]?.state
        state == androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING ||
            state == androidx.media3.exoplayer.offline.Download.STATE_QUEUED
    }

    var showYouTubeMatchDialog by rememberSaveable { mutableStateOf(false) }
    var showAddToPlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var showAddToLocalPlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var showSelectArtistDialog by rememberSaveable { mutableStateOf(false) }


    fun resolveAndNavigateToArtist(artistName: String) {
        coroutineScope.launch {
            val ytArtistId = withContext(Dispatchers.IO) {
                runCatching {
                    YouTube.search(artistName, YouTube.SearchFilter.FILTER_ARTIST)
                        .getOrNull()
                        ?.items
                        ?.firstOrNull { it is ArtistItem }
                        ?.id
                }.getOrNull()
            }
            if (ytArtistId != null) {
                navController?.navigate("artist/$ytArtistId")
                onDismiss()
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.spotify_no_tracks),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    if (showSelectArtistDialog) {
        ListDialog(
            onDismiss = { showSelectArtistDialog = false },
        ) {
            items(track.artists) { artist ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(ListItemHeight)
                        .clickable {
                            showSelectArtistDialog = false
                            resolveAndNavigateToArtist(artist.name)
                        }
                        .padding(horizontal = 12.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .fillParentMaxWidth()
                            .height(ListItemHeight)
                            .padding(horizontal = 24.dp),
                    ) {
                        Text(
                            text = artist.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    val currentMatch by produceState<com.metrolist.music.db.entities.SpotifyMatchEntity?>(
        initialValue = null,
        track.id,
    ) {
        withContext(Dispatchers.IO) {
            value = database.getSpotifyMatch(track.id)
        }
    }

    if (showYouTubeMatchDialog) {
        YouTubeMatchDialog(
            currentYouTubeId = currentMatch?.youtubeId,
            onConfirm = { result ->
                coroutineScope.launch(Dispatchers.IO) {
                    mapper.overrideMatch(
                        spotifyId = track.id,
                        youtubeId = result.videoId,
                        title = result.title,
                        artist = result.artist,
                    )
                }
            },
            onDismiss = { showYouTubeMatchDialog = false },
        )
    }

    val thumbnailUrl = SpotifyMapper.getTrackThumbnail(track)

    ListItem(
        headlineContent = {
            Text(
                text = track.name,
                modifier = Modifier.basicMarquee(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = joinByBullet(
                    track.artists.joinToString { it.name },
                    makeTimeString(track.durationMs.toLong()),
                ),
            )
        },
        leadingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(ListThumbnailSize)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius)),
            ) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ThumbnailCornerRadius)),
                )
            }
        },
    )

    Spacer(modifier = Modifier.height(12.dp))

    Material3MenuGroup(
        items = listOfNotNull(
            Material3MenuItemData(
                title = {
                    Text(text = stringResource(if (isLiked) R.string.action_remove_like else R.string.action_like))
                },
                description = { Text(text = stringResource(R.string.track_like_desc)) },
                icon = {
                    Icon(
                        painter = painterResource(if (isLiked) R.drawable.favorite else R.drawable.favorite_border),
                        contentDescription = null,
                        tint = if (isLiked) MaterialTheme.colorScheme.error else LocalContentColor.current,
                        modifier = Modifier.graphicsLayer {
                            scaleX = likeScale
                            scaleY = likeScale
                        },
                    )
                },
                onClick = {
                    val target = !isLiked
                    // Flip the heart immediately; put it back if the account refuses the change.
                    com.metrolist.music.playback.SpotifyLikeCache.setLiked(track.id, target)
                    coroutineScope.launch(Dispatchers.IO) {
                        val result = if (target) {
                            com.metrolist.music.catalog.Catalog.saveTrack(track.id)
                        } else {
                            com.metrolist.music.catalog.Catalog.removeTrack(track.id)
                        }
                        result.onFailure {
                            com.metrolist.music.playback.SpotifyLikeCache.setLiked(track.id, !target)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, it.message ?: "", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
            ),
            Material3MenuItemData(
                title = {
                    Text(
                        text = stringResource(
                            when {
                                downloadedId != null -> R.string.remove_download
                                downloadingId != null -> R.string.cancel
                                else -> R.string.action_download
                            },
                        ),
                    )
                },
                description = { Text(text = stringResource(R.string.track_download_desc)) },
                icon = {
                    Icon(
                        painter = painterResource(if (downloadedId != null) R.drawable.offline else R.drawable.download),
                        contentDescription = null,
                    )
                },
                onClick = {
                    val existing = downloadedId ?: downloadingId
                    if (existing != null) {
                        androidx.media3.exoplayer.offline.DownloadService.sendRemoveDownload(
                            context,
                            com.metrolist.music.playback.ExoDownloadService::class.java,
                            existing,
                            false,
                        )
                        return@Material3MenuItemData
                    }
                    onDismiss()
                    Toast.makeText(context, context.getString(R.string.download_starting), Toast.LENGTH_SHORT).show()
                    val appContext = context.applicationContext
                    coroutineScope.launch(Dispatchers.IO) {
                        val mediaItem = mapper.resolveToMediaItem(track)
                        if (mediaItem == null) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(appContext, appContext.getString(R.string.spotify_no_tracks), Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                        val request = androidx.media3.exoplayer.offline.DownloadRequest
                            .Builder(mediaItem.mediaId, mediaItem.mediaId.toUri())
                            .setCustomCacheKey(mediaItem.mediaId)
                            .setData(track.name.toByteArray())
                            .build()
                        androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                            appContext,
                            com.metrolist.music.playback.ExoDownloadService::class.java,
                            request,
                            false,
                        )
                    }
                },
            ),
            downloadedId?.let { id ->
                Material3MenuItemData(
                title = { Text(text = stringResource(R.string.open_file_location)) },
                description = { Text(text = stringResource(R.string.open_file_location_desc)) },
                icon = {
                    Icon(painter = painterResource(R.drawable.folder), contentDescription = null)
                },
                onClick = {
                    onDismiss()
                    val appContext = context.applicationContext
                    coroutineScope.launch {
                        com.metrolist.music.utils.openDownloadLocation(
                            appContext,
                            downloadUtil.downloadExporter,
                            id,
                        )
                    }
                },
                )
            },
            downloadedId?.let { id ->
                Material3MenuItemData(
                title = { Text(text = stringResource(R.string.share_file)) },
                description = { Text(text = stringResource(R.string.share_file_desc)) },
                icon = {
                    Icon(painter = painterResource(R.drawable.share), contentDescription = null)
                },
                onClick = {
                    onDismiss()
                    val appContext = context.applicationContext
                    coroutineScope.launch {
                        com.metrolist.music.utils.shareDownloadedFile(
                            appContext,
                            downloadUtil.downloadExporter,
                            id,
                        )
                    }
                },
                )
            },
            Material3MenuItemData(
                title = { Text(text = stringResource(R.string.play_next)) },
                description = { Text(text = stringResource(R.string.play_next_desc)) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.queue_play_next),
                        contentDescription = null,
                    )
                },
                onClick = {
                    onDismiss()
                    coroutineScope.launch {
                        val mediaItem = withContext(Dispatchers.IO) {
                            mapper.resolveToMediaItem(track)
                        }
                        if (mediaItem != null) {
                            playerConnection.playNext(mediaItem)
                            Toast.makeText(
                                context,
                                context.getString(R.string.added_to_play_next),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.spotify_no_tracks),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            ),
            Material3MenuItemData(
                title = { Text(text = stringResource(R.string.add_to_queue)) },
                description = { Text(text = stringResource(R.string.add_to_queue_desc)) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.queue_add),
                        contentDescription = null,
                    )
                },
                onClick = {
                    onDismiss()
                    coroutineScope.launch {
                        val mediaItem = withContext(Dispatchers.IO) {
                            mapper.resolveToMediaItem(track)
                        }
                        if (mediaItem != null) {
                            playerConnection.addToQueue(mediaItem)
                            Toast.makeText(
                                context,
                                context.getString(R.string.added_to_queue),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.spotify_no_tracks),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            ),
        ),
    )

    Material3MenuGroup(
        items = buildList {
            add(
                Material3MenuItemData(
                    title = { Text(text = stringResource(R.string.add_to_playlist)) },
                    description = { Text(text = stringResource(R.string.add_to_playlist_local_desc)) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.playlist_add),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        showAddToLocalPlaylistDialog = true
                    },
                ),
            )
            add(
                Material3MenuItemData(
                    title = { Text(text = stringResource(R.string.spotify_add_to_playlist)) },
                    description = { Text(text = stringResource(R.string.spotify_add_to_playlist_desc)) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.playlist_add),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        showAddToPlaylistDialog = true
                    },
                ),
            )
            run {
                add(
                    Material3MenuItemData(
                        title = { Text(text = stringResource(R.string.change_youtube_version)) },
                        description = { Text(text = stringResource(R.string.change_youtube_version_desc)) },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.link),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            showYouTubeMatchDialog = true
                        },
                    ),
                )
            }
        },
    )

    if (navController != null && track.artists.isNotEmpty()) {
        Material3MenuGroup(
            items = listOf(
                Material3MenuItemData(
                    title = { Text(text = stringResource(R.string.view_artist)) },
                    description = { Text(text = track.artists.joinToString { it.name }) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.artist),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        if (track.artists.size == 1) {
                            resolveAndNavigateToArtist(track.artists[0].name)
                        } else {
                            showSelectArtistDialog = true
                        }
                    },
                ),
            ),
        )
    }

    // The app's own playlists. The track is written to the database as it is, so a track whose
    // audio has not been resolved yet can be added just as well as a played one.
    AddToPlaylistDialog(
        isVisible = showAddToLocalPlaylistDialog,
        onGetSong = { listOf(mapper.persistWithoutResolving(track).id) },
        onGetSongIds = { listOf(mapper.persistWithoutResolving(track).id) },
        onDismiss = { showAddToLocalPlaylistDialog = false },
    )

    AddToSpotifyPlaylistFlow(
        showDialog = showAddToPlaylistDialog,
        youtubeId = track.id,
        title = track.name,
        artist = track.artists.firstOrNull()?.name ?: "",
        durationSec = track.durationMs / 1000,
        spotifyUri = track.uri ?: "spotify:track:${track.id}",
        mapper = mapper,
        onDismiss = { showAddToPlaylistDialog = false },
    )

    if (onRemoveFromPlaylist != null) {
        Material3MenuGroup(
            items = listOf(
                Material3MenuItemData(
                    title = { Text(text = stringResource(R.string.spotify_remove_from_playlist)) },
                    description = { Text(text = stringResource(R.string.spotify_remove_from_playlist_desc)) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.remove),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onDismiss()
                        onRemoveFromPlaylist()
                    },
                ),
            ),
        )
    }
}
