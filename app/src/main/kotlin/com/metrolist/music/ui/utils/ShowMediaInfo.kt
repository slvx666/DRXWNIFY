/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.MediaInfo
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.ui.component.shimmer.TextPlaceholder

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.media3.exoplayer.offline.Download
import com.metrolist.music.LocalDownloadUtil
import kotlinx.coroutines.launch

@Composable
fun ShowMediaInfo(
    videoId: String,
    /** The track as the caller knows it: the sheet then works before the track was ever played. */
    fallbackMetadata: com.metrolist.music.models.MediaMetadata? = null,
) {
    if (videoId.isBlank()) return
    val database = LocalDatabase.current
    // A catalog track that hasn't played yet has no audio of its own: find the recording it will
    // play from (as playback would), so the sheet shows that recording's real quality.
    val playableId by androidx.compose.runtime.produceState<String?>(
        initialValue = videoId.takeUnless { needsResolving(it) },
        videoId,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { resolvePlayableId(videoId, database) }.getOrNull() ?: videoId
        }
    }
    val id = playableId
    if (id == null) {
        ShimmerHost {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(all = 16.dp),
            ) {
                TextPlaceholder()
            }
        }
        return
    }
    MediaInfoContent(id, fallbackMetadata)
}

private fun needsResolving(id: String): Boolean =
    id.startsWith(com.metrolist.music.utils.SPOTIFY_ID_PREFIX) ||
        (com.metrolist.music.resolver.FallbackIds.isFallbackId(id) &&
            !com.metrolist.music.resolver.SourceSearch.isSourceTrack(id))

/** The id whose audio [id] plays: a YouTube video id, or the source id when another source serves it. */
private suspend fun resolvePlayableId(id: String, database: com.metrolist.music.db.MusicDatabase): String {
    if (id.startsWith(com.metrolist.music.utils.SPOTIFY_ID_PREFIX)) {
        val spotifyId = id.removePrefix(com.metrolist.music.utils.SPOTIFY_ID_PREFIX)
        database.getSpotifyMatch(spotifyId)?.youtubeId?.let { return it }
        val track = com.metrolist.music.playback.SpotifyMetadataRegistry.get(id)
            ?: com.metrolist.music.catalog.Catalog.getTrack(spotifyId).getOrNull()
            ?: return id
        return com.metrolist.music.playback.SpotifyYouTubeMapper(database).mapToYouTube(track)?.id ?: id
    }
    if (com.metrolist.music.resolver.FallbackIds.isFallbackId(id)) {
        val choice = com.metrolist.music.resolver.AudioFallbackEngine.currentChoice(id, database.getSongByIdBlocking(id))
        if (choice?.provider == com.metrolist.music.resolver.AudioProviderId.YOUTUBE) return choice.trackId
    }
    return id
}

@Composable
private fun MediaInfoContent(
    videoId: String,
    fallbackMetadata: com.metrolist.music.models.MediaMetadata?,
) {

    val windowInsets = WindowInsets.systemBars

    var info by remember {
        mutableStateOf<MediaInfo?>(null)
    }

    val database = LocalDatabase.current
    var song by remember { mutableStateOf<Song?>(null) }

    var currentFormat by remember { mutableStateOf<FormatEntity?>(null) }

    val playerConnection = LocalPlayerConnection.current
    val context = LocalContext.current
    val downloadUtil = LocalDownloadUtil.current
    val download by downloadUtil.getDownload(videoId).collectAsState(initial = null)
    val coroutineScope = rememberCoroutineScope()

    // Views/likes exist only for a YouTube video. For a track from VK, SoundCloud, … the YouTube
    // lookup can never succeed, and waiting for it is what kept this sheet loading forever.
    var infoDone by remember { mutableStateOf(false) }
    val playerMetadata = playerConnection?.mediaMetadata?.collectAsState()?.value
        ?.takeIf { it.id == videoId }

    LaunchedEffect(Unit, videoId) {
        info = if (isYouTubeVideoId(videoId)) YouTube.getMediaInfo(videoId).getOrNull() else null
        infoDone = true
    }

    LaunchedEffect(Unit, videoId) {
        database.song(videoId).collect {
            song = it
        }
    }

    LaunchedEffect(Unit, videoId) {
        database.format(videoId).collect {
            currentFormat = it
        }
    }

    // Not played yet (no stored format): what the source says the stream will be.
    val previewFormat by androidx.compose.runtime.produceState<FormatEntity?>(null, videoId) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { previewFormatOf(videoId, database) }.getOrNull()
        }
    }

    // Which service the audio comes from (YouTube, VK, Qobuz…).
    val audioSource by androidx.compose.runtime.produceState<String?>(null, videoId) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.metrolist.music.resolver.AudioFallbackEngine
                .sourceOf(videoId, database.getSongByIdBlocking(videoId))
                ?.let { com.metrolist.music.ui.component.providerLabel(it) }
        }
    }

    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier
            .padding(
                windowInsets
                    .only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom)
                    .asPaddingValues()
            )
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (infoDone && (song != null || playerMetadata != null || fallbackMetadata != null)) {
            item(contentType = "MediaDetails") {
                Column {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    @Composable
                    fun copyItem(
                        label: String,
                        text: String?,
                        icon: Int,
                        color: androidx.compose.ui.graphics.Color? = null,
                    ): Material3SettingsItem? {
                        if (text.isNullOrBlank()) return null
                        return Material3SettingsItem(
                            title = { Text(label) },
                            description = {
                                if (color != null) {
                                    Text(text, color = color, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                } else {
                                    Text(text)
                                }
                            },
                            icon = painterResource(icon),
                            onClick = {
                                cm.setPrimaryClip(ClipData.newPlainText("text", text))
                                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                            },
                        )
                    }

                    // Quality first: what a listener opens this sheet for.
                    val isPreview = currentFormat == null && previewFormat != null
                    val format = currentFormat ?: previewFormat
                    val kbps = format?.bitrate?.takeIf { it > 0 }
                        ?.let { com.metrolist.music.ui.component.AudioQualityLevel.kbps(it) }
                    val codec = com.metrolist.music.ui.component.AudioQualityLevel.codecName(format?.mimeType, format?.codecs)
                    QualityHero(kbps = kbps, codec = codec, sampleRate = format?.sampleRate, source = audioSource, isPreview = isPreview)

                    val lowQualityNote = stringResource(R.string.low_quality)
                    val isLow = format != null &&
                        com.metrolist.music.ui.component.AudioQualityLevel.isLow(format.bitrate, format.mimeType)
                    val qualityItems = listOfNotNull(
                        copyItem(
                            stringResource(R.string.bitrate),
                            kbps?.let { k -> if (isLow) "$k kbps — $lowQualityNote" else "$k kbps" },
                            R.drawable.graphic_eq,
                            kbps?.let { com.metrolist.music.ui.dialog.bitrateColor(it) },
                        ),
                        copyItem(
                            stringResource(R.string.codecs),
                            listOfNotNull(codec, format?.codecs?.takeIf { it.isNotBlank() && !it.equals(codec, true) })
                                .joinToString(" · "),
                            R.drawable.info,
                        ),
                        copyItem(stringResource(R.string.sample_rate), format?.sampleRate?.let(::khz), R.drawable.gradient),
                        copyItem(stringResource(R.string.audio_source_label), audioSource, R.drawable.radio),
                        copyItem(stringResource(R.string.loudness), format?.loudnessDb?.let { "$it dB" }, R.drawable.contrast),
                        copyItem(
                            stringResource(R.string.file_size),
                            format?.contentLength?.let { Formatter.formatShortFileSize(context, it) },
                            R.drawable.content_copy,
                        ),
                        copyItem(stringResource(R.string.mime_type), format?.mimeType, R.drawable.key),
                    )
                    if (qualityItems.isNotEmpty()) {
                        Material3SettingsGroup(title = stringResource(R.string.audio_quality_title), items = qualityItems)
                        Spacer(Modifier.height(8.dp))
                    }

                    if (download?.state == Download.STATE_COMPLETED) {
                        Material3SettingsGroup(
                            title = stringResource(R.string.file_title),
                            items = listOf(
                                Material3SettingsItem(
                                    title = { Text(stringResource(R.string.open_file_location)) },
                                    description = { Text(stringResource(R.string.open_file_location_desc)) },
                                    icon = painterResource(R.drawable.folder),
                                    onClick = {
                                        val appContext = context.applicationContext
                                        coroutineScope.launch {
                                            com.metrolist.music.utils.openDownloadLocation(
                                                appContext,
                                                downloadUtil.downloadExporter,
                                                videoId,
                                            )
                                        }
                                    },
                                ),
                                Material3SettingsItem(
                                    title = { Text(stringResource(R.string.share_file)) },
                                    description = { Text(stringResource(R.string.share_file_desc)) },
                                    icon = painterResource(R.drawable.share),
                                    onClick = {
                                        val appContext = context.applicationContext
                                        coroutineScope.launch {
                                            com.metrolist.music.utils.shareDownloadedFile(
                                                appContext,
                                                downloadUtil.downloadExporter,
                                                videoId,
                                            )
                                        }
                                    },
                                ),
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    val aboutItems = listOfNotNull(
                        copyItem(
                            stringResource(R.string.song_title),
                            song?.title ?: playerMetadata?.title ?: fallbackMetadata?.title,
                            R.drawable.music_note,
                        ),
                        copyItem(
                            stringResource(R.string.song_artists),
                            song?.artists?.joinToString { it.name } ?: playerMetadata?.artists?.joinToString { it.name }
                                ?: fallbackMetadata?.artists?.joinToString { it.name },
                            R.drawable.person,
                        ),
                        copyItem(stringResource(R.string.views), info?.viewCount?.let(::numberFormatter), R.drawable.media3_icon_feed),
                        copyItem(stringResource(R.string.likes), info?.like?.let(::numberFormatter), R.drawable.media3_icon_thumb_up_unfilled),
                        copyItem(stringResource(R.string.media_id), videoId, R.drawable.media3_icon_bookmark_filled),
                        copyItem("Itag", format?.itag?.takeIf { it > 0 }?.toString(), R.drawable.key),
                    )
                    Material3SettingsGroup(title = stringResource(R.string.information), items = aboutItems)

                    info?.description?.takeIf { it.isNotBlank() }?.let { descriptionText ->
                        Spacer(Modifier.height(8.dp))
                        Material3SettingsGroup(
                            title = stringResource(R.string.description),
                            items = listOf(
                                Material3SettingsItem(
                                    title = { Text(stringResource(R.string.description)) },
                                    description = { Text(descriptionText) },
                                    onClick = {
                                        cm.setPrimaryClip(ClipData.newPlainText("text", descriptionText))
                                        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                                    },
                                ),
                            ),
                        )
                    }
                }
            }
        } else {
            item(contentType = "MediaInfoLoader") {
                ShimmerHost {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(all = 16.dp)
                    ) {
                        TextPlaceholder()
                    }
                }
            }
        }
    }
}
/** An 11-character YouTube video id, as opposed to a `mfb:` / source id of another provider. */
private fun isYouTubeVideoId(id: String): Boolean =
    id.length == 11 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

/** 44100 → "44.1 kHz", 48000 → "48 kHz". */
private fun khz(hz: Int): String {
    val value = hz / 1000.0
    return if (value % 1.0 == 0.0) "${value.toInt()} kHz" else "$value kHz"
}

/** The quality at a glance: the bitrate in its colour, then codec, sample rate and source. */
@Composable
private fun QualityHero(kbps: Int?, codec: String?, sampleRate: Int?, source: String?, isPreview: Boolean) {
    val color = kbps?.let { com.metrolist.music.ui.dialog.bitrateColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val details = listOfNotNull(codec, sampleRate?.let(::khz), source).joinToString(" · ")
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(
            text = buildAnnotatedStringOf(kbps, details, color),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (kbps == null) {
            Text(
                text = stringResource(R.string.quality_unknown_yet),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (isPreview) {
            Text(
                text = stringResource(R.string.quality_preview_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun buildAnnotatedStringOf(kbps: Int?, details: String, color: androidx.compose.ui.graphics.Color) =
    androidx.compose.ui.text.buildAnnotatedString {
        if (kbps != null) {
            pushStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = color,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    fontSize = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
            )
            append("$kbps kbps")
            pop()
            if (details.isNotEmpty()) append("   ")
        }
        append(details)
    }

/**
 * The stream a track will play from, before it has played: for a YouTube video its best audio
 * format (asked from YouTube). Null when it can't be known without fetching the audio.
 */
private suspend fun previewFormatOf(mediaId: String, database: com.metrolist.music.db.MusicDatabase): FormatEntity? {
    if (isYouTubeVideoId(mediaId)) {
        val best = com.metrolist.music.utils.YTPlayerUtils.playerResponseForMetadata(mediaId).getOrNull()
            ?.streamingData?.adaptiveFormats
            ?.filter { it.isAudio }
            ?.maxByOrNull { it.bitrate }
            ?: return null
        return FormatEntity(
            id = mediaId,
            itag = best.itag,
            mimeType = best.mimeType.substringBefore(';'),
            codecs = best.mimeType.substringAfter("codecs=", "").trim('"', ' '),
            bitrate = best.bitrate,
            sampleRate = best.audioSampleRate,
            contentLength = best.contentLength ?: 0L,
            loudnessDb = best.loudnessDb,
            playbackUrl = null,
        )
    }
    // Other sources don't state it before the audio is fetched: unknown, never assumed.
    return null
}
