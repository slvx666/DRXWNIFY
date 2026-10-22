/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.metrolist.music.genre.GenrePlaylists
import com.metrolist.music.playback.SpotifyBatchDownload
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.music.playback.queues.SpotifyPlaylistQueue
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.screens.friends.genreColor
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.launch

/**
 * Getting to know a genre (a tap on any genre tag): fifteen tracks that describe it best, one per
 * artist where possible — see [GenrePlaylists].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GenreScreen(navController: NavController, genre: String) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val playerConnection = LocalPlayerConnection.current
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val mapper = remember { SpotifyYouTubeMapper(database) }
    val progressBySource by SpotifyBatchDownload.progressBySource.collectAsState()

    var tracks by remember { mutableStateOf<List<SpotifyTrack>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(genre, attempt) {
        failed = false
        GenrePlaylists.tracks(genre, force = attempt > 0)
            .onSuccess { tracks = it; failed = it.isEmpty() }
            .onFailure { failed = true }
    }

    val list = tracks.orEmpty()
    val downloadedIds = com.metrolist.music.ui.component.rememberDownloadedIds(list)
    val sourceId = "genre_$genre"
    val progress = progressBySource[sourceId]
    val accent = genreColor(genre)

    fun play(from: List<SpotifyTrack>, index: Int) {
        val connection = playerConnection ?: return
        connection.playQueue(
            SpotifyPlaylistQueue(playlistId = sourceId, initialTracks = from, startIndex = index, mapper = mapper),
        )
    }

    fun downloadAll() {
        if (list.isEmpty()) return
        val appContext = context.applicationContext
        Toast.makeText(context, context.getString(R.string.spotify_download_started, list.size), Toast.LENGTH_SHORT).show()
        SpotifyBatchDownload.start(
            appContext = appContext,
            sourceId = sourceId,
            tracks = list,
            mapper = mapper,
            label = genre,
            downloads = downloadUtil.downloads,
            database = database,
            folderName = genre,
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
            title = { Text(genre, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = navController::navigateUp) { Icon(painterResource(R.drawable.arrow_back), null) } },
        )
        LazyColumn(
            contentPadding = PaddingValues(bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() + 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.12f))))
                        .padding(18.dp),
                ) {
                    Text(genre, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.genre_playlist_subtitle, GenrePlaylists.SIZE),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    )
                    if (list.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { play(list, 0) }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                                Icon(painterResource(R.drawable.play), null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.play))
                            }
                            FilledTonalIconButton(onClick = { play(list.shuffled(), 0) }) {
                                Icon(painterResource(R.drawable.shuffle), stringResource(R.string.shuffle))
                            }
                            if (progress != null) {
                                FilledTonalIconButton(onClick = { SpotifyBatchDownload.cancel(sourceId) }) {
                                    Icon(painterResource(R.drawable.close), stringResource(R.string.cancel))
                                }
                            } else {
                                FilledTonalIconButton(onClick = { downloadAll() }, enabled = downloadedIds.size < list.size) {
                                    Icon(painterResource(R.drawable.download), stringResource(R.string.action_download))
                                }
                            }
                        }
                        com.metrolist.music.ui.component.BatchDownloadProgress(progress = progress, downloaded = downloadedIds.size, total = list.size)
                    }
                }
            }
            when {
                tracks == null && !failed -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                failed -> item(key = "failed") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.genre_playlist_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { attempt++ }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
            itemsIndexed(list, key = { _, t -> "g_${t.id}" }) { i, track ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { play(list, i) },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuState.show {
                                    com.metrolist.music.ui.menu.SpotifyTrackMenu(
                                        track = track,
                                        mapper = mapper,
                                        onDismiss = menuState::dismiss,
                                        navController = navController,
                                    )
                                }
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(26.dp),
                    )
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))) {
                        AsyncImage(SpotifyMapper.getTrackThumbnail(track), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                track.artists.joinToString { it.name }.ifBlank { null },
                                track.album?.releaseDate?.take(4),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (track.id in downloadedIds) {
                        Icon(
                            painterResource(R.drawable.offline),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp).size(18.dp),
                        )
                    }
                }
            }
            if (list.isNotEmpty()) {
                item(key = "source") {
                    Text(
                        stringResource(R.string.genre_playlist_source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
