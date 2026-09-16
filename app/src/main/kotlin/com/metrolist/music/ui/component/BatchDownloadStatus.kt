/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
                stringResource(R.string.spotify_dl_searching, progress.current, progress.total)
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
