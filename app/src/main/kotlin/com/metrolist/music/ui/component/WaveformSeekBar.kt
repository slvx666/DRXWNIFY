/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.content.Context
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.SpectrumCheck
import com.metrolist.music.resolver.FallbackIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * The AIMP-style seek bar: many thin vertical bars, each as tall as the music is loud at that
 * moment, the played part in the accent colour. The loudness is the track's own, decoded from the
 * download or the cache (never fetched), and only once the WHOLE track is on the phone: drawing the
 * cached part and growing it later made the bars jump on their own. Until then the bars are even,
 * and they change once, smoothly.
 */
@Composable
fun WaveformSeekBar(
    mediaId: String?,
    database: MusicDatabase,
    position: Long,
    duration: Long,
    enabled: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    onSeekPreview: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val downloadUtil = LocalDownloadUtil.current
    var levels by remember(mediaId) { mutableStateOf(mediaId?.let(Waveforms::cached)) }
    val currentPosition by rememberUpdatedState(position)

    // At once (a downloaded or fully cached track), then again when playback is far enough along
    // that the stream has most likely been cached to the end.
    LaunchedEffect(mediaId, duration > 0) {
        val id = mediaId ?: return@LaunchedEffect
        if (duration <= 0 || levels != null) return@LaunchedEffect
        for (checkpoint in Waveforms.CHECKPOINTS) {
            while (currentPosition.toFloat() / duration < checkpoint) delay(2_000L)
            Waveforms.load(context, database, downloadUtil, id, duration)?.let {
                levels = it
                return@LaunchedEffect
            }
        }
    }

    val placeholder = remember(mediaId) { Waveforms.placeholder(mediaId.orEmpty()) }
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownProgress by animateFloatAsState(progress, tween(180), label = "waveformProgress")
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    val appear by animateFloatAsState(if (levels != null) 1f else 0f, tween(450), label = "waveformAppear")

    fun fractionAt(x: Float, width: Float) = (x / width).coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(66.dp)
            .pointerInput(enabled, duration) {
                if (!enabled || duration <= 0) return@pointerInput
                detectTapGestures { offset -> onSeek((fractionAt(offset.x, size.width.toFloat()) * duration).toLong()) }
            }
            .pointerInput(enabled, duration) {
                if (!enabled || duration <= 0) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragFraction = fractionAt(offset.x, size.width.toFloat())
                        onSeekPreview((dragFraction * duration).toLong())
                    },
                    onDragEnd = {
                        dragging = false
                        onSeek((dragFraction * duration).toLong())
                    },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { change, _ ->
                        dragFraction = fractionAt(change.position.x, size.width.toFloat())
                        onSeekPreview((dragFraction * duration).toLong())
                    },
                )
            },
    ) {
        val bars = Waveforms.BARS
        val gap = size.width / bars
        val barWidth = (gap * 0.62f).coerceAtLeast(1.5f)
        val mid = size.height / 2
        val played = if (dragging) dragFraction else shownProgress
        val data = levels
        for (i in 0 until bars) {
            val known = data?.getOrNull(i)?.takeIf { it >= 0 }
            val level = if (known != null) {
                placeholder[i] + (known - placeholder[i]) * appear
            } else {
                placeholder[i]
            }
            val h = (size.height * 0.94f * level).coerceAtLeast(barWidth)
            val x = i * gap + (gap - barWidth) / 2
            val isPlayed = (i + 0.5f) / bars <= played
            val color = if (isPlayed) activeColor else inactiveColor
            drawRoundRect(
                color = color,
                topLeft = Offset(x, mid - h / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
        if (dragging) {
            val x = size.width * dragFraction
            drawRect(activeColor, topLeft = Offset(x - 1.dp.toPx(), 0f), size = Size(2.dp.toPx(), size.height))
        }
    }
}

/** Loudness per track, remembered in memory for the session and on disk. */
object Waveforms {
    const val BARS = 72

    /** Where in the track to look again (share played) while the whole of it isn't here yet. */
    val CHECKPOINTS = floatArrayOf(0f, 0.55f, 0.9f)

    private val memory = LruCache<String, FloatArray>(64)

    fun cached(mediaId: String): FloatArray? = memory.get(mediaId)

    suspend fun load(
        context: Context,
        database: MusicDatabase,
        downloadUtil: com.metrolist.music.playback.DownloadUtil,
        mediaId: String,
        durationMs: Long,
    ): FloatArray? = withContext(Dispatchers.IO) {
        memory.get(mediaId)?.takeIf { arr -> arr.none { it < 0 } }?.let { return@withContext it }
        val ids = buildList {
            add(mediaId)
            // The same catalog track under its other id (queued as mfb:, downloaded as a video id).
            runCatching {
                val catalogId = FallbackIds.catalogIdOf(mediaId)
                if (catalogId != null) {
                    database.getSpotifyMatch(catalogId)?.youtubeId?.let(::add)
                } else {
                    database.getSpotifyMatchByYouTubeId(mediaId)?.spotifyId?.let { add(FallbackIds.of(it)) }
                }
            }
        }
        // Stored ones first (worked out when the track was downloaded, under either of its ids).
        for (id in ids) {
            fileFor(context, id).takeIf { it.exists() }?.let { file ->
                runCatching {
                    val bytes = file.readBytes()
                    FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f }
                }.getOrNull()?.takeIf { it.size == BARS }?.let { memory.put(mediaId, it); return@withContext it }
            }
        }
        val exported = ids.firstNotNullOfOrNull { downloadUtil.downloadExporter.exportedUri(it) }
            ?: if (mediaId.startsWith("local:")) {
                runCatching { database.getSongById(mediaId)?.song?.localPath?.let(android.net.Uri::parse) }.getOrNull()
            } else {
                null
            }
        val levels = SpectrumCheck.waveform(
            context.applicationContext, ids, listOf(downloadUtil.downloadCache, downloadUtil.playerCache), exported, BARS, durationMs,
        ) ?: return@withContext null
        // Part of the track only: not shown (see the class comment). The last bar or two may stay
        // empty when the stated length runs a second past the audio — those take their neighbour.
        if (levels.dropLast(2).any { it < 0 }) return@withContext null
        for (i in levels.indices) if (levels[i] < 0) levels[i] = levels.getOrNull(i - 1)?.takeIf { it >= 0 } ?: 0.1f
        memory.put(mediaId, levels)
        runCatching {
            fileFor(context, mediaId).apply { parentFile?.mkdirs() }
                .writeBytes(ByteArray(BARS) { (levels[it] * 255).toInt().coerceIn(0, 255).toByte() })
        }
        levels
    }

    private val precomputeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val precomputeGate = kotlinx.coroutines.sync.Semaphore(1)

    /** Works out and stores the bars of a freshly downloaded file, one at a time, in the background. */
    fun precompute(context: Context, mediaId: String, uri: android.net.Uri) {
        if (fileFor(context, mediaId).exists()) return
        precomputeScope.launch {
            precomputeGate.acquire()
            try {
                val levels = SpectrumCheck.waveform(context.applicationContext, listOf(mediaId), emptyList(), uri, BARS)
                    ?.takeIf { arr -> arr.dropLast(2).none { it < 0 } } ?: return@launch
                for (i in levels.indices) if (levels[i] < 0) levels[i] = levels.getOrNull(i - 1)?.takeIf { it >= 0 } ?: 0.1f
                memory.put(mediaId, levels)
                runCatching {
                    fileFor(context, mediaId).apply { parentFile?.mkdirs() }
                        .writeBytes(ByteArray(BARS) { (levels[it] * 255).toInt().coerceIn(0, 255).toByte() })
                }
            } finally {
                precomputeGate.release()
            }
        }
    }

    /** Even bars until the real shape is known: no made-up shape that later changes. */
    fun placeholder(@Suppress("UNUSED_PARAMETER") mediaId: String): FloatArray = FloatArray(BARS) { 0.32f }

    /** A gentle sample shape, for the style picker only. */
    fun sample(): FloatArray {
        val rnd = Random(7)
        var v = 0.45f
        return FloatArray(BARS) { i ->
            v = (v + (rnd.nextFloat() - 0.5f) * 0.25f).coerceIn(0.25f, 0.7f)
            val edge = minOf(i, BARS - 1 - i).coerceAtMost(6) / 6f
            v * (0.5f + 0.5f * edge)
        }
    }

    private fun fileFor(context: Context, mediaId: String) =
        java.io.File(context.cacheDir, "waveforms/" + mediaId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".bin")
}

/** A still sample of the bar seek bar, for the style picker. */
@Composable
fun WaveformPreview(activeColor: Color, inactiveColor: Color, modifier: Modifier = Modifier) {
    val sample = remember { Waveforms.sample().map { (it * 1.35f).coerceAtMost(1f) } }
    Canvas(modifier.fillMaxWidth().height(36.dp)) {
        val gap = size.width / sample.size
        val barWidth = (gap * 0.58f).coerceAtLeast(1.5f)
        sample.forEachIndexed { i, level ->
            val h = size.height * level
            drawRoundRect(
                color = if (i < sample.size * 0.4f) activeColor else inactiveColor,
                topLeft = Offset(i * gap + (gap - barWidth) / 2, (size.height - h) / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
    }
}
