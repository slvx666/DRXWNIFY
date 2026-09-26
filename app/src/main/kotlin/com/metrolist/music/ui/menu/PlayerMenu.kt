/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import androidx.datastore.preferences.core.edit
import com.metrolist.music.utils.dataStore
import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import com.metrolist.innertube.YouTube
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.constants.VarispeedKey
import com.metrolist.music.listentogether.ConnectionState
import com.metrolist.music.listentogether.ListenTogetherEvent
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.playback.SpotifyMetadataRegistry
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.music.utils.SPOTIFY_ID_PREFIX
import com.metrolist.music.utils.isSpotifyId
import com.metrolist.music.utils.stripSpotifyPrefix
import com.metrolist.music.ui.component.YouTubeMatchDialog
import com.metrolist.music.ui.component.BottomSheetState
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.Material3MenuGroup
import androidx.compose.ui.draw.clip
import coil3.compose.AsyncImage
import com.metrolist.music.ui.component.LocalBottomSheetPageState
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.NewAction
import com.metrolist.music.ui.component.NewActionGrid
import com.metrolist.music.ui.component.VolumeSlider
import com.metrolist.music.ui.utils.ShowMediaInfo
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round

@Composable
fun PlayerMenu(
    mediaMetadata: MediaMetadata?,
    navController: NavController,
    playerBottomSheetState: BottomSheetState? = null,
    isQueueTrigger: Boolean? = false,
    isCurrentTrack: Boolean = true,
    spotifyTrack: com.metrolist.spotify.models.SpotifyTrack? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onShowDetailsDialog: (() -> Unit)? = null,
    /** Actions only the calling screen knows (remove from history / cache…), after the main ones. */
    extraItems: List<Material3MenuItemData> = emptyList(),
    onDismiss: () -> Unit,
) {
    mediaMetadata ?: return
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val playerVolume = playerConnection.service.playerVolume.collectAsState()
    val bottomSheetPageState = LocalBottomSheetPageState.current

    // Cast state for volume control - safely access castConnectionHandler to prevent crashes
    val castHandler =
        remember(playerConnection) {
            try {
                playerConnection.service.castConnectionHandler
            } catch (e: Exception) {
                null
            }
        }
    val isCasting by castHandler?.isCasting?.collectAsState() ?: remember { mutableStateOf(false) }
    val castVolume by castHandler?.castVolume?.collectAsState() ?: remember { mutableFloatStateOf(1f) }
    val castDeviceName by castHandler?.castDeviceName?.collectAsState() ?: remember { mutableStateOf<String?>(null) }

    val varispeedMode by rememberPreference(VarispeedKey, defaultValue = false)

    val librarySong by database.song(mediaMetadata.id).collectAsState(initial = null)
    val coroutineScope = rememberCoroutineScope()

    val downloadUtil = LocalDownloadUtil.current
    val downloads by downloadUtil.downloads.collectAsState()
    val directDownload by downloadUtil
        .getDownload(mediaMetadata.id)
        .collectAsState(initial = null)

    val downloadCandidates by produceState(initialValue = listOf(mediaMetadata.id), mediaMetadata.id, spotifyTrack?.id) {
        value = withContext(Dispatchers.IO) {
            val sId = spotifyTrack?.id
                ?: (if (mediaMetadata.id.isSpotifyId()) mediaMetadata.id.stripSpotifyPrefix() else null)
                ?: database.getSpotifyMatchByYouTubeId(mediaMetadata.id)?.spotifyId
            listOfNotNull(
                mediaMetadata.id,
                sId?.let { database.getSpotifyMatch(it)?.youtubeId },
                sId?.let { com.metrolist.music.resolver.FallbackIds.of(it) },
            ).distinct()
        }
    }
    val downloadedCandidateId = downloadCandidates.firstOrNull {
        downloads[it]?.state == Download.STATE_COMPLETED
    }
    val downloadingCandidateId = downloadCandidates.firstOrNull {
        val s = downloads[it]?.state
        s == Download.STATE_DOWNLOADING || s == Download.STATE_QUEUED
    }
    val effectiveDownloadState = when {
        downloadedCandidateId != null -> Download.STATE_COMPLETED
        downloadingCandidateId != null -> Download.STATE_DOWNLOADING
        else -> directDownload?.state
    }
    val effectiveDownloadId = downloadedCandidateId ?: downloadingCandidateId ?: mediaMetadata.id

    // Recover the originating Spotify track for the current media so "View artist" / "View album"
    // work for Spotify-sourced tracks (whose YouTube MediaItem carries no album and often id-less
    // artists). Prefer the in-memory registry; if it was evicted (e.g. after a process restart —
    // this is why the buttons "often" disappeared), fall back to the spotify_match table + a one-shot
    // Spotify.getTrack so the real album/artist ids are always available.
    val recoveredSpotifyTrack by produceState<com.metrolist.spotify.models.SpotifyTrack?>(
        initialValue = mediaMetadata.id?.let { SpotifyMetadataRegistry.get(it) },
        mediaMetadata.id,
    ) {
        val fromRegistry = mediaMetadata.id?.let { SpotifyMetadataRegistry.get(it) }
        if (fromRegistry != null) {
            value = fromRegistry
        } else {
            value = kotlinx.coroutines.withContext(Dispatchers.IO) {
                val sid = database.getSpotifyMatchByYouTubeId(mediaMetadata.id)?.spotifyId
                sid?.let { com.metrolist.music.catalog.Catalog.getTrack(it).getOrNull() }
            }
        }
    }

    // Artist links for "View artist": prefer the recovered Spotify track's artists (real Spotify ids),
    // falling back to the media artists.
    val menuArtists: List<Pair<String, String?>> =
        recoveredSpotifyTrack
            ?.artists
            ?.filter { it.name.isNotBlank() }
            ?.map { a -> a.name to a.id?.let { "$SPOTIFY_ID_PREFIX$it" } }
            ?.takeIf { it.isNotEmpty() }
            ?: mediaMetadata.artists.map { it.name to it.id }
                .takeIf { list -> list.any { it.second != null } }
            // Persisted song row (Spotify metadata is written there on every resolve) — works after a
            // restart without the rate-limited REST track lookup.
            ?: librarySong?.orderedArtists?.map { it.name to it.id }
            ?: mediaMetadata.artists.map { it.name to it.id }
    val navigableArtists = menuArtists.filter { it.second != null }

    // Album link for "View album": prefer the recovered Spotify track's album (spotify_album/{id}),
    // else the media's own album (album/{id}). null when neither is known.
    val spotifyAlbumId = recoveredSpotifyTrack?.album?.id?.takeIf { it.isNotBlank() }
        ?: mediaMetadata.album?.id?.takeIf { it.isSpotifyId() }?.stripSpotifyPrefix()
        ?: librarySong?.song?.albumId?.takeIf { it.isSpotifyId() }?.stripSpotifyPrefix()
        // Older rows/items carried the bare 22-char Spotify album id.
        ?: mediaMetadata.album?.id?.takeIf { it.matches(Regex("^[0-9A-Za-z]{22}$")) }

    // Navigate to an artist by link id (Spotify ids open the Spotify artist screen).
    val openArtist: (String) -> Unit = { navId ->
        if (navId.isSpotifyId()) {
            navController.navigate("spotify_artist/${navId.stripSpotifyPrefix()}")
        } else {
            navController.navigate("artist/$navId")
        }
        playerBottomSheetState?.collapseSoft()
        onDismiss()
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showListenTogetherDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showYouTubeMatchDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showQobuzMatchDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showAddToSpotifyPlaylist by rememberSaveable { mutableStateOf(false) }
    val spotifyMapper = remember { SpotifyYouTubeMapper(database) }

    val playNextTrack: () -> Unit = {
        onDismiss()
        coroutineScope.launch {
            if (spotifyTrack != null) {
                val item = withContext(Dispatchers.IO) {
                    spotifyMapper.resolveToMediaItem(spotifyTrack)
                }
                if (item != null) {
                    playerConnection.playNext(item)
                    Toast.makeText(context, context.getString(R.string.added_to_play_next), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.spotify_no_tracks), Toast.LENGTH_SHORT).show()
                }
            } else if (mediaMetadata.id.isSpotifyId()) {
                val sid = mediaMetadata.id.stripSpotifyPrefix()
                val spTrack = withContext(Dispatchers.IO) {
                    com.metrolist.music.catalog.Catalog.getTrack(sid).getOrNull()
                }
                if (spTrack != null) {
                    val item = withContext(Dispatchers.IO) {
                        spotifyMapper.resolveToMediaItem(spTrack)
                    }
                    if (item != null) {
                        playerConnection.playNext(item)
                        Toast.makeText(context, context.getString(R.string.added_to_play_next), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, context.getString(R.string.spotify_no_tracks), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    playerConnection.playNext(mediaMetadata.toMediaItem())
                    Toast.makeText(context, context.getString(R.string.added_to_play_next), Toast.LENGTH_SHORT).show()
                }
            } else {
                playerConnection.playNext(mediaMetadata.toMediaItem())
                Toast.makeText(context, context.getString(R.string.added_to_play_next), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val addToQueueTrack: () -> Unit = {
        onDismiss()
        coroutineScope.launch {
            if (spotifyTrack != null) {
                val item = withContext(Dispatchers.IO) {
                    spotifyMapper.resolveToMediaItem(spotifyTrack)
                }
                if (item != null) {
                    playerConnection.addToQueue(item)
                    Toast.makeText(context, context.getString(R.string.added_to_queue), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.spotify_no_tracks), Toast.LENGTH_SHORT).show()
                }
            } else if (mediaMetadata.id.isSpotifyId()) {
                val sid = mediaMetadata.id.stripSpotifyPrefix()
                val spTrack = withContext(Dispatchers.IO) {
                    com.metrolist.music.catalog.Catalog.getTrack(sid).getOrNull()
                }
                if (spTrack != null) {
                    val item = withContext(Dispatchers.IO) {
                        spotifyMapper.resolveToMediaItem(spTrack)
                    }
                    if (item != null) {
                        playerConnection.addToQueue(item)
                        Toast.makeText(context, context.getString(R.string.added_to_queue), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, context.getString(R.string.spotify_no_tracks), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    playerConnection.addToQueue(mediaMetadata.toMediaItem())
                    Toast.makeText(context, context.getString(R.string.added_to_queue), Toast.LENGTH_SHORT).show()
                }
            } else {
                playerConnection.addToQueue(mediaMetadata.toMediaItem())
                Toast.makeText(context, context.getString(R.string.added_to_queue), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val resolvedSpotifyMatch by produceState<com.metrolist.music.db.entities.SpotifyMatchEntity?>(
        initialValue = null,
        mediaMetadata.id,
    ) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            value = database.getSpotifyMatchByYouTubeId(mediaMetadata.id)
        }
    }

    AddToSpotifyPlaylistFlow(
        showDialog = showAddToSpotifyPlaylist,
        youtubeId = mediaMetadata.id,
        title = mediaMetadata.title,
        artist = mediaMetadata.artists.firstOrNull()?.name ?: "",
        durationSec = mediaMetadata.duration,
        spotifyUri = resolvedSpotifyMatch?.spotifyId?.let { "spotify:track:$it" }
            ?: (if (mediaMetadata.id.isSpotifyId()) "spotify:track:${mediaMetadata.id.stripSpotifyPrefix()}" else null)
            ?: (spotifyTrack?.id?.let { "spotify:track:$it" }),
        mapper = spotifyMapper,
        onDismiss = { showAddToSpotifyPlaylist = false },
    )

    var showVersionDialog by rememberSaveable { mutableStateOf(false) }
    if (showVersionDialog) {
        com.metrolist.music.ui.dialog.TrackVersionDialog(
            mediaMetadata = mediaMetadata,
            onDismiss = { showVersionDialog = false },
        )
    }

    val listenTogetherManager = LocalListenTogetherManager.current
    val listenTogetherRoleState = listenTogetherManager?.role?.collectAsState(initial = com.metrolist.music.listentogether.RoomRole.NONE)
    val isListenTogetherGuest = listenTogetherRoleState?.value == com.metrolist.music.listentogether.RoomRole.GUEST
    val pendingSuggestions by listenTogetherManager?.pendingSuggestions?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList()) }

    AddToPlaylistDialog(
        isVisible = showChoosePlaylistDialog,
        onGetSong = { playlist ->
            database.withTransaction {
                insert(mediaMetadata)
            }
            coroutineScope.launch(Dispatchers.IO) {
                if (!mediaMetadata.id.isSpotifyId()) {
                    playlist.playlist.browseId?.let { YouTube.addToPlaylist(it, mediaMetadata.id) }
                }
            }
            listOf(mediaMetadata.id)
        },
        onGetSongIds = { listOf(mediaMetadata.id) },
        onDismiss = {
            showChoosePlaylistDialog = false
        },
    )

    ListenTogetherDialog(
        visible = showListenTogetherDialog,
        mediaMetadata = mediaMetadata,
        onDismiss = { showListenTogetherDialog = false },
    )

    var showSelectArtistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showSelectArtistDialog) {
        ListDialog(
            onDismiss = { showSelectArtistDialog = false },
        ) {
            items(navigableArtists) { artist ->
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier =
                        Modifier
                            .fillParentMaxWidth()
                            .height(ListItemHeight)
                            .clickable {
                                showSelectArtistDialog = false
                                artist.second?.let { openArtist(it) }
                            }.padding(horizontal = 24.dp),
                ) {
                    Text(
                        text = artist.first,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    var showPitchTempoDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showPitchTempoDialog) {
        TempoPitchDialog(
            onDismiss = { showPitchTempoDialog = false },
        )
    }

    var showSpeedDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showSpeedDialog) {
        SpeedDialog(
            onDismiss = { showSpeedDialog = false },
        )
    }

    // Optional cast indicator (only while casting). The old leading divider/volume "strip" was
    // removed, so the menu content starts right at the top.
    if (isCasting && castDeviceName != null) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp, bottom = 4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.cast),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.casting_to, castDeviceName ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // "Add to queue" / "Play next" as one group, first outside the full-screen player.
    val QueueActions: @Composable () -> Unit = {
        Material3MenuGroup(
            items = listOf(
                Material3MenuItemData(
                    title = { Text(text = stringResource(R.string.add_to_queue)) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.queue_music),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    onClick = addToQueueTrack,
                ),
                Material3MenuItemData(
                    title = { Text(text = stringResource(R.string.queue_play_next)) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.queue_play_next),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    onClick = playNextTrack,
                ),
            ),
        )
    }

    // The heart in the header: liked in the app or on the account, with the plus when the track
    // can only be kept in the app (see LikeTarget).
    // What a download of this track would be, when known (the stream it plays from).
    val trackFormat by remember(mediaMetadata.id) { database.format(mediaMetadata.id) }.collectAsState(initial = null)
    val audioSourceName by produceState<String?>(null, mediaMetadata.id) {
        value = withContext(Dispatchers.IO) {
            com.metrolist.music.resolver.AudioFallbackEngine
                .sourceOf(mediaMetadata.id, database.getSongByIdBlocking(mediaMetadata.id))
                ?.let { com.metrolist.music.ui.component.providerLabel(it) }
        }
    }
    val downloadQuality = com.metrolist.music.ui.component.AudioQualityLevel.summary(
        source = audioSourceName,
        mimeType = trackFormat?.mimeType,
        codecs = trackFormat?.codecs,
        bitrate = trackFormat?.bitrate,
    )

    val headerCatalogId = spotifyTrack?.id ?: recoveredSpotifyTrack?.id
    val headerLiked = com.metrolist.music.playback.rememberTrackLiked(database, mediaMetadata.id, headerCatalogId)
    val headerLikeSyncs = com.metrolist.music.playback.rememberLikeSyncs(database, mediaMetadata.id, headerCatalogId)
    // Where the like goes besides the app: the account that owns the track, or VK for a VK track.
    val likeAccount: Triple<Int, Int, Int>? = when {
        !headerLikeSyncs -> null
        com.metrolist.music.resolver.SourceSearch.providerOf(mediaMetadata.id) ==
            com.metrolist.music.resolver.AudioProviderId.VK -> Triple(R.string.like_add_vk, R.string.like_remove_vk, R.drawable.vk_music)
        headerCatalogId != null && com.metrolist.music.catalog.Catalog.isYandexId(headerCatalogId) ->
            Triple(R.string.like_add_yandex, R.string.like_remove_yandex, R.drawable.yandex_music)
        else -> Triple(R.string.like_add_spotify, R.string.like_remove_spotify, R.drawable.spotify)
    }

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    LazyColumn(
        contentPadding =
            PaddingValues(
                start = 0.dp,
                top = 0.dp,
                end = 0.dp,
                bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
            ),
    ) {
        // Every track menu in the app is this one. Outside the full-screen player it opens with the
        // track itself (cover, title, artist and its heart) and the queue actions come first.
        if (!isCurrentTrack) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = mediaMetadata.thumbnailUrl ?: spotifyTrack?.album?.images?.firstOrNull()?.url,
                        contentDescription = null,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mediaMetadata.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = mediaMetadata.artists.joinToString { it.name },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = { playerConnection.service.setTrackLiked(mediaMetadata, !headerLiked) },
                    ) {
                        Icon(
                            painter = painterResource(
                                com.metrolist.music.playback.LikeTarget.icon(headerLiked, headerLikeSyncs),
                            ),
                            contentDescription = null,
                            tint = if (headerLiked) MaterialTheme.colorScheme.error else androidx.compose.material3.LocalContentColor.current,
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
            item { QueueActions() }
            item { Spacer(modifier = Modifier.height(12.dp)) }
        }

        item {
            Material3MenuGroup(
                items =
                    buildList {
                        when (effectiveDownloadState) {
                            Download.STATE_COMPLETED -> {
                                add(
                                    Material3MenuItemData(
                                        title = {
                                            Text(
                                                text = stringResource(R.string.remove_download),
                                            )
                                        },
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.offline),
                                                contentDescription = null,
                                                modifier = Modifier.size(24.dp),
                                            )
                                        },
                                        onClick = {
                                            DownloadService.sendRemoveDownload(
                                                context,
                                                ExoDownloadService::class.java,
                                                effectiveDownloadId,
                                                false,
                                            )
                                        },
                                    )
                                )
                            }

                            Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                                add(
                                    Material3MenuItemData(
                                        title = { Text(text = stringResource(R.string.downloading)) },
                                        icon = {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        },
                                        onClick = {
                                            DownloadService.sendRemoveDownload(
                                                context,
                                                ExoDownloadService::class.java,
                                                effectiveDownloadId,
                                                false,
                                            )
                                        },
                                    )
                                )
                            }

                            else -> {
                                add(
                                    Material3MenuItemData(
                                        title = {
                                            // "Download (VK · MP3 · 320 kbps)": what the file will be.
                                            Text(
                                                text = stringResource(R.string.action_download) +
                                                    (downloadQuality?.let { " ($it)" } ?: ""),
                                            )
                                        },
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.download),
                                                contentDescription = null,
                                                modifier = Modifier.size(24.dp),
                                            )
                                        },
                                        onClick = {
                                            if (spotifyTrack != null || mediaMetadata.id.isSpotifyId()) {
                                                val appContext = context.applicationContext
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val trackToResolve = spotifyTrack ?: runCatching {
                                                        val sid = mediaMetadata.id.stripSpotifyPrefix()
                                                        com.metrolist.music.catalog.Catalog.getTrack(sid).getOrNull()
                                                    }.getOrNull()
                                                    val mediaItem = trackToResolve?.let { spotifyMapper.resolveToMediaItem(it) }
                                                    if (mediaItem == null) {
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(appContext, appContext.getString(R.string.spotify_no_tracks), Toast.LENGTH_SHORT).show()
                                                        }
                                                        return@launch
                                                    }
                                                    val request = DownloadRequest
                                                        .Builder(mediaItem.mediaId, mediaItem.mediaId.toUri())
                                                        .setCustomCacheKey(mediaItem.mediaId)
                                                        .setData(mediaMetadata.title.toByteArray())
                                                        .build()
                                                    DownloadService.sendAddDownload(
                                                        appContext,
                                                        ExoDownloadService::class.java,
                                                        request,
                                                        false,
                                                    )
                                                }
                                            } else {
                                                database.transaction {
                                                    upsertMetadata(mediaMetadata)
                                                }
                                                val downloadRequest =
                                                    DownloadRequest
                                                        .Builder(mediaMetadata.id, mediaMetadata.id.toUri())
                                                        .setCustomCacheKey(mediaMetadata.id)
                                                        .setData(mediaMetadata.title.toByteArray())
                                                        .build()
                                                DownloadService.sendAddDownload(
                                                    context,
                                                    ExoDownloadService::class.java,
                                                    downloadRequest,
                                                    false,
                                                )
                                            }
                                        },
                                    )
                                )
                            }
                        }


                        // The like of the account: into Spotify's (Yandex Music's, VK's) liked tracks.
                        likeAccount?.let { (addLabel, removeLabel, icon) ->
                            add(
                                Material3MenuItemData(
                                    title = {
                                        Text(
                                            text = stringResource(if (headerLiked) removeLabel else addLabel),
                                        )
                                    },
                                    icon = {
                                        Icon(
                                            painter = painterResource(icon),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    onClick = {
                                        playerConnection.service.setTrackLiked(mediaMetadata, !headerLiked)
                                        onDismiss()
                                    },
                                )
                            )
                        }

                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.add_to_playlist)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.playlist_add),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                onClick = {
                                    showChoosePlaylistDialog = true
                                },
                            )
                        )

                        if (effectiveDownloadState == Download.STATE_COMPLETED) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.open_file_location)) },
                                    description = { Text(text = stringResource(R.string.open_file_location_desc)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.folder),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    onClick = {
                                        onDismiss()
                                        val appContext = context.applicationContext
                                        coroutineScope.launch {
                                            com.metrolist.music.utils.openDownloadLocation(
                                                appContext,
                                                downloadUtil.downloadExporter,
                                                effectiveDownloadId,
                                            )
                                        }
                                    },
                                )
                            )
                        }

                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.share)) },
                                description = if (effectiveDownloadState == Download.STATE_COMPLETED) {
                                    { Text(text = stringResource(R.string.share_file_desc)) }
                                } else {
                                    null
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.share),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                onClick = {
                                    onDismiss()
                                    if (effectiveDownloadState == Download.STATE_COMPLETED) {
                                        // A downloaded track is shared as the file itself.
                                        val appContext = context.applicationContext
                                        coroutineScope.launch {
                                            com.metrolist.music.utils.shareDownloadedFile(
                                                appContext,
                                                downloadUtil.downloadExporter,
                                                effectiveDownloadId,
                                            )
                                        }
                                    } else {
                                        val link = shareLinkOf(mediaMetadata, spotifyTrack?.id ?: recoveredSpotifyTrack?.id)
                                        val intent = android.content.Intent().apply {
                                            action = android.content.Intent.ACTION_SEND
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, link)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(intent, null))
                                    }
                                },
                            )
                        )

                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.track_info)) },
                                description = { Text(text = stringResource(R.string.details_desc)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.info),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                onClick = {
                                    if (onShowDetailsDialog != null) {
                                        onShowDetailsDialog()
                                    } else {
                                        val detailsId = resolvedSpotifyMatch?.youtubeId ?: mediaMetadata.id
                                        bottomSheetPageState.show {
                                            ShowMediaInfo(detailsId)
                                        }
                                    }
                                    onDismiss()
                                },
                            ),
                        )

                        addAll(extraItems)

                        if (onRemoveFromPlaylist != null) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.remove_from_playlist)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.delete),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    onClick = {
                                        onDismiss()
                                        onRemoveFromPlaylist()
                                    },
                                )
                            )
                        }
                    },
            )
        }

        if (isCurrentTrack) {
            item { Spacer(modifier = Modifier.height(12.dp)) }
            item { QueueActions() }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        // View artist (right under Download) then View album.
        item {
            // Only real podcast episodes hide artist/album links. The old heuristic ("album id doesn't
            // start with MPREb_") treated every Spotify-sourced track (album id "spotify:…") as a
            // podcast — which is why "View artist" / "View album" were so often missing.
            val isPodcast = mediaMetadata.isEpisode
            Material3MenuGroup(
                items =
                    buildList {
                        if (navigableArtists.isNotEmpty() && !isPodcast) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.view_artist)) },
                                    description = {
                                        Text(
                                            text = menuArtists.joinToString { it.first },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.artist),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    onClick = {
                                        if (navigableArtists.size == 1) {
                                            navigableArtists.first().second?.let { openArtist(it) }
                                        } else {
                                            showSelectArtistDialog = true
                                        }
                                    },
                                ),
                            )
                        }
                        // Works for Spotify tracks via the recovered album id, not only tracks that
                        // carry a native album on the MediaItem.
                        val albumTitle = mediaMetadata.album?.title ?: recoveredSpotifyTrack?.album?.name
                            ?: librarySong?.song?.albumName
                        if ((mediaMetadata.album != null || spotifyAlbumId != null) && !isPodcast) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.view_album)) },
                                    description = {
                                        Text(
                                            text = albumTitle.orEmpty(),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.album),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    onClick = {
                                        val mediaAlbumId = mediaMetadata.album?.id
                                        val nativeAlbumId = mediaAlbumId?.takeUnless {
                                            it.isSpotifyId() || it.matches(Regex("^[0-9A-Za-z]{22}$"))
                                        }
                                        val spotifyTarget = spotifyAlbumId
                                            ?: mediaAlbumId?.takeIf { it.isSpotifyId() }?.stripSpotifyPrefix()
                                        when {
                                            // Prefer the exact Spotify album when known.
                                            spotifyTarget != null -> navController.navigate("spotify_album/$spotifyTarget")
                                            nativeAlbumId != null -> navController.navigate("album/$nativeAlbumId")
                                        }
                                        playerBottomSheetState?.collapseSoft()
                                        onDismiss()
                                    },
                                ),
                            )
                        }
                    },
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            val startingRadioText = stringResource(R.string.starting_radio)
            Material3MenuGroup(
                items =
                    buildList {
                        // "Start radio" as a normal row (matches the buttons below), placed above
                        // "Listen together". A track from the experimental source search has no
                        // catalog entry to build a radio from, so the row is left out.
                        val isSourceTrack =
                            com.metrolist.music.resolver.SourceSearch.isSourceTrack(mediaMetadata.id)
                        if (!isListenTogetherGuest && !isSourceTrack) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.start_radio)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.radio),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    onClick = {
                                        Toast.makeText(context, startingRadioText, Toast.LENGTH_SHORT).show()
                                        playerConnection.startRadioSeamlessly()
                                        onDismiss()
                                    },
                                ),
                            )
                        }
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.listen_together)) },
                                icon = {
                                    // Show a small badge when there are pending suggestions
                                    Box {
                                        Icon(
                                            painter = painterResource(R.drawable.group),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        if (pendingSuggestions.isNotEmpty()) {
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier =
                                                    Modifier
                                                        .offset(x = 8.dp, y = (-6).dp)
                                                        .align(Alignment.TopEnd),
                                            ) {
                                                Text(
                                                    text = pendingSuggestions.size.toString(),
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                        }
                                    }
                                },
                                onClick = { showListenTogetherDialog = true },
                            ),
                        )
                        if (isListenTogetherGuest) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.resync)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.replay),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    onClick = {
                                        listenTogetherManager.requestSync()
                                        onDismiss()
                                    },
                                ),
                            )
                        }
                    },
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Material3MenuGroup(
                items =
                    buildList {
                        // Other recordings of the track to pick from, closest first, with bitrates.
                        val canChangeVersion = !mediaMetadata.isEpisode &&
                            !com.metrolist.music.resolver.SourceSearch.isSourceTrack(mediaMetadata.id)
                        if (canChangeVersion) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.track_version)) },
                                    description = { Text(text = stringResource(R.string.track_version_desc)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.link),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    onClick = {
                                        showVersionDialog = true
                                    },
                                ),
                            )
                        }

                        if (isQueueTrigger != true) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.equalizer)) },
                                    description = { Text(text = stringResource(R.string.equalizer_desc)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.equalizer),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    onClick = {
                                        navController.navigate("equalizer")
                                        onDismiss()
                                    },
                                ),
                            )
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.advanced)) },
                                    description = { Text(text = stringResource(R.string.advanced_desc)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.tune),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    onClick = {
                                        if (!varispeedMode) showPitchTempoDialog = true
                                        else showSpeedDialog = true
                                    },
                                ),
                            )
                        }
                    },
            )
        }
    }
}

/** A link to [mediaMetadata] that opens anywhere: Spotify when it is a Spotify track, else YouTube Music. */
private fun shareLinkOf(mediaMetadata: MediaMetadata, spotifyId: String?): String {
    val id = mediaMetadata.id
    val catalogId = spotifyId
        ?: com.metrolist.music.resolver.FallbackIds.catalogIdOf(id)
        ?: id.takeIf { it.isSpotifyId() }?.stripSpotifyPrefix()
    val plain = "${mediaMetadata.artists.joinToString { it.name }} – ${mediaMetadata.title}"
    return when {
        com.metrolist.music.resolver.SourceSearch.isSourceTrack(id) -> plain
        catalogId != null && !com.metrolist.music.catalog.Catalog.isYandexId(catalogId) ->
            "https://open.spotify.com/track/$catalogId"
        catalogId != null || com.metrolist.music.resolver.FallbackIds.isFallbackId(id) -> plain
        else -> "https://music.youtube.com/watch?v=$id"
    }
}

@Composable
fun TempoPitchDialog(onDismiss: () -> Unit) {
    val playerConnection = LocalPlayerConnection.current ?: return
    var tempo by remember {
        mutableFloatStateOf(playerConnection.player.playbackParameters.speed)
    }
    var transposeValue by remember {
        mutableIntStateOf(round(12 * log2(playerConnection.player.playbackParameters.pitch)).toInt())
    }
    val updatePlaybackParameters = {
        playerConnection.player.playbackParameters =
            PlaybackParameters(tempo, 2f.pow(transposeValue.toFloat() / 12))
    }
    val listenTogetherManager = com.metrolist.music.LocalListenTogetherManager.current
    val isInRoom = listenTogetherManager?.isInRoom ?: false

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.tempo_and_pitch))
        },
        dismissButton = {
            TextButton(
                onClick = {
                    tempo = 1f
                    transposeValue = 0
                    updatePlaybackParameters()
                },
            ) {
                Text(stringResource(R.string.reset))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        text = {
            Column {
                if (!isInRoom) {
                    ValueAdjuster(
                        icon = R.drawable.speed,
                        currentValue = tempo,
                        values = (0..35).map { round((0.25f + it * 0.05f) * 100) / 100 },
                        onValueUpdate = {
                            tempo = it
                            updatePlaybackParameters()
                        },
                        valueText = { "x$it" },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                ValueAdjuster(
                    icon = R.drawable.discover_tune,
                    currentValue = transposeValue,
                    values = (-12..12).toList(),
                    onValueUpdate = {
                        transposeValue = it
                        updatePlaybackParameters()
                    },
                    valueText = { "${if (it > 0) "+" else ""}$it" },
                )
                BalanceAdjuster(modifier = Modifier.padding(top = 16.dp))
            }
        },
    )
}


/** Left/right balance slider, shared by the "Advanced" dialogs. */
@Composable
private fun BalanceAdjuster(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var balance by remember { mutableFloatStateOf(com.metrolist.music.playback.audio.StereoBalance.value) }
    fun apply(value: Float) {
        balance = value
        com.metrolist.music.playback.audio.StereoBalance.value = value
        scope.launch {
            context.dataStore.edit { it[com.metrolist.music.constants.StereoBalanceKey] = value }
        }
    }
    val leftPercent = (100 * (if (balance > 0f) 1f - balance else 1f)).toInt()
    val rightPercent = (100 * (if (balance < 0f) 1f + balance else 1f)).toInt()
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.stereo_balance),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.stereo_balance_value, leftPercent, rightPercent),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.stereo_left_short), style = MaterialTheme.typography.labelLarge)
            androidx.compose.material3.Slider(
                value = balance,
                onValueChange = { apply((it * 20).let(::round) / 20f) },
                valueRange = -1f..1f,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(stringResource(R.string.stereo_right_short), style = MaterialTheme.typography.labelLarge)
        }
        if (balance != 0f) {
            TextButton(onClick = { apply(0f) }) { Text(stringResource(R.string.stereo_balance_center)) }
        }
    }
}

@Composable
fun SpeedDialog(onDismiss: () -> Unit) {
    val playerConnection = LocalPlayerConnection.current ?: return
    var speed by remember {
        mutableFloatStateOf(playerConnection.player.playbackParameters.speed)
    }
    val updatePlaybackParameters = {
        playerConnection.player.playbackParameters =
            PlaybackParameters(speed, speed)
    }
    val listenTogetherManager = com.metrolist.music.LocalListenTogetherManager.current

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.speed))
        },
        dismissButton = {
            TextButton(
                onClick = {
                    speed = 1f
                    updatePlaybackParameters()
                },
            ) {
                Text(stringResource(R.string.reset))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        text = {
            Column {
                ValueAdjuster(
                    icon = R.drawable.speed,
                    currentValue = speed,
                    values = (0..35).map { round((0.25f + it * 0.05f) * 100) / 100 },
                    onValueUpdate = {
                        speed = it
                        updatePlaybackParameters()
                    },
                    valueText = { "x$it" },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                BalanceAdjuster(modifier = Modifier.padding(top = 4.dp))
            }
        },
    )
}
@Composable
fun <T> ValueAdjuster(
    @DrawableRes icon: Int,
    currentValue: T,
    values: List<T>,
    onValueUpdate: (T) -> Unit,
    valueText: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )

        IconButton(
            enabled = currentValue != values.first(),
            onClick = {
                onValueUpdate(values[values.indexOf(currentValue) - 1])
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.remove),
                contentDescription = null,
            )
        }

        Text(
            text = valueText(currentValue),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(80.dp),
        )

        IconButton(
            enabled = currentValue != values.last(),
            onClick = {
                onValueUpdate(values[values.indexOf(currentValue) + 1])
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.add),
                contentDescription = null,
            )
        }
    }
}

@Composable
fun ListenTogetherDialog(
    visible: Boolean,
    mediaMetadata: MediaMetadata?,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val listenTogetherManager = com.metrolist.music.LocalListenTogetherManager.current
    val joiningRoomTemplate = stringResource(R.string.joining_room)

    // Handle case where manager is not available
    if (listenTogetherManager == null) {
        ListDialog(onDismiss = onDismiss) {
            item {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.group),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.listen_together),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.listen_together_not_configured),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onDismiss,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            }
        }
        return
    }

    val connectionState by listenTogetherManager.connectionState.collectAsState()
    val roomState by listenTogetherManager.roomState.collectAsState()
    val userId by listenTogetherManager.userId.collectAsState()
    val pendingJoinRequests by listenTogetherManager.pendingJoinRequests.collectAsState()
    val pendingSuggestions by listenTogetherManager.pendingSuggestions.collectAsState()

    // Load saved username
    var savedUsername by rememberPreference(com.metrolist.music.constants.ListenTogetherUsernameKey, "")
    var roomCodeInput by rememberSaveable { mutableStateOf("") }
    var usernameInput by rememberSaveable { mutableStateOf(savedUsername) }

    // Local UI state for join/create actions
    var isCreatingRoom by rememberSaveable { mutableStateOf(false) }
    var isJoiningRoom by rememberSaveable { mutableStateOf(false) }
    var joinErrorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    // User action menu state
    var selectedUserForMenu by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedUsername by rememberSaveable { mutableStateOf<String?>(null) }

    // Localized helper strings
    val waitingForApprovalText = stringResource(R.string.waiting_for_approval)
    val invalidRoomCodeText = stringResource(R.string.invalid_room_code)
    val joinRequestDeniedText = stringResource(R.string.join_request_denied)

    // User action menu dialog
    if (selectedUserForMenu != null && selectedUsername != null) {
        ListDialog(
            onDismiss = {
                selectedUserForMenu = null
                selectedUsername = null
            },
        ) {
            item {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.group),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.manage_user),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = selectedUsername ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }

            // Kick button
            item {
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clickable {
                                selectedUserForMenu?.let {
                                    listenTogetherManager.kickUser(it, "Removed by host")
                                }
                                selectedUserForMenu = null
                                selectedUsername = null
                            },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.kick_user),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = stringResource(R.string.kick_user_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // Permanently kick button
            item {
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clickable {
                                selectedUserForMenu?.let { userId ->
                                    selectedUsername?.let { username ->
                                        listenTogetherManager.blockUser(username)
                                        listenTogetherManager.kickUser(userId, R.string.user_blocked_by_host.toString())
                                    }
                                }
                                selectedUserForMenu = null
                                selectedUsername = null
                            },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.permanently_kick_user),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.permanently_kick_user_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // Transfer ownership button
            item {
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clickable {
                                selectedUserForMenu?.let {
                                    listenTogetherManager.transferHost(it)
                                }
                                selectedUserForMenu = null
                                selectedUsername = null
                            },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.crown),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.transfer_ownership),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.transfer_ownership_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
        return
    }

    // Sync usernameInput when savedUsername changes
    LaunchedEffect(savedUsername) {
        if (usernameInput.isBlank() && savedUsername.isNotBlank()) {
            usernameInput = savedUsername
        }
    }

    // Listen to low level events to update UI state (join rejected, approved, room created)
    LaunchedEffect(listenTogetherManager) {
        listenTogetherManager.events.collect { event ->
            when (event) {
                is ListenTogetherEvent.JoinRejected -> {
                    val reason = event.reason
                    joinErrorMessage =
                        when {
                            reason.isNullOrBlank() -> joinRequestDeniedText
                            reason.contains("invalid", ignoreCase = true) == true -> invalidRoomCodeText
                            else -> "$joinRequestDeniedText: $reason"
                        }
                    isJoiningRoom = false
                    isCreatingRoom = false
                }

                is ListenTogetherEvent.JoinApproved -> {
                    isJoiningRoom = false
                    joinErrorMessage = null
                }

                is ListenTogetherEvent.RoomCreated -> {
                    isCreatingRoom = false
                    val clipboard =
                        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("ListenTogetherRoom", event.roomCode)
                    clipboard.setPrimaryClip(clip)
                }

                else -> { /* ignore other events here */ }
            }
        }
    }

    // Check if already in a room
    val isInRoom = listenTogetherManager.isInRoom
    val isHost = roomState?.hostId == userId

    ListDialog(onDismiss = onDismiss) {
        // Header - Icon on left, text left-aligned
        item {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Icon(
                    painter = painterResource(R.drawable.group),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text =
                        if (isInRoom) {
                            if (isHost) stringResource(R.string.hosting_room) else stringResource(R.string.in_room)
                        } else {
                            stringResource(R.string.listen_together)
                        },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Connection status
        item {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                color =
                    when (connectionState) {
                        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                        ConnectionState.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
                    },
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(10.dp)
                                    .background(
                                        color =
                                            when (connectionState) {
                                                ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                                                ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> MaterialTheme.colorScheme.secondary
                                                ConnectionState.ERROR -> MaterialTheme.colorScheme.error
                                                ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
                                            },
                                        shape = RoundedCornerShape(50),
                                    ),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text =
                                when (connectionState) {
                                    ConnectionState.CONNECTED -> stringResource(R.string.listen_together_connected)
                                    ConnectionState.CONNECTING -> stringResource(R.string.listen_together_connecting)
                                    ConnectionState.RECONNECTING -> stringResource(R.string.listen_together_reconnecting)
                                    ConnectionState.ERROR -> stringResource(R.string.listen_together_error)
                                    ConnectionState.DISCONNECTED -> stringResource(R.string.listen_together_disconnected)
                                },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color =
                                when (connectionState) {
                                    ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                                    ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> MaterialTheme.colorScheme.secondary
                                    ConnectionState.ERROR -> MaterialTheme.colorScheme.error
                                    ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }

                    if (connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.RECONNECTING) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.ERROR) {
                            Button(
                                onClick = { listenTogetherManager.connect() },
                                modifier = Modifier.weight(1f),
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                    ),
                            ) {
                                Text(stringResource(R.string.connect), fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Button(
                                onClick = { listenTogetherManager.disconnect() },
                                modifier = Modifier.weight(1f),
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                    ),
                            ) {
                                Text(stringResource(R.string.disconnect), fontWeight = FontWeight.SemiBold)
                            }
                            FilledTonalButton(
                                onClick = { listenTogetherManager.forceReconnect() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Reconnect", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        if (connectionState == ConnectionState.CONNECTED && !isInRoom) {
            item {
                Text(
                    text = stringResource(R.string.listen_together_background_disconnect_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        if (isInRoom) {
            // Room status card
            roomState?.let { room ->
                item {
                    Surface(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.room_code),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = room.roomCode,
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 6.sp,
                                )
                            }
                            if (isHost) {
                                Spacer(modifier = Modifier.height(12.dp))
                                val inviteLink =
                                    remember(room.roomCode) {
                                        "https://metrolist.meowery.eu/listen?code=${room.roomCode}"
                                    }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            val clipboard =
                                                context.getSystemService(
                                                    Context.CLIPBOARD_SERVICE,
                                                ) as android.content.ClipboardManager
                                            val clip = android.content.ClipData.newPlainText("Listen Together Link", inviteLink)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                                        },
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.link),
                                            contentDescription = stringResource(R.string.copy_link),
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.copy_link))
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    FilledTonalButton(
                                        onClick = {
                                            val clipboard =
                                                context.getSystemService(
                                                    Context.CLIPBOARD_SERVICE,
                                                ) as android.content.ClipboardManager
                                            val clip = android.content.ClipData.newPlainText("Room Code", room.roomCode)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                                        },
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.content_copy),
                                            contentDescription = stringResource(R.string.copy_code),
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.copy_code))
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }

                // Connected users - horizontal layout
                val connectedUsers = room.users.filter { it.isConnected }

                item {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.connected_users, connectedUsers.size),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )

                        // Horizontal scrollable row for users
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            connectedUsers.forEach { user ->
                                // User avatar card
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier =
                                        Modifier
                                            .width(72.dp)
                                            .clickable(
                                                enabled = isHost && user.userId != userId,
                                                onClick = {
                                                    selectedUserForMenu = user.userId
                                                    selectedUsername = user.username
                                                },
                                            ),
                                ) {
                                    // Circular avatar
                                    Box(
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Surface(
                                            modifier = Modifier.size(52.dp),
                                            shape = RoundedCornerShape(50),
                                            color =
                                                if (user.isHost) {
                                                    MaterialTheme.colorScheme.primary
                                                } else if (user.userId == userId) {
                                                    MaterialTheme.colorScheme.secondary
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                },
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.fillMaxSize(),
                                            ) {
                                                Text(
                                                    text = user.username.take(1).uppercase(),
                                                    style = MaterialTheme.typography.titleLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    color =
                                                        if (user.isHost) {
                                                            MaterialTheme.colorScheme.onPrimary
                                                        } else if (user.userId == userId) {
                                                            MaterialTheme.colorScheme.onSecondary
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                        },
                                                )
                                            }
                                        }

                                        // Host/You badge
                                        if (user.isHost || user.userId == userId) {
                                            Surface(
                                                modifier =
                                                    Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .offset(x = 4.dp, y = 4.dp)
                                                        .size(18.dp),
                                                shape = RoundedCornerShape(50),
                                                color = if (user.isHost) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                            ) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier.fillMaxSize(),
                                                ) {
                                                    Icon(
                                                        painter =
                                                            painterResource(
                                                                if (user.isHost) R.drawable.crown else R.drawable.person,
                                                            ),
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier.size(12.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    // Username
                                    Text(
                                        text = user.username,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (user.userId == userId) FontWeight.Bold else FontWeight.Medium,
                                        color =
                                            if (user.isHost) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                    )

                                    // Role label
                                    if (user.isHost) {
                                        Text(
                                            text = stringResource(R.string.host_label),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                        )
                                    } else if (user.userId == userId) {
                                        Text(
                                            text = stringResource(R.string.you_label),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Pending join requests (host only)
                if (isHost && pendingJoinRequests.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.pending_requests),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    items(pendingJoinRequests) { request ->
                        Surface(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.padding(12.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Surface(
                                        modifier = Modifier.size(36.dp),
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.secondary,
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.fillMaxSize(),
                                        ) {
                                            Text(
                                                text = request.username.take(1).uppercase(),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSecondary,
                                            )
                                        }
                                    }
                                    Text(
                                        text = request.username,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = { listenTogetherManager.approveJoin(request.userId) },
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.check),
                                            contentDescription = stringResource(R.string.approve),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = { listenTogetherManager.rejectJoin(request.userId, "Rejected by host") },
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.close),
                                            contentDescription = stringResource(R.string.reject),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Pending suggestions (host only)
                if (isHost && pendingSuggestions.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.pending_suggestions),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    items(pendingSuggestions) { suggestion ->
                        Surface(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.padding(12.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.queue_music),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = suggestion.trackInfo.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = suggestion.fromUsername,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = { listenTogetherManager.approveSuggestion(suggestion.suggestionId) },
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.check),
                                            contentDescription = stringResource(R.string.approve),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = { listenTogetherManager.rejectSuggestion(suggestion.suggestionId, "Rejected by host") },
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.close),
                                            contentDescription = stringResource(R.string.reject),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Leave room button
                item {
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(R.string.cancel),
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Button(
                            onClick = {
                                listenTogetherManager.leaveRoom()
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                ),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.logout),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.leave_room), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        } else {
            // Join/Create room section
            item {
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.listen_together_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )

                        OutlinedTextField(
                            value = usernameInput,
                            onValueChange = { usernameInput = it },
                            label = { Text(stringResource(R.string.username)) },
                            placeholder = { Text(stringResource(R.string.enter_username)) },
                            leadingIcon = {
                                Icon(
                                    painterResource(R.drawable.person),
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingIcon = {
                                if (usernameInput.isNotBlank()) {
                                    IconButton(onClick = { usernameInput = "" }) {
                                        Icon(painterResource(R.drawable.close), null)
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        HorizontalDivider()

                        Text(
                            text = stringResource(R.string.join_existing_room),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        OutlinedTextField(
                            value = roomCodeInput,
                            onValueChange = { roomCodeInput = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8) },
                            label = { Text(stringResource(R.string.room_code)) },
                            placeholder = { Text("ABCD1234") },
                            supportingText = {
                                Text(
                                    text = "${roomCodeInput.length}/8",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    painterResource(R.drawable.token),
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Status messages
                        if (isJoiningRoom) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = waitingForApprovalText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }

                        joinErrorMessage?.let { msg ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(12.dp),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.error),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Action buttons
            item {
                Spacer(modifier = Modifier.height(20.dp))
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Create Room button (left side)
                        Button(
                            onClick = {
                                val username = usernameInput.takeIf { it.isNotBlank() } ?: savedUsername
                                val finalUsername = username.trim()
                                if (finalUsername.isNotBlank()) {
                                    savedUsername = finalUsername
                                    Toast.makeText(context, R.string.creating_room, Toast.LENGTH_SHORT).show()
                                    isCreatingRoom = true
                                    isJoiningRoom = false
                                    joinErrorMessage = null
                                    listenTogetherManager.connect()
                                    listenTogetherManager.createRoom(finalUsername)
                                } else {
                                    Toast.makeText(context, R.string.error_username_empty, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = (usernameInput.trim().isNotBlank() || savedUsername.isNotBlank()),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.add),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.create_room), fontWeight = FontWeight.SemiBold)
                        }

                        // Join Room button (right side - only visible when room code is complete)
                        if (roomCodeInput.length == 8) {
                            Button(
                                onClick = {
                                    val username = usernameInput.takeIf { it.isNotBlank() } ?: savedUsername
                                    val finalUsername = username.trim()
                                    if (finalUsername.isNotBlank()) {
                                        savedUsername = finalUsername
                                        Toast
                                            .makeText(
                                                context,
                                                String.format(joiningRoomTemplate, roomCodeInput),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        isJoiningRoom = true
                                        isCreatingRoom = false
                                        joinErrorMessage = null
                                        listenTogetherManager.connect()
                                        listenTogetherManager.joinRoom(roomCodeInput, finalUsername)
                                    } else {
                                        Toast.makeText(context, R.string.error_username_empty, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = (usernameInput.trim().isNotBlank() || savedUsername.isNotBlank()),
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary,
                                    ),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.login),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.join_room), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.cancel),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
