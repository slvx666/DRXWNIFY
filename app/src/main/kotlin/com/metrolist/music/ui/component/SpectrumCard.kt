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
    // The full spectrogram made before for this track: opened at once, not rendered and saved again.
    var fullFile by remember(key) { mutableStateOf<java.io.File?>(null) }
    var showFull by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        fullFile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            SpectrumCheck.savedFull(context.applicationContext, mediaIds)
        }
    }

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
        if (fullFile != null) {
            showFull = true
            return
        }
        rendering = true
        scope.launch {
            val file = SpectrumCheck.renderFull(
                context.applicationContext, mediaIds,
                listOf(downloadUtil.downloadCache, downloadUtil.playerCache), exportedUri(),
                title = title,
                subtitle = listOfNotNull(codec, kbps?.let { "$it kbps" }).joinToString(" · "),
            )
            rendering = false
            if (file == null) {
                Toast.makeText(context, R.string.spectrum_not_cached, Toast.LENGTH_LONG).show()
                return@launch
            }
            fullFile = file
            showFull = true
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
                    stringResource(R.string.spectrum_tap_to_open),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                HowToRead()
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
                    OutlinedButton(enabled = !running, onClick = ::run, shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                        if (running) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        else Text(stringResource(R.string.spectrum_run))
                    }
                }
                OutlinedButton(enabled = !rendering, onClick = ::renderFull, shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                    if (rendering) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    else Text(stringResource(if (fullFile != null) R.string.spectrum_full_open else R.string.spectrum_full))
                }
            }
        }
    }

    val v = verdict
    if (fullScreen && v != null) SpectrumFullScreen(v, onDismiss = { fullScreen = false })
    val file = fullFile
    if (showFull && file != null) {
        FullSpectrogramViewer(
            file = file,
            title = title,
            onRedo = {
                showFull = false
                file.delete()
                fullFile = null
                renderFull()
            },
            onDismiss = { showFull = false },
        )
    }
}

/** The kept full spectrogram, in the app: pinch to zoom, share it or put a copy in the gallery. */
@Composable
private fun FullSpectrogramViewer(file: java.io.File, title: String, onRedo: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap = remember(file) {
        runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color(0xFF07060F), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.spectrum_full),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        runCatching {
                            val uri = SpectrumCheck.shareUri(context, file)
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                    null,
                                ),
                            )
                        }
                    }) { Icon(painterResource(R.drawable.share), stringResource(R.string.share), tint = Color.White) }
                    IconButton(onClick = {
                        scope.launch {
                            val saved = SpectrumCheck.saveToGallery(context.applicationContext, file, title)
                            Toast.makeText(context, if (saved != null) R.string.spectrum_full_saved else R.string.error_unknown, Toast.LENGTH_SHORT).show()
                        }
                    }) { Icon(painterResource(R.drawable.download), stringResource(R.string.spectrum_save_gallery), tint = Color.White) }
                    IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.close), null, tint = Color.White) }
                }
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
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap,
                            null,
                            contentScale = ContentScale.Fit,
                            filterQuality = FilterQuality.High,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                        )
                    }
                }
                OutlinedButton(onClick = onRedo, modifier = Modifier.padding(top = 8.dp), shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                    Text(stringResource(R.string.spectrum_full_redo), color = Color.White)
                }
            }
        }
    }
}

/** What to look at on the picture — the user judges, the app no longer does. */
@Composable
private fun HowToRead(color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = stringResource(R.string.spectrum_how_to_read),
        style = MaterialTheme.typography.bodySmall,
        color = color,
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
                HowToRead(color = Color.White.copy(alpha = 0.75f))
            }
        }
    }
}

