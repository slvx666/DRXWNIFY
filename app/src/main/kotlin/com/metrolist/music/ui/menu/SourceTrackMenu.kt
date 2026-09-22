/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.widget.Toast
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.resolver.ProviderMatch
import com.metrolist.music.resolver.SourceSearch
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Menu for a track found by the experimental source search. Deliberately short: such a track has no
 * catalog entry, so liking it, opening its artist or album and starting a radio from it are not
 * possible — only the actions that need nothing but the audio itself are offered.
 */
@Composable
fun SourceTrackMenu(
    match: ProviderMatch,
    onDismiss: () -> Unit,
    navController: androidx.navigation.NavController? = null,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val coroutineScope = rememberCoroutineScope()

    val mediaId = SourceSearch.mediaIdOf(match)
    val downloadUtil = LocalDownloadUtil.current
    val downloads by downloadUtil.downloads.collectAsState()
    val downloadState = downloads[mediaId]?.state
    val isDownloaded = downloadState == Download.STATE_COMPLETED
    val isDownloading = downloadState == Download.STATE_DOWNLOADING || downloadState == Download.STATE_QUEUED

    ListItem(
        headlineContent = {
            Text(text = match.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                text = joinByBullet(
                    match.artist.takeIf { it.isNotBlank() },
                    match.durationMs?.takeIf { it > 0 }?.let { makeTimeString(it) },
                    match.provider.name.lowercase().replaceFirstChar { it.uppercase() },
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
                    model = match.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ThumbnailCornerRadius)),
                )
            }
        },
    )

    Text(
        text = stringResource(R.string.search_sources_limits),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )

    Spacer(modifier = Modifier.height(12.dp))

    Material3MenuGroup(
        items = listOfNotNull(
            // There is no artist page for a source track, so this searches the sources for the
            // performer instead — the closest thing that actually works.
            if (navController != null && match.artist.isNotBlank()) {
                Material3MenuItemData(
                    title = { Text(text = stringResource(R.string.search_this_artist)) },
                    description = { Text(text = match.artist) },
                    icon = {
                        Icon(painter = painterResource(R.drawable.artist), contentDescription = null)
                    },
                    onClick = {
                        onDismiss()
                        com.metrolist.music.viewmodels.SourceSearchViewModel.pendingArtist = match.artist
                        navController.navigate(
                            "search/" + java.net.URLEncoder.encode(match.artist, "UTF-8"),
                        )
                    },
                )
            } else {
                null
            },
            if (isDownloaded) {
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
                                mediaId,
                            )
                        }
                    },
                )
            } else {
                null
            },
            if (isDownloaded) {
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
                                mediaId,
                            )
                        }
                    },
                )
            } else {
                null
            },
            Material3MenuItemData(
                title = { Text(text = stringResource(R.string.play_next)) },
                description = { Text(text = stringResource(R.string.play_next_desc)) },
                icon = {
                    Icon(painter = painterResource(R.drawable.queue_play_next), contentDescription = null)
                },
                onClick = {
                    onDismiss()
                    playerConnection.playNext(SourceSearch.mediaItemOf(match))
                    Toast.makeText(context, context.getString(R.string.added_to_play_next), Toast.LENGTH_SHORT).show()
                    coroutineScope.launch { SourceSearch.remember(listOf(match)) }
                },
            ),
            Material3MenuItemData(
                title = { Text(text = stringResource(R.string.add_to_queue)) },
                description = { Text(text = stringResource(R.string.add_to_queue_desc)) },
                icon = {
                    Icon(painter = painterResource(R.drawable.queue_add), contentDescription = null)
                },
                onClick = {
                    onDismiss()
                    playerConnection.addToQueue(SourceSearch.mediaItemOf(match))
                    Toast.makeText(context, context.getString(R.string.added_to_queue), Toast.LENGTH_SHORT).show()
                    coroutineScope.launch { SourceSearch.remember(listOf(match)) }
                },
            ),
            Material3MenuItemData(
                title = {
                    Text(
                        text = stringResource(
                            when {
                                isDownloaded -> R.string.remove_download
                                isDownloading -> R.string.cancel
                                else -> R.string.action_download
                            },
                        ),
                    )
                },
                description = { Text(text = stringResource(R.string.track_download_desc)) },
                icon = {
                    Icon(
                        painter = painterResource(if (isDownloaded) R.drawable.offline else R.drawable.download),
                        contentDescription = null,
                    )
                },
                onClick = {
                    if (isDownloaded || isDownloading) {
                        DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, mediaId, false)
                        return@Material3MenuItemData
                    }
                    onDismiss()
                    Toast.makeText(context, context.getString(R.string.download_starting), Toast.LENGTH_SHORT).show()
                    val appContext = context.applicationContext
                    coroutineScope.launch(Dispatchers.IO) {
                        // The download reads the audio through the pinned source, so pin it first.
                        SourceSearch.remember(listOf(match))
                        val request = DownloadRequest.Builder(mediaId, mediaId.toUri())
                            .setCustomCacheKey(mediaId)
                            .setData(match.title.toByteArray())
                            .build()
                        DownloadService.sendAddDownload(appContext, ExoDownloadService::class.java, request, false)
                    }
                },
            ),
        ),
    )
}
