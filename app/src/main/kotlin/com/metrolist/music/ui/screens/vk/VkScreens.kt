/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.vk

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.metrolist.music.ui.component.coverSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.resolver.ProviderMatch
import com.metrolist.music.resolver.SourceSearch
import com.metrolist.music.resolver.providers.VkPlaylist
import com.metrolist.music.ui.component.EmptyPlaceholder
import com.metrolist.music.ui.component.ItemThumbnail
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.shimmer.ListItemPlaceHolder
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.ui.menu.SourceTrackMenu
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.viewmodels.VkLibraryTab
import com.metrolist.music.viewmodels.VkLibraryViewModel
import com.metrolist.music.viewmodels.VkPlaylistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URLEncoder

/** Route of a VK album/playlist page; the playlist itself is looked up by [VkPlaylist.key]. */
fun vkPlaylistRoute(playlist: VkPlaylist): String =
    "vk_playlist/" + URLEncoder.encode(playlist.key, "UTF-8")

/** "Album · Artist · 2021" / "Playlist · 34 tracks" under a VK playlist's title. */
@Composable
fun vkPlaylistSubtitle(playlist: VkPlaylist): String = joinByBullet(
    stringResource(if (playlist.isAlbum) R.string.lib_type_album else R.string.lib_type_playlist),
    playlist.artist.takeIf { it.isNotBlank() },
    playlist.year?.toString(),
    playlist.count.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.n_song, it, it) },
)

/** One VK playlist as a list row (library and search results). */
@Composable
fun VkPlaylistRow(playlist: VkPlaylist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        title = playlist.title,
        subtitle = vkPlaylistSubtitle(playlist),
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = playlist.coverUrl,
                isActive = false,
                isPlaying = false,
                shape = RoundedCornerShape(ThumbnailCornerRadius),
                modifier = Modifier.size(ListThumbnailSize),
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

/** One VK track row; the whole list it belongs to becomes the queue when it is tapped. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VkTrackRow(
    match: ProviderMatch,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    downloadState: Int? = null,
    /** Non-null while tracks are being picked: whether this one is ticked. */
    selected: Boolean? = null,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        title = match.title,
        subtitle = joinByBullet(
            match.artist.takeIf { it.isNotBlank() },
            match.durationMs?.takeIf { it > 0 }?.let { makeTimeString(it) },
        ),
        badges = {
            com.metrolist.music.playback.LikedBadge(
                androidx.compose.runtime.remember(match) { com.metrolist.music.resolver.SourceSearch.mediaIdOf(match) },
            )
            com.metrolist.music.ui.component.Icon.Download(downloadState)
        },
        trailingContent = {
            com.metrolist.music.ui.component.TrackSelectionCheck(selected != null, selected == true, onClick)
        },
        isActive = isActive,
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = match.thumbnailUrl,
                isActive = isActive,
                isPlaying = isPlaying,
                shape = RoundedCornerShape(ThumbnailCornerRadius),
                modifier = Modifier.size(ListThumbnailSize),
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

/**
 * The account's own VK music, in the library under the "VK" chip: its tracks, the albums added in
 * VK, and its playlists — switched by the chips at the top, which also lead back out.
 */
@Composable
fun VkLibraryScreen(
    navController: NavController,
    onClose: () -> Unit,
    viewModel: VkLibraryViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val tab by viewModel.tab.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()

    val shownPlaylists = when (tab) {
        VkLibraryTab.ALBUMS -> playlists.filter { it.isAlbum }
        VkLibraryTab.PLAYLISTS -> playlists.filterNot { it.isAlbum }
        VkLibraryTab.TRACKS -> emptyList()
    }

    LazyColumn(
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "filter") {
            com.metrolist.music.ui.screens.library.VkLibraryChips(
                tab = tab,
                onTab = viewModel::selectTab,
                onClose = onClose,
            )
        }

        // The shared test account plays VK, but its library is not the user's to see.
        if (!com.metrolist.music.resolver.VkMusic.isReady || !com.metrolist.music.resolver.VkMusic.ownAccount) {
            item(key = "signin") {
                EmptyPlaceholder(
                    icon = R.drawable.vk_music,
                    text = stringResource(R.string.vk_library_needs_login),
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
            return@LazyColumn
        }

        if (tab == VkLibraryTab.TRACKS) {
            itemsIndexed(tracks, key = { index, match -> "${match.trackId}_$index" }) { index, match ->
                val mediaId = remember(match) { SourceSearch.mediaIdOf(match) }
                VkTrackRow(
                    match = match,
                    isActive = mediaMetadata?.id == mediaId,
                    isPlaying = isPlaying,
                    onClick = {
                        playerConnection.playQueue(
                            ListQueue(
                                title = "VK",
                                items = tracks.map { SourceSearch.mediaItemOf(it) },
                                startIndex = index,
                            ),
                        )
                    },
                    onLongClick = {
                        menuState.show {
                            SourceTrackMenu(match = match, onDismiss = menuState::dismiss, navController = navController)
                        }
                    },
                    modifier = Modifier.animateItem(),
                )
            }
        } else {
            items(shownPlaylists, key = { it.key }) { playlist ->
                VkPlaylistRow(
                    playlist = playlist,
                    onClick = { navController.navigate(vkPlaylistRoute(playlist)) },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        val nothing = if (tab == VkLibraryTab.TRACKS) tracks.isEmpty() else shownPlaylists.isEmpty()
        when {
            isLoading -> item(key = "loading") {
                ShimmerHost { repeat(6) { ListItemPlaceHolder() } }
            }
            failed -> item(key = "failed") {
                RetryPlaceholder(onRetry = viewModel::refresh)
            }
            nothing -> item(key = "empty") {
                EmptyPlaceholder(
                    icon = R.drawable.queue_music,
                    text = stringResource(R.string.vk_library_empty),
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
    }
}

/** A VK album or playlist: header, play/shuffle/download-all, and its tracks. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VkPlaylistScreen(
    navController: NavController,
    viewModel: VkPlaylistViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val database = LocalDatabase.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlist = viewModel.playlist
    val tracks by viewModel.tracks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val isSaved by viewModel.isSaved.collectAsState()
    val canSave by viewModel.canSave.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val downloadUtil = LocalDownloadUtil.current
    val downloads by downloadUtil.visibleDownloads.collectAsState()
    val exported by com.metrolist.music.utils.DownloadExportState.exported.collectAsState()

    fun play(list: List<ProviderMatch>, index: Int, shuffle: Boolean = false) {
        if (list.isEmpty()) return
        val ordered = if (shuffle) list.shuffled() else list
        playerConnection.playQueue(
            ListQueue(
                title = playlist?.title,
                items = ordered.map { SourceSearch.mediaItemOf(it) },
                startIndex = if (shuffle) 0 else index,
            ),
        )
    }

    val selection = com.metrolist.music.ui.component.rememberTrackSelection()

    fun downloadTracks(tracks: List<ProviderMatch>) {
        if (tracks.isEmpty()) return
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            // Pinned to VK first: the download reads the audio through the pinned source.
            SourceSearch.remember(tracks)
            // Only what isn't on the phone yet: re-adding a finished download fetches it again.
            val done = com.metrolist.music.utils.DownloadExportState.exported.value
            tracks.filterNot { SourceSearch.mediaIdOf(it) in done }.forEach { match ->
                val mediaId = SourceSearch.mediaIdOf(match)
                database.transaction { upsertMetadata(SourceSearch.metadataOf(match)) }
                val request = DownloadRequest.Builder(mediaId, mediaId.toUri())
                    .setCustomCacheKey(mediaId)
                    .setData(match.title.toByteArray())
                    .build()
                DownloadService.sendAddDownload(appContext, ExoDownloadService::class.java, request, false)
            }
        }
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.vk_download_started, tracks.size),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    fun downloadAll() = downloadTracks(tracks)

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = {
                Text(
                    text = playlist?.title.orEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
            actions = {
                com.metrolist.music.ui.component.TrackSelectionActions(selection, tracks.map { it.trackId })
            },
        )

        LazyColumn(
            contentPadding = LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues(),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (playlist != null) {
                item(key = "header") {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        // The same page as a Spotify / Yandex album: one album design everywhere.
                        // Tap: full screen (zoomable). Long press: saved to the gallery.
                        val coverViewer = com.metrolist.music.ui.component.rememberCoverViewerState()
                        val coverName = listOf(playlist.artist, playlist.title).filter { it.isNotBlank() }.joinToString(" - ")
                        coverViewer.Content()
                        AsyncImage(
                            model = playlist.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(240.dp)
                                .clip(RoundedCornerShape(ThumbnailCornerRadius))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .coverSource(coverViewer)
                                .combinedClickable(
                                    onClick = { coverViewer.open(playlist.coverUrl, coverName) },
                                    onLongClick = { coverViewer.showActions(playlist.coverUrl, coverName) },
                                ),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = playlist.title,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (playlist.artist.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = playlist.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = listOfNotNull(
                                stringResource(if (playlist.isAlbum) R.string.release_type_album else R.string.release_type_playlist),
                                playlist.year?.toString(),
                                tracks.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.n_song, it, it) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        val downloadedIds = tracks.map { SourceSearch.mediaIdOf(it) }.filter { it in exported }
                        if (tracks.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Button(onClick = { play(tracks, 0) }) {
                                    Icon(painterResource(R.drawable.play), contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(R.string.play))
                                }
                                Spacer(Modifier.width(8.dp))
                                com.metrolist.music.ui.component.DownloadOrShare(
                                    downloaded = downloadedIds.size,
                                    total = tracks.size,
                                    downloadedIds = { downloadedIds },
                                    onDownloadRest = ::downloadAll,
                                ) {
                                    OutlinedButton(onClick = ::downloadAll) {
                                        Icon(painterResource(R.drawable.download), contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.size(8.dp))
                                        Text(stringResource(R.string.action_download))
                                    }
                                }
                                if (canSave || isSaved) {
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        // The user's own playlists are already theirs; only others' can be saved.
                                        enabled = canSave && !saving,
                                        onClick = {
                                            viewModel.toggleSaved { result ->
                                                val message = when {
                                                    result.isFailure -> R.string.vk_save_failed
                                                    result.getOrNull() == true -> R.string.vk_saved
                                                    else -> R.string.vk_unsaved
                                                }
                                                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                    ) {
                                        Icon(
                                            painterResource(if (isSaved) R.drawable.favorite else R.drawable.favorite_border),
                                            contentDescription = stringResource(R.string.vk_save),
                                            tint = if (isSaved) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                            com.metrolist.music.ui.component.BatchDownloadProgress(
                                progress = null,
                                downloaded = downloadedIds.size,
                                total = tracks.size,
                            )
                        }
                    }
                }
            }

            itemsIndexed(tracks, key = { index, match -> "${match.trackId}_$index" }) { index, match ->
                val mediaId = remember(match) { SourceSearch.mediaIdOf(match) }
                VkTrackRow(
                    match = match,
                    isActive = mediaMetadata?.id == mediaId,
                    isPlaying = isPlaying,
                    downloadState = downloads[mediaId]?.state,
                    selected = if (selection.active) selection.isSelected(match.trackId) else null,
                    onClick = { if (selection.active) selection.toggle(match.trackId) else play(tracks, index) },
                    onLongClick = {
                        menuState.show {
                            SourceTrackMenu(match = match, onDismiss = menuState::dismiss, navController = navController)
                        }
                    },
                    modifier = Modifier.animateItem(),
                )
            }

            when {
                isLoading -> item(key = "loading") {
                    ShimmerHost { repeat(6) { ListItemPlaceHolder() } }
                }
                failed -> item(key = "failed") {
                    RetryPlaceholder(onRetry = viewModel::load)
                }
                tracks.isEmpty() -> item(key = "empty") {
                    EmptyPlaceholder(
                        icon = R.drawable.music_note,
                        text = stringResource(R.string.vk_playlist_empty),
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
            item(key = "selection_space") { com.metrolist.music.ui.component.TrackSelectionSpacer(selection) }
        }
    }

    com.metrolist.music.ui.component.TrackSelectionBar(
        selection = selection,
        onDownload = { keys -> downloadTracks(tracks.filter { it.trackId in keys }); selection.stop() },
        onShare = { keys ->
            val picked = tracks.filter { it.trackId in keys }
            com.metrolist.music.ui.component.SelectedTracks.share(context, downloadUtil) { picked.map { SourceSearch.mediaIdOf(it) } }
        },
        onDelete = { keys ->
            val picked = tracks.filter { it.trackId in keys }
            com.metrolist.music.ui.component.SelectedTracks.delete(context, downloadUtil) { picked.map { SourceSearch.mediaIdOf(it) } }
        },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom).asPaddingValues()),
    )
    }
}

@Composable
private fun RetryPlaceholder(onRetry: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(32.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.vk_load_failed),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}
