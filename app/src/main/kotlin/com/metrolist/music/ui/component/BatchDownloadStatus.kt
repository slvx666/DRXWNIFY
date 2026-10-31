/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.playback.SpotifyBatchDownload
import com.metrolist.music.utils.DownloadExportState
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.flow.collectLatest

/**
 * How many of [tracks] already have a file in the user's download folder. Recomputed whenever a new
 * track finishes exporting, so the screen keeps telling the truth after leaving and coming back.
 */
@Composable
fun rememberDownloadedCount(tracks: List<SpotifyTrack>): Int {
    val database = LocalDatabase.current
    val ids = remember(tracks) { tracks.map { it.id } }
    val count by produceState(initialValue = 0, ids) {
        DownloadExportState.exported.collectLatest {
            value = SpotifyBatchDownload.downloadedCount(database, tracks)
        }
    }
    return count
}

/** Ids of [tracks] whose file is in the download folder, kept up to date as exports finish. */
@Composable
fun rememberDownloadedIds(tracks: List<SpotifyTrack>): Set<String> {
    val database = LocalDatabase.current
    val ids = remember(tracks) { tracks.map { it.id } }
    val downloaded by produceState(initialValue = emptySet<String>(), ids) {
        DownloadExportState.exported.collectLatest {
            value = SpotifyBatchDownload.downloadedTrackIds(database, tracks)
        }
    }
    return downloaded
}

/**
 * Label for the "download everything" button: it says what is left to do rather than always
 * offering to download the whole list again.
 */
@Composable
fun batchDownloadLabel(downloaded: Int, total: Int): String = when {
    total <= 0 || downloaded <= 0 -> stringResource(R.string.spotify_download_all)
    downloaded >= total -> stringResource(R.string.download_status_all)
    else -> stringResource(R.string.download_the_rest, total - downloaded)
}

/**
 * The line(s) under the download button: what stage the batch is in, how much of the list is on the
 * device, and whether it is safe to leave the app.
 */
@Composable
fun BatchDownloadProgress(
    progress: SpotifyBatchDownload.Progress?,
    downloaded: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    if (progress == null && (total <= 0 || downloaded <= 0)) return
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(12.dp))
        if (progress == null) {
            Text(
                text = if (downloaded >= total) {
                    stringResource(R.string.download_status_all)
                } else {
                    stringResource(R.string.download_status_downloaded, downloaded, total)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        val stage = when (progress.phase) {
            SpotifyBatchDownload.Phase.SEARCHING ->
                stringResource(R.string.spotify_dl_searching, progress.searched, progress.total) + "   " +
                    stringResource(R.string.spotify_dl_in_folder, progress.current)
            SpotifyBatchDownload.Phase.DOWNLOADING ->
                stringResource(R.string.spotify_dl_downloading, progress.current, progress.total)
            SpotifyBatchDownload.Phase.FORMATTING ->
                stringResource(R.string.spotify_dl_formatting_count, progress.current, progress.total)
            SpotifyBatchDownload.Phase.DONE ->
                stringResource(R.string.spotify_dl_downloading, progress.current, progress.total)
        }
        val extra = buildList {
            if (progress.skipped > 0) add(stringResource(R.string.download_status_not_found, progress.skipped))
            if (progress.failed > 0) add(stringResource(R.string.download_status_failed, progress.failed))
        }.joinToString("  •  ")

        Text(
            text = if (extra.isEmpty()) stage else "$stage   $extra",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.download_keep_open_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the user is told about why a track didn't download — plain words, what to do about it. */
@Composable
fun downloadIssueText(reason: com.metrolist.music.playback.DownloadIssues.Reason): String = stringResource(
    when (reason) {
        com.metrolist.music.playback.DownloadIssues.Reason.NOT_FOUND -> R.string.download_issue_not_found
        com.metrolist.music.playback.DownloadIssues.Reason.SOURCES_DID_NOT_ANSWER -> R.string.download_issue_no_answer
        com.metrolist.music.playback.DownloadIssues.Reason.DOWNLOAD_FAILED -> R.string.download_issue_download_failed
        com.metrolist.music.playback.DownloadIssues.Reason.SAVE_FAILED -> R.string.download_issue_save_failed
        com.metrolist.music.playback.DownloadIssues.Reason.YOUTUBE_REFUSED -> R.string.download_issue_youtube_refused
        com.metrolist.music.playback.DownloadIssues.Reason.VK_PAUSED -> R.string.download_issue_vk_paused
    },
)

/**
 * "Why N didn't download" under a list's download status once a batch has finished: opens the
 * tracks that aren't in the folder with the reason next to each (YouTube refusing the VPN, VK's
 * pause, not found anywhere…), so a short count is not a mystery.
 */
@Composable
fun DownloadIssuesLink(tracks: List<SpotifyTrack>, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.metrolist.music.playback.DownloadIssues.ensureLoaded(context)
        }
    }
    val issues by com.metrolist.music.playback.DownloadIssues.issues.collectAsState()
    val downloadedIds = rememberDownloadedIds(tracks)
    val failed = remember(tracks, issues, downloadedIds) {
        tracks.filter { it.id !in downloadedIds && it.id in issues }.distinctBy { it.id }
    }
    if (failed.isEmpty()) return
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.material3.TextButton(onClick = { open = true }, modifier = modifier) {
        Text(stringResource(R.string.download_issues_why, failed.size))
    }
    if (!open) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.ok)) }
        },
        title = { Text(stringResource(R.string.download_issues_title) + " · " + failed.size) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                items(failed.size) { i ->
                    val track = failed[i]
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(track.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(track.artists.joinToString { it.name }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = issues[track.id]?.let { downloadIssueText(it) }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
    )
}
