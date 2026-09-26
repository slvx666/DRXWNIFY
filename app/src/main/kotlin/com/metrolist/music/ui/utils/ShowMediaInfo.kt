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
fun ShowMediaInfo(videoId: String) {
    if (videoId.isBlank() || videoId.isEmpty()) return

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
                    .asPaddingValues()
            )
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (infoDone && (song != null || playerMetadata != null)) {
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
                    val format = currentFormat
                    val kbps = format?.bitrate?.takeIf { it > 0 }
                        ?.let { com.metrolist.music.ui.component.AudioQualityLevel.kbps(it) }
                    val codec = com.metrolist.music.ui.component.AudioQualityLevel.codecName(format?.mimeType, format?.codecs)
                    QualityHero(kbps = kbps, codec = codec, sampleRate = format?.sampleRate, source = audioSource)

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
                        copyItem(stringResource(R.string.song_title), song?.title ?: playerMetadata?.title, R.drawable.music_note),
                        copyItem(
                            stringResource(R.string.song_artists),
                            song?.artists?.joinToString { it.name } ?: playerMetadata?.artists?.joinToString { it.name },
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

/** The quality at a glance: the bitrate big and in its colour, then codec, sample rate and source. */
@Composable
private fun QualityHero(kbps: Int?, codec: String?, sampleRate: Int?, source: String?) {
    val color = kbps?.let { com.metrolist.music.ui.dialog.bitrateColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .background(color.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .padding(vertical = 18.dp, horizontal = 16.dp),
    ) {
        Text(
            text = kbps?.let { "$it kbps" } ?: stringResource(R.string.quality_unknown_yet),
            style = if (kbps != null) MaterialTheme.typography.displaySmall else MaterialTheme.typography.bodyMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = color,
        )
        val details = listOfNotNull(codec, sampleRate?.let(::khz), source)
        if (details.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = details.joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
