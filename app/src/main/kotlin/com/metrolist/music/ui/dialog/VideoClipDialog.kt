/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.dialog

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.metrolist.music.R
import com.metrolist.music.playback.VideoClipDownloader
import com.metrolist.music.utils.makeTimeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Download the music video": the clips YouTube has for the track, each with its frame, and under
 * them the quality (1080p unless changed). The download runs on with a notification.
 */
@Composable
fun VideoClipDialog(
    title: String,
    artist: String,
    durationSec: Int?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var candidates by remember { mutableStateOf<List<VideoClipDownloader.Candidate>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var height by remember { mutableIntStateOf(VideoClipDownloader.DEFAULT_HEIGHT) }
    var preview by remember { mutableStateOf<VideoClipDownloader.Candidate?>(null) }

    LaunchedEffect(title, artist) {
        candidates = withContext(Dispatchers.IO) {
            runCatching { VideoClipDownloader.search(title, artist, durationSec) }.getOrDefault(emptyList())
        }
        selected = candidates.firstOrNull()?.videoId
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.video_clip_download)) },
        text = {
            Column {
                when {
                    loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(vertical = 16.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.video_clip_searching))
                    }

                    candidates.isEmpty() -> Text(stringResource(R.string.video_clip_none), modifier = Modifier.padding(vertical = 16.dp))

                    else -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    ) {
                        items(candidates, key = { it.videoId }) { clip ->
                            ClipRow(clip, isSelected = clip.videoId == selected, onLongClick = { preview = clip }) { selected = clip.videoId }
                        }
                    }
                }
                if (candidates.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.video_clip_quality), style = MaterialTheme.typography.labelLarge)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    ) {
                        VideoClipDownloader.HEIGHTS.forEach { h ->
                            FilterChip(
                                selected = height == h,
                                onClick = { height = h },
                                label = { Text(if (h >= 2160) "4K" else "${h}p") },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.video_clip_quality_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = {
                    val clip = candidates.firstOrNull { it.videoId == selected } ?: return@TextButton
                    VideoClipDownloader.start(context, clip, height, listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - "))
                    Toast.makeText(context, R.string.video_clip_started, Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_download)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )

    preview?.let { clip ->
        ClipPreview(clip, onPick = { selected = clip.videoId; preview = null }, onDismiss = { preview = null })
    }
}

@Composable
private fun ClipRow(clip: VideoClipDownloader.Candidate, isSelected: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(128.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .then(if (isSelected) Modifier.border(2.dp, accent, RoundedCornerShape(8.dp)) else Modifier),
        ) {
            AsyncImage(
                model = clip.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            clip.durationSec?.let { d ->
                Text(
                    text = makeTimeString(d * 1000L),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                clip.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ClipKindBadge(clip)
            Text(
                clip.channel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** What kind of video it is: YouTube's label plus whether the picture actually moves. */
@Composable
private fun ClipKindBadge(clip: VideoClipDownloader.Candidate) {
    val motion by androidx.compose.runtime.produceState<Float?>(null, clip.videoId) { value = VideoClipDownloader.motionOf(clip.videoId) }
    val still = motion?.let { it < VideoClipDownloader.STILL_MOTION } == true
    val (label, color) = when {
        still -> stringResource(R.string.video_clip_kind_still) to MaterialTheme.colorScheme.error
        clip.videoType == "MUSIC_VIDEO_TYPE_OMV" -> stringResource(R.string.video_clip_kind_official) to Color(0xFF4CAF50)
        clip.videoType == "MUSIC_VIDEO_TYPE_ATV" -> stringResource(R.string.video_clip_kind_audio) to MaterialTheme.colorScheme.error
        clip.videoType == "MUSIC_VIDEO_TYPE_UGC" -> stringResource(R.string.video_clip_kind_fan) to Color(0xFFE0A030)
        motion != null -> stringResource(R.string.video_clip_kind_moving) to MaterialTheme.colorScheme.primary
        else -> return
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/** Long press: three frames of the video (a quarter, half, three quarters in) to tell a clip from a still cover. */
@Composable
private fun ClipPreview(clip: VideoClipDownloader.Candidate, onPick: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(clip.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ClipKindBadge(clip)
                clip.frameUrls.forEachIndexed { i, url ->
                    Box {
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
                        )
                        Text(
                            text = listOf("25%", "50%", "75%")[i],
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onPick) { Text(stringResource(R.string.video_clip_pick)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}