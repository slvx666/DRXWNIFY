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
    val downloadUtil = com.metrolist.music.LocalDownloadUtil.current
    val scope = rememberCoroutineScope()
    val mediaId = mediaMetadata.id

    var loading by remember { mutableStateOf(true) }
    var candidates by remember { mutableStateOf<List<AudioFallbackEngine.VersionCandidate>>(emptyList()) }
    var currentKey by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<VersionTarget?>(null) }
    var pinnedButNotPlaying by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(mediaId) {
        withContext(Dispatchers.IO) {
            val t = versionTarget(mediaMetadata, database)
            target = t
            val lookupId = t.catalogId?.let { FallbackIds.of(it) } ?: mediaId
            candidates = AudioFallbackEngine.versionCandidates(lookupId, database.getSongByIdBlocking(lookupId))
                .ifEmpty { AudioFallbackEngine.versionCandidates(mediaId, database.getSongByIdBlocking(mediaId)) }
                // A plain YouTube track has no catalog entry to pin another source to.
                .filter { t.catalogId != null || FallbackIds.isFallbackId(mediaId) || it.match.provider == AudioProviderId.YOUTUBE }
            // What really plays: a pinned version whose source didn't answer (VK down, token gone)
            // is not "the current one" — the player fell back to another source.
            val serving = com.metrolist.music.resolver.NowServing.of(mediaId)
            val pinned = t.currentKey
            currentKey = pinned?.takeIf { serving == null || it.startsWith(serving.name + ":") }
            pinnedButNotPlaying = if (pinned != null && serving != null && !pinned.startsWith(serving.name + ":")) {
                providerLabel(AudioProviderId.valueOf(pinned.substringBefore(':'))) to providerLabel(serving)
            } else {
                null
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
            pinnedButNotPlaying?.let { (pinned, playing) ->
                Text(
                    text = stringResource(R.string.track_version_pinned_failed, pinned, playing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
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
                        // Shown at once; the bitrate follows when it has been read (spinner till then).
                        val measured by androidx.compose.runtime.produceState<Int?>(candidate.bitrateKbps, key) {
                            if (value == null) {
                                val lookupId = target?.catalogId?.let { FallbackIds.of(it) } ?: mediaId
                                // Off the main thread: Room refuses it there (this crashed the app).
                                value = withContext(Dispatchers.IO) {
                                    AudioFallbackEngine.measureVersion(lookupId, database.getSongByIdBlocking(lookupId), candidate.match)
                                } ?: -1
                            }
                        }
                        VersionRow(
                            candidate = candidate.copy(bitrateKbps = measured),
                            isCurrent = key == currentKey,
                            onClick = {
                                onDismiss()
                                // Picking the current one again still applies it: it retries a source
                                // that failed before (its failure is forgotten) and restarts the track.
                                val t = target ?: return@VersionRow
                                scope.launch {
                                    applyVersion(database, playerConnection, downloadUtil, mediaMetadata, t, candidate.match)
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
        dismissButton = {
            // Back to what the app picks by itself: always possible after a hand-picked version.
            if (target?.manual == true) {
                TextButton(onClick = {
                    onDismiss()
                    val t = target ?: return@TextButton
                    scope.launch {
                        applyVersion(database, playerConnection, downloadUtil, mediaMetadata, t, null)
                        Toast.makeText(context, R.string.track_version_changed, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.track_version_auto)) }
            }
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
    if (kbps == null) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        return
    }
    val known = kbps.takeIf { it > 0 }
    val color = known?.let(::bitrateColor) ?: MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = if (known != null) "$known kbps" else "? kbps",
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
    AudioProviderId.LOSSLESS -> "Lossless"
}

/**
 * What a version change is about: the catalog track behind the item (if any), whether a version was
 * picked by hand, and which recording plays now.
 */
private data class VersionTarget(
    val catalogId: String?,
    val manual: Boolean,
    val currentKey: String?,
)

private suspend fun versionTarget(metadata: MediaMetadata, database: com.metrolist.music.db.MusicDatabase): VersionTarget {
    val id = metadata.id
    val catalogId = when {
        id.startsWith(com.metrolist.music.utils.SPOTIFY_ID_PREFIX) -> id.removePrefix(com.metrolist.music.utils.SPOTIFY_ID_PREFIX)
        com.metrolist.music.resolver.SourceSearch.isSourceTrack(id) -> null
        FallbackIds.isFallbackId(id) -> FallbackIds.catalogIdOf(id)
        else -> database.getSpotifyMatchByYouTubeId(id)?.spotifyId
    }
    if (catalogId == null) {
        val choice = if (FallbackIds.isFallbackId(id)) AudioFallbackEngine.currentChoice(id, database.getSongByIdBlocking(id)) else null
        return VersionTarget(
            catalogId = null,
            manual = choice != null && choice.confidence >= com.metrolist.music.resolver.ParallelAudioResolver.MANUAL_CONFIDENCE,
            currentKey = choice?.let { "${it.provider}:${it.trackId}" } ?: "${AudioProviderId.YOUTUBE}:$id",
        )
    }
    // The engine describes the track by its source id: make sure it knows the catalog track.
    val fallbackId = FallbackIds.of(catalogId)
    if (SpotifyMetadataRegistry.get(fallbackId) == null) {
        (SpotifyMetadataRegistry.get(id) ?: com.metrolist.music.catalog.Catalog.getTrack(catalogId).getOrNull())
            ?.let { SpotifyMetadataRegistry.register(fallbackId, it) }
    }
    val pinned = runCatching { database.getAudioFallbackMatches(catalogId) }.getOrNull().orEmpty()
        .firstOrNull { it.selected && it.confidence >= com.metrolist.music.resolver.ParallelAudioResolver.MANUAL_CONFIDENCE }
    val youtubeMatch = database.getSpotifyMatch(catalogId)
    return VersionTarget(
        catalogId = catalogId,
        manual = pinned != null || youtubeMatch?.isManualOverride == true,
        currentKey = when {
            pinned != null -> "${pinned.provider}:${pinned.providerTrackId}"
            youtubeMatch != null -> "${AudioProviderId.YOUTUBE}:${youtubeMatch.youtubeId}"
            else -> AudioFallbackEngine.currentChoice(fallbackId, database.getSongByIdBlocking(fallbackId))
                ?.let { "${it.provider}:${it.trackId}" }
        },
    )
}

/**
 * Makes [match] the recording of the track from now on — or, with null, goes back to what the app
 * picks by itself — and swaps it in wherever the track sits in the queue:
 *  - a catalog track: a YouTube upload becomes its YouTube match (and no other source is pinned
 *    over it); any other source is pinned to it and its YouTube match forgotten, so playback and
 *    downloads both use that source;
 *  - a track found in a source itself: the choice is pinned for it.
 */
private suspend fun applyVersion(
    database: com.metrolist.music.db.MusicDatabase,
    playerConnection: com.metrolist.music.playback.PlayerConnection,
    downloadUtil: com.metrolist.music.playback.DownloadUtil,
    mediaMetadata: MediaMetadata,
    target: VersionTarget,
    match: com.metrolist.music.resolver.ProviderMatch?,
) {
    val mediaId = mediaMetadata.id
    val catalogId = target.catalogId
    if (catalogId == null) {
        if (FallbackIds.isFallbackId(mediaId)) playerConnection.service.setAudioSource(mediaId, match)
        else if (match != null && match.trackId != mediaId) replaceInQueue(playerConnection, mediaId, mediaMetadata.copy(id = match.trackId))
        return
    }
    val fallbackId = FallbackIds.of(catalogId)
    val newId: String = withContext(Dispatchers.IO) {
        val mapper = SpotifyYouTubeMapper(database)
        val oldYouTube = database.getSpotifyMatch(catalogId)?.youtubeId
        when {
            match == null -> {
                // Automatic again: no pinned source, no hand-picked YouTube upload.
                AudioFallbackEngine.setManualChoice(fallbackId, database.getSongByIdBlocking(fallbackId), null)
                oldYouTube?.let { SpotifyYouTubeMapper.forgetYouTubeVideo(database, it) }
                val track = SpotifyMetadataRegistry.get(fallbackId)
                track?.let { mapper.mapToYouTube(it)?.id } ?: fallbackId
            }
            match.provider == AudioProviderId.YOUTUBE -> {
                AudioFallbackEngine.setManualChoice(fallbackId, database.getSongByIdBlocking(fallbackId), null)
                mapper.overrideMatch(catalogId, match.trackId, match.title, match.artist)
                SpotifyMetadataRegistry.get(fallbackId)?.let { SpotifyMetadataRegistry.register(match.trackId, it) }
                match.trackId
            }
            else -> {
                AudioFallbackEngine.setManualChoice(fallbackId, database.getSongByIdBlocking(fallbackId), match)
                oldYouTube?.let { SpotifyYouTubeMapper.forgetYouTubeVideo(database, it) }
                fallbackId
            }
        }
    }
    // A downloaded track kept playing its old file (VK 63 kbps after Bandcamp 128 was picked):
    // the download follows the version too.
    val downloaded = listOf(mediaId, fallbackId).distinct().firstOrNull { id ->
        downloadUtil.downloads.value[id]?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
    }
    if (newId == mediaId) {
        // Same item (a source id before and after): drop what was cached for it and restart it.
        playerConnection.service.setAudioSource(mediaId, match, dropDownloaded = downloaded == mediaId)
    } else {
        replaceInQueue(playerConnection, mediaId, mediaMetadata.copy(id = newId))
    }
    if (downloaded != null) downloadUtil.redownload(downloaded, newId, mediaMetadata.title)
}

private fun replaceInQueue(
    playerConnection: com.metrolist.music.playback.PlayerConnection,
    oldId: String,
    replacement: MediaMetadata,
) {
    val player = playerConnection.player
    val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == oldId } ?: return
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
