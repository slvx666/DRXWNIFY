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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.SpotifyMetadataRegistry
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.music.resolver.AudioFallbackEngine
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.FallbackIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Change track version": the app looks for other recordings of the track itself — several YouTube
 * uploads and the best match of every other source — and lists them closest match first, then by
 * bitrate, each with its bitrate in a colour from light green (320 kbps and up) to dark red (0).
 */
@Composable
fun TrackVersionDialog(
    mediaMetadata: MediaMetadata,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val scope = rememberCoroutineScope()
    val mediaId = mediaMetadata.id

    var loading by remember { mutableStateOf(true) }
    var candidates by remember { mutableStateOf<List<AudioFallbackEngine.VersionCandidate>>(emptyList()) }
    var currentKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mediaId) {
        withContext(Dispatchers.IO) {
            val song = database.getSongByIdBlocking(mediaId)
            val isFallback = FallbackIds.isFallbackId(mediaId)
            val hasCatalog = isFallback || database.getSpotifyMatchByYouTubeId(mediaId) != null
            candidates = AudioFallbackEngine.versionCandidates(mediaId, song)
                // A plain YouTube track has no catalog entry to pin another source to.
                .filter { hasCatalog || it.match.provider == AudioProviderId.YOUTUBE }
            currentKey = if (isFallback) {
                AudioFallbackEngine.currentChoice(mediaId, song)?.let { "${it.provider}:${it.trackId}" }
            } else {
                "${AudioProviderId.YOUTUBE}:$mediaId"
            }
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.track_version)) },
        text = {
            Column {
            // What a change does, said once, right where it is made.
            Text(
                text = stringResource(R.string.track_version_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            when {
                loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(vertical = 16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.audio_source_searching))
                }

                candidates.isEmpty() -> Text(
                    text = stringResource(R.string.audio_source_none),
                    modifier = Modifier.padding(vertical = 16.dp),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(candidates, key = { "${it.match.provider}:${it.match.trackId}" }) { candidate ->
                        val key = "${candidate.match.provider}:${candidate.match.trackId}"
                        VersionRow(
                            candidate = candidate,
                            isCurrent = key == currentKey,
                            onClick = {
                                onDismiss()
                                if (key == currentKey) return@VersionRow
                                scope.launch {
                                    applyVersion(context, database, playerConnection, mediaMetadata, candidate)
                                    Toast.makeText(context, R.string.track_version_changed, Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }
            }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun VersionRow(
    candidate: AudioFallbackEngine.VersionCandidate,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val match = candidate.match
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${match.artist} – ${match.title}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val percent = (match.confidence.coerceAtMost(1.0) * 100).toInt()
            val info = stringResource(R.string.track_version_info, providerLabel(match.provider), percent) +
                if (isCurrent) " · " + stringResource(R.string.track_version_current) else ""
            Text(
                text = info,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        BitratePill(candidate.bitrateKbps)
    }
}

@Composable
private fun BitratePill(kbps: Int?) {
    val color = kbps?.let(::bitrateColor) ?: MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = if (kbps != null) "$kbps kbps" else "— kbps",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** 320 kbps and up: light green; down to 0: dark red, through yellow and orange. */
fun bitrateColor(kbps: Int): Color {
    val t = kbps.coerceIn(0, 320) / 320f
    return Color.hsv(hue = 100f * t, saturation = 0.85f - 0.3f * t, value = 0.45f + 0.47f * t)
}

private fun providerLabel(id: AudioProviderId): String = when (id) {
    AudioProviderId.YOUTUBE -> "YouTube"
    AudioProviderId.QOBUZ -> "Qobuz"
    AudioProviderId.VK -> "VK"
    AudioProviderId.SOUNDCLOUD -> "SoundCloud"
    AudioProviderId.BANDCAMP -> "Bandcamp"
    AudioProviderId.AUDIUS -> "Audius"
    AudioProviderId.SOULSEEK -> "Soulseek"
}

/**
 * Makes [candidate] the recording of the track from now on (and swaps it in if it is playing):
 *  - a track already served by a source: the choice is pinned for it;
 *  - a Spotify track on YouTube: another YouTube upload becomes its match, while any other source
 *    replaces YouTube for it altogether (pinned to the track, YouTube match forgotten).
 */
private suspend fun applyVersion(
    context: android.content.Context,
    database: com.metrolist.music.db.MusicDatabase,
    playerConnection: com.metrolist.music.playback.PlayerConnection,
    mediaMetadata: MediaMetadata,
    candidate: AudioFallbackEngine.VersionCandidate,
) {
    val mediaId = mediaMetadata.id
    val match = candidate.match
    if (FallbackIds.isFallbackId(mediaId)) {
        playerConnection.service.setAudioSource(mediaId, match)
        return
    }
    val replacement: MediaMetadata = withContext(Dispatchers.IO) {
        val spotifyId = database.getSpotifyMatchByYouTubeId(mediaId)?.spotifyId
        if (match.provider == AudioProviderId.YOUTUBE) {
            if (spotifyId != null) {
                SpotifyYouTubeMapper(database).overrideMatch(spotifyId, match.trackId, match.title, match.artist)
            }
            SpotifyMetadataRegistry.get(mediaId)?.let { SpotifyMetadataRegistry.register(match.trackId, it) }
            mediaMetadata.copy(id = match.trackId)
        } else {
            val song = database.getSongByIdBlocking(mediaId)
            AudioFallbackEngine.setManualChoice(mediaId, song, match)
            SpotifyYouTubeMapper.forgetYouTubeVideo(database, mediaId)
            val fallbackId = FallbackIds.of(spotifyId ?: return@withContext mediaMetadata)
            SpotifyMetadataRegistry.get(mediaId)?.let { SpotifyMetadataRegistry.register(fallbackId, it) }
            mediaMetadata.copy(id = fallbackId)
        }
    }
    if (replacement.id == mediaId) return
    val player = playerConnection.player
    val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId } ?: return
    val isCurrent = index == player.currentMediaItemIndex
    val position = player.currentPosition
    val wasPlaying = player.playWhenReady
    player.replaceMediaItem(index, replacement.toMediaItem())
    if (isCurrent) {
        player.seekTo(index, position)
        player.prepare()
        if (wasPlaying) player.play()
    }
}
