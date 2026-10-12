/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.friends

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.friends.FriendLikesSync
import com.metrolist.music.friends.FriendPlayback
import com.metrolist.music.friends.FriendsHub
import com.metrolist.music.playback.SpotifyBatchDownload
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.menu.PlayerMenu
import kotlinx.coroutines.launch

/**
 * A friend's liked tracks as a playlist: covers, play from any track, shuffle, download them all,
 * or keep them as one of the user's own playlists that follows the friend's likes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FriendLikesScreen(navController: NavController, pubkey: String) {
    LiveWhileShown()
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val playerConnection = LocalPlayerConnection.current
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val all by FriendsHub.data.collectAsState()
    val friends by FriendsHub.friends.collectAsState()
    val followed by FriendLikesSync.followed.collectAsState()
    val progressBySource by SpotifyBatchDownload.progressBySource.collectAsState()

    val d = all[pubkey]
    val name = displayName(pubkey, d?.name ?: friends.firstOrNull { it.pubkey == pubkey }?.name?.ifBlank { null })
    val likes = d?.likes
    val refs = likes?.tracks.orEmpty()
    val catalogTracks = remember(refs) { refs.mapNotNull(FriendPlayback::asCatalogTrack) }
    val downloadedIds = com.metrolist.music.ui.component.rememberDownloadedIds(catalogTracks)
    val downloadSourceId = "friend_likes_$pubkey"
    val progress = progressBySource[downloadSourceId]
    val isFollowed = pubkey in followed
    val playlistTitle = stringResource(R.string.friend_likes_playlist_name, name)

    fun play(list: List<FriendsHub.TrackRef>, index: Int) {
        val connection = playerConnection ?: return
        scope.launch { connection.playQueue(FriendPlayback.listQueue(list, index)) }
    }

    fun openMenu(track: FriendsHub.TrackRef) = scope.launch {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val (metadata, catalogTrack) = FriendPlayback.menuTarget(track) ?: return@launch
        menuState.show {
            PlayerMenu(
                mediaMetadata = metadata,
                navController = navController,
                isCurrentTrack = false,
                spotifyTrack = catalogTrack,
                onDismiss = menuState::dismiss,
            )
        }
    }

    fun downloadAll() {
        if (catalogTracks.isEmpty()) return
        val appContext = context.applicationContext
        Toast.makeText(context, context.getString(R.string.spotify_download_started, catalogTracks.size), Toast.LENGTH_SHORT).show()
        SpotifyBatchDownload.start(
            appContext = appContext,
            sourceId = downloadSourceId,
            tracks = catalogTracks,
            mapper = SpotifyYouTubeMapper(database),
            label = playlistTitle,
            downloads = downloadUtil.downloads,
            database = database,
            folderName = playlistTitle,
            onFinished = { result ->
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.spotify_dl_finished, result.current, result.skipped, result.failed),
                    Toast.LENGTH_LONG,
                ).show()
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.friend_likes_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = navController::navigateUp) { Icon(painterResource(R.drawable.arrow_back), null) } },
        )
        LazyColumn(
            contentPadding = PaddingValues(
                bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() + 16.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FriendAvatar(pubkey, name, d?.avatar, 56.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (likes != null) {
                                Text(
                                    if (likes.total > refs.size) {
                                        stringResource(R.string.friend_likes_shown, refs.size, likes.total)
                                    } else {
                                        androidx.compose.ui.res.pluralStringResource(R.plurals.n_song, refs.size, refs.size)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (refs.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { play(refs, 0) }) {
                                Icon(painterResource(R.drawable.play), null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.play))
                            }
                            FilledTonalIconButton(onClick = { play(refs.shuffled(), 0) }) {
                                Icon(painterResource(R.drawable.shuffle), stringResource(R.string.shuffle))
                            }
                            if (progress != null) {
                                FilledTonalIconButton(onClick = { SpotifyBatchDownload.cancel(downloadSourceId) }) {
                                    Icon(painterResource(R.drawable.close), stringResource(R.string.cancel))
                                }
                            } else {
                                FilledTonalIconButton(
                                    onClick = { downloadAll() },
                                    enabled = downloadedIds.size < catalogTracks.size,
                                ) {
                                    Icon(painterResource(R.drawable.download), stringResource(R.string.action_download))
                                }
                            }
                            // Kept as one of my playlists (follows the friend's likes): a toggle like the others.
                            if (isFollowed) {
                                FilledTonalIconButton(onClick = {
                                    FriendLikesSync.unfollow(pubkey)
                                    Toast.makeText(context, R.string.friend_likes_unfollowed, Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(painterResource(R.drawable.library_add_check), stringResource(R.string.friend_likes_following))
                                }
                            } else {
                                FilledTonalIconButton(onClick = {
                                    val current = likes ?: return@FilledTonalIconButton
                                    FriendLikesSync.follow(pubkey, playlistTitle, current)
                                    Toast.makeText(context, context.getString(R.string.friend_likes_followed, playlistTitle), Toast.LENGTH_LONG).show()
                                }) {
                                    Icon(painterResource(R.drawable.library_add), stringResource(R.string.friend_likes_follow))
                                }
                            }
                        }
                        com.metrolist.music.ui.component.BatchDownloadProgress(
                            progress = progress,
                            downloaded = downloadedIds.size,
                            total = catalogTracks.size,
                        )
                    }
                }
            }
            if (likes == null) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.friend_likes_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            itemsIndexed(refs, key = { i, t -> "l_${i}_${t.catalogId ?: t.title}" }) { i, track ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { play(refs, i) }, onLongClick = { openMenu(track) })
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))) {
                        AsyncImage(track.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                track.artist,
                                track.durationMs.takeIf { it > 0 }?.let { ms -> "%d:%02d".format(ms / 60_000, ms / 1000 % 60) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (track.catalogId != null && track.catalogId in downloadedIds) {
                        Icon(
                            painterResource(R.drawable.offline),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp).size(18.dp),
                        )
                    }
                }
            }
        }
    }
}
