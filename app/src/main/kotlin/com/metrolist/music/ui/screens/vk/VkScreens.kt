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
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        title = match.title,
        subtitle = joinByBullet(
            match.artist.takeIf { it.isNotBlank() },
            match.durationMs?.takeIf { it > 0 }?.let { makeTimeString(it) },
        ),
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

        if (!com.metrolist.music.resolver.VkMusic.isReady) {
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

    fun downloadAll() {
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            // Pinned to VK first: the download reads the audio through the pinned source.
            SourceSearch.remember(tracks)
            tracks.forEach { match ->
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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        // Tap: full screen (zoomable). Long press: saved to the gallery.
                        val coverViewer = com.metrolist.music.ui.component.rememberCoverViewerState()
                        val coverName = listOf(playlist.artist, playlist.title).filter { it.isNotBlank() }.joinToString(" - ")
                        coverViewer.Content()
                        AsyncImage(
                            model = playlist.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .combinedClickable(
                                    onClick = { coverViewer.open(playlist.coverUrl, coverName) },
                                    onLongClick = { coverViewer.save(playlist.coverUrl, coverName) },
                                ),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = playlist.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = vkPlaylistSubtitle(playlist),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        // Three actions, as on any album page: download (left), listen (centre),
                        // save to "Library → VK" (right).
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.FilledTonalIconButton(
                                onClick = ::downloadAll,
                                enabled = tracks.isNotEmpty(),
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(painterResource(R.drawable.download), stringResource(R.string.action_download), Modifier.size(22.dp))
                            }
                            androidx.compose.material3.FilledIconButton(
                                onClick = { play(tracks, 0) },
                                enabled = tracks.isNotEmpty(),
                                modifier = Modifier.size(64.dp),
                            ) {
                                Icon(painterResource(R.drawable.play), stringResource(R.string.play), Modifier.size(32.dp))
                            }
                            androidx.compose.material3.FilledTonalIconButton(
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
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    painterResource(if (isSaved) R.drawable.favorite else R.drawable.favorite_border),
                                    contentDescription = stringResource(R.string.vk_save),
                                    tint = if (isSaved) MaterialTheme.colorScheme.error else androidx.compose.material3.LocalContentColor.current,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
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
                    onClick = { play(tracks, index) },
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
        }
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
