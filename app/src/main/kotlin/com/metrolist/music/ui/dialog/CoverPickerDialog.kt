/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.dialog

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.R
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.utils.DownloadExportState
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A cover for a track found in a source that has none (Soulseek files, many uploads): the catalog is
 * searched with what the file says about itself, and the results are offered with how closely each
 * one fits — file tags on Soulseek are messy, so the user picks. The chosen cover (and album) goes
 * into the track, and a downloaded file is written again with it embedded.
 */
@Composable
fun CoverPickerDialog(
    mediaId: String,
    title: String,
    artist: String,
    durationMs: Long?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(cleanQuery(artist, title)) }
    var loading by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<Pair<SpotifyTrack, Int>>>(emptyList()) }

    LaunchedEffect(query) {
        loading = true
        delay(400) // typing
        results = withContext(Dispatchers.IO) {
            runCatching {
                Catalog.ensureSearchable(com.metrolist.music.constants.MetadataSource.SPOTIFY)
                Catalog.search(query, listOf("track"), 15).getOrNull()?.tracks?.items.orEmpty()
            }.getOrDefault(emptyList())
                .filter { SpotifyMapper.getTrackThumbnail(it) != null }
                .map { track ->
                    val score = SpotifyMapper.matchScore(
                        track.name, track.artists.firstOrNull()?.name.orEmpty(), track.durationMs,
                        cleanTitle(title), artist, durationMs?.let { (it / 1000).toInt() },
                    )
                    track to (score * 100).toInt().coerceIn(0, 100)
                }
                .sortedByDescending { it.second }
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cover_picker_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.cover_picker_query)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                when {
                    loading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(24.dp), strokeWidth = 2.dp)
                    results.isEmpty() -> Text(stringResource(R.string.cover_picker_none), modifier = Modifier.padding(16.dp))
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                        items(results, key = { it.first.id }) { (track, percent) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        onDismiss()
                                        scope.launch {
                                            applyCover(database, downloadUtil, mediaId, track)
                                            Toast.makeText(context, R.string.cover_picker_done, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(6.dp),
                            ) {
                                AsyncImage(
                                    model = SpotifyMapper.getTrackThumbnail(track),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        listOfNotNull(track.artists.firstOrNull()?.name, track.album?.name).joinToString(" · "),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                val color = when {
                                    percent >= 80 -> Color(0xFF4CAF50)
                                    percent >= 50 -> Color(0xFFE0A030)
                                    else -> MaterialTheme.colorScheme.error
                                }
                                Text(
                                    "$percent%",
                                    color = color,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(color.copy(alpha = 0.16f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                                Spacer(Modifier.width(2.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** "03 - Artist - Title [320].mp3" → "Artist Title": what a catalog search understands. */
private fun cleanTitle(raw: String): String =
    raw.substringBeforeLast('.', raw)
        .replace('_', ' ')
        .replace(Regex("^\\s*\\d{1,3}\\s*[-.)]?\\s*"), "")
        .replace(Regex("[\\[(](?:\\d{2,4}\\s*kbps|320|flac|mp3|web|cd)[^\\])]*[\\])]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun cleanQuery(artist: String, title: String): String {
    val t = cleanTitle(title)
    // Soulseek "artist" is the folder path; the file name often carries the artist already.
    return if (artist.isNotBlank() && !t.contains(artist, ignoreCase = true) && artist.length < 40) "$artist $t" else t
}

private suspend fun applyCover(
    database: com.metrolist.music.db.MusicDatabase,
    downloadUtil: com.metrolist.music.playback.DownloadUtil,
    mediaId: String,
    track: SpotifyTrack,
) = withContext(Dispatchers.IO) {
    val song = database.getSongByIdBlocking(mediaId) ?: return@withContext
    val cover = SpotifyMapper.getTrackThumbnail(track) ?: return@withContext
    database.query {
        update(song.song.copy(thumbnailUrl = cover, albumName = track.album?.name ?: song.song.albumName))
    }
    // A downloaded file is written again, now with the cover in it.
    if (mediaId in DownloadExportState.exported.value) downloadUtil.downloadExporter.rewrite(mediaId)
}
