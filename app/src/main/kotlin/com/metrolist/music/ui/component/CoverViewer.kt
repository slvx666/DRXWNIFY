/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.utils.CoverSaver
import kotlinx.coroutines.launch

/**
 * Remembers whether a cover is open full screen. A cover calls [open] on tap and [save] on long
 * press; [Content] draws the viewer while it is open.
 */
class CoverViewerState(private val context: android.content.Context) {
    var url by mutableStateOf<String?>(null)
        private set
    var name by mutableStateOf("")
        private set

    fun open(url: String?, name: String) {
        if (url.isNullOrBlank()) return
        this.url = url
        this.name = name
    }

    fun close() {
        url = null
    }

    fun save(url: String?, name: String) = CoverSaver.saveInBackground(context, url, name)

    @Composable
    fun Content() {
        url?.let { CoverViewer(url = it, name = name, onDismiss = ::close) }
    }
}

@Composable
fun rememberCoverViewerState(): CoverViewerState {
    val context = LocalContext.current
    return remember { CoverViewerState(context.applicationContext) }
}

/**
 * A cover full screen: pinch to zoom, drag to look around, double tap to zoom in/out, long press
 * to save it to the gallery. Shown in the largest size the service serves.
 */
@Composable
fun CoverViewer(url: String, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxWidth by remember { mutableFloatStateOf(0f) }
    val largest = remember(url) { CoverSaver.largest(url) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    boxWidth = size.width.toFloat()
                    detectTransformGestures { _, pan, zoom, _ ->
                        val next = (scale.value * zoom).coerceIn(1f, 6f)
                        scope.launch { scale.snapTo(next) }
                        // Panning only makes sense zoomed in; keep the picture from leaving the screen.
                        val limit = boxWidth * (next - 1f) / 2f
                        offset = if (next <= 1f) {
                            Offset.Zero
                        } else {
                            Offset(
                                (offset.x + pan.x).coerceIn(-limit, limit),
                                (offset.y + pan.y).coerceIn(-limit, limit),
                            )
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (scale.value <= 1.01f) onDismiss() },
                        onDoubleTap = {
                            scope.launch {
                                if (scale.value > 1.01f) {
                                    offset = Offset.Zero
                                    scale.animateTo(1f, tween(220))
                                } else {
                                    scale.animateTo(2.5f, tween(220))
                                }
                            }
                        },
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            CoverSaver.saveInBackground(context, url, name)
                        },
                    )
                },
        ) {
            AsyncImage(
                model = largest,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).systemBarsPadding().padding(8.dp),
            ) {
                Icon(painterResource(R.drawable.close), contentDescription = stringResource(R.string.close), tint = Color.White)
            }
            Text(
                text = stringResource(R.string.cover_viewer_hint),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(16.dp),
            )
        }
    }
}
