/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.R
import com.metrolist.music.playback.SpectrumCheck
import kotlinx.coroutines.launch

/**
 * "Is the quality real?" in the track's info sheet. The track may be known under several ids (its
 * source id, the YouTube upload it plays from): all of them are tried, and the downloaded file
 * itself is analysed when there is one. Tapping the picture opens it full screen; "Full
 * spectrogram" renders the whole track into a PNG like a PC analyser does.
 */
@Composable
fun SpectrumCard(
    mediaIds: List<String>,
    codec: String?,
    kbps: Int?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val downloadUtil = LocalDownloadUtil.current
    val scope = rememberCoroutineScope()
    val key = mediaIds.firstOrNull().orEmpty()
    val verdict by remember(key) { SpectrumCheck.verdictFlow(key) }.collectAsState(initial = null)
    var running by remember(key) { mutableStateOf(false) }
    var failed by remember(key) { mutableStateOf(false) }
    var rendering by remember(key) { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }

    suspend fun exportedUri(): android.net.Uri? =
        mediaIds.firstNotNullOfOrNull { downloadUtil.downloadExporter.exportedUri(it) }

    fun run() {
        running = true
        failed = false
        scope.launch {
            failed = SpectrumCheck.analyze(
                context.applicationContext, mediaIds, codec, kbps,
                listOf(downloadUtil.downloadCache, downloadUtil.playerCache), exportedUri(),
            ) == null
            running = false
        }
    }

    // A downloaded track is checked by itself: the answer is there when the sheet opens.
    LaunchedEffect(key) {
        if (verdict == null && exportedUri() != null) run()
    }

    fun renderFull() {
        rendering = true
        scope.launch {
            val uri = SpectrumCheck.renderFull(
                context.applicationContext, mediaIds,
                listOf(downloadUtil.downloadCache, downloadUtil.playerCache), exportedUri(),
                title = title,
                subtitle = listOfNotNull(codec, kbps?.let { "$it kbps" }).joinToString(" · "),
            )
            rendering = false
            if (uri == null) {
                Toast.makeText(context, R.string.spectrum_not_cached, Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(context, R.string.spectrum_full_saved, Toast.LENGTH_SHORT).show()
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.spectrum_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val v = verdict
            if (v != null) {
                SpectrogramImage(v, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(8.dp)).clickable { fullScreen = true })
                Text(
                    text = stringResource(R.string.spectrum_legend),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    stringResource(R.string.spectrum_tap_to_open),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                VerdictText(v)
            } else {
                Text(
                    text = stringResource(if (failed) R.string.spectrum_not_cached else R.string.spectrum_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (v == null) {
                    OutlinedButton(enabled = !running, onClick = ::run) {
                        if (running) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        else Text(stringResource(R.string.spectrum_run))
                    }
                }
                OutlinedButton(enabled = !rendering, onClick = ::renderFull) {
                    if (rendering) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    else Text(stringResource(R.string.spectrum_full))
                }
            }
        }
    }

    val v = verdict
    if (fullScreen && v != null) SpectrumFullScreen(v, onDismiss = { fullScreen = false })
}

@Composable
private fun VerdictText(v: SpectrumCheck.Verdict) {
    val color = when {
        v.upscaledPercent >= 60 -> MaterialTheme.colorScheme.error
        v.upscaledPercent >= 30 -> Color(0xFFE0A030)
        else -> Color(0xFF4CAF50)
    }
    Text(
        text = stringResource(
            when (v.kind) {
                SpectrumCheck.Kind.FULL_BAND -> R.string.spectrum_kind_full
                SpectrumCheck.Kind.MIXED -> R.string.spectrum_kind_mixed
                SpectrumCheck.Kind.TRANSCODED -> R.string.spectrum_kind_transcoded
                SpectrumCheck.Kind.GENUINE -> R.string.spectrum_kind_genuine
            },
            v.upscaledPercent,
        ),
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    val equivalent = v.equivalentKbps
    if (equivalent != null) {
        Text(
            text = if (equivalent >= 1411) stringResource(R.string.spectrum_full_band, v.cutoffHz / 1000.0)
            else stringResource(R.string.spectrum_equivalent, equivalent, v.realCutoffHz / 1000.0),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    v.shelfHz?.let { hz ->
        Text(
            text = stringResource(R.string.spectrum_shelf, hz / 1000.0, (v.shelfShare * 100).toInt()),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFFF6B7D),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    Text(
        text = stringResource(
            R.string.spectrum_distribution,
            v.lowCutoffHz / 1000.0, v.cutoffHz / 1000.0, v.maxCutoffHz / 1000.0, (v.lowShare * 100).toInt(),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = stringResource(R.string.spectrum_expected, v.expectedHz / 1000.0, v.claimedKbps?.toString() ?: "?") +
            (if (v.fromFile) " · " + stringResource(R.string.spectrum_from_file) else ""),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SpectrogramImage(v: SpectrumCheck.Verdict, modifier: Modifier) {
    val bitmap = remember(v) {
        Bitmap.createBitmap(v.pixels, v.imageWidth, v.imageHeight, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Box(modifier) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.High,
            modifier = Modifier.fillMaxSize(),
        )
        FrequencyOverlay(v, Modifier.fillMaxSize(), labels = false)
    }
}

/**
 * kHz grid, the expected top (dashed green), the median cut (red) and every frame's own cut (thin
 * cyan trace) — a trace that jumps is a track pieced together from different sources.
 */
@Composable
private fun FrequencyOverlay(v: SpectrumCheck.Verdict, modifier: Modifier, labels: Boolean) {
    val cutColor = MaterialTheme.colorScheme.error
    val expectedColor = Color(0xFF4CAF50)
    Canvas(modifier) {
        val nyquist = v.sampleRate / 2f
        fun yOf(hz: Float) = size.height * (1 - hz / nyquist)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(200, 255, 255, 255)
            textSize = 11.dp.toPx()
            isAntiAlias = true
        }
        var khz = 2
        while (khz * 1000 < nyquist) {
            val y = yOf(khz * 1000f)
            drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            if (labels) drawContext.canvas.nativeCanvas.drawText("$khz kHz", 6.dp.toPx(), y - 3.dp.toPx(), paint)
            khz += 2
        }
        v.segmentStarts.drop(1).forEach { col ->
            val x = size.width * col / v.imageWidth
            drawLine(Color.White.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
        }
        val trace = Path()
        var open = false
        v.frameCutoffs.forEachIndexed { i, hz ->
            if (hz.isNaN() || (i in v.segmentStarts)) { open = false }
            if (hz.isNaN()) return@forEachIndexed
            val x = size.width * (i + 0.5f) / v.imageWidth
            val y = yOf(hz)
            if (!open) { trace.moveTo(x, y); open = true } else trace.lineTo(x, y)
        }
        drawLine(expectedColor.copy(alpha = 0.8f), Offset(0f, yOf(v.expectedHz.toFloat())), Offset(size.width, yOf(v.expectedHz.toFloat())), strokeWidth = 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
        drawLine(cutColor, Offset(0f, yOf(v.cutoffHz.toFloat())), Offset(size.width, yOf(v.cutoffHz.toFloat())), strokeWidth = 2.5f)
        // The former encoder ceiling, when one was found.
        v.shelfHz?.let { hz ->
            drawLine(
                Color(0xFFFF3D57),
                Offset(0f, yOf(hz.toFloat())),
                Offset(size.width, yOf(hz.toFloat())),
                strokeWidth = 3f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
            )
        }
    }
}

@Composable
private fun SpectrumFullScreen(v: SpectrumCheck.Verdict, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color(0xFF07060F), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.spectrum_title),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.close), null, tint = Color.White) }
                }
                // Pinch to zoom, drag to look around: the frequencies above the cut can be inspected.
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                val state = rememberTransformableState { zoom, pan, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .transformable(state),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    ) {
                        val bitmap = remember(v) {
                            Bitmap.createBitmap(v.pixels, v.imageWidth, v.imageHeight, Bitmap.Config.ARGB_8888).asImageBitmap()
                        }
                        Image(bitmap, null, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.High, modifier = Modifier.fillMaxSize())
                        FrequencyOverlay(v, Modifier.fillMaxSize(), labels = true)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.spectrum_profile), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                ProfileGraph(v, Modifier.fillMaxWidth().height(140.dp).padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                VerdictText(v)
                Text(
                    stringResource(R.string.spectrum_how, v.seconds, v.loudFrames),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * White: the typical level per frequency. Orange: the share of moments whose cut is at least that
 * high — an encoder's cut is where it falls from ~100 % to ~0 %.
 */
@Composable
private fun ProfileGraph(v: SpectrumCheck.Verdict, modifier: Modifier) {
    val cutColor = MaterialTheme.colorScheme.error
    Canvas(modifier.background(Color(0xFF111018))) {
        val points = v.profileDb.size
        val max = v.profileDb.max()
        val min = max - 100f
        fun x(i: Int) = size.width * i / (points - 1)
        val level = Path()
        val occ = Path()
        for (i in 0 until points) {
            val y = size.height * (1 - ((v.profileDb[i] - min) / (max - min)).coerceIn(0f, 1f))
            val yo = size.height * (1 - v.occupancy[i].coerceIn(0f, 1f))
            if (i == 0) { level.moveTo(x(i), y); occ.moveTo(x(i), yo) } else { level.lineTo(x(i), y); occ.lineTo(x(i), yo) }
        }
        drawPath(occ, Color(0xFFF98C2F), style = Stroke(width = 2f))
        drawPath(level, Color.White, style = Stroke(width = 2f))
        val nyquist = v.sampleRate / 2f
        val cx = size.width * v.cutoffHz / nyquist
        drawLine(cutColor, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2f)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(170, 255, 255, 255)
            textSize = 10.dp.toPx()
            isAntiAlias = true
        }
        var khz = 5
        while (khz * 1000 < nyquist) {
            val gx = size.width * khz * 1000 / nyquist
            drawLine(Color.White.copy(alpha = 0.15f), Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText("$khz", gx + 3.dp.toPx(), size.height - 4.dp.toPx(), paint)
            khz += 5
        }
    }
}
