/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.metrolist.music.playback.PlayerConnection
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Orbit: the round cover's share of the square it stands in (the rest is the ring). */
const val ORBIT_COVER_SHARE = 0.82f

/**
 * Orbit's cover turns slowly while the track plays (a turn in 40 s). On pause it slows to a stop
 * over ~0.8 s instead of freezing, and speeds up the same way again. Motion reduced: still.
 */
@Composable
fun Modifier.orbitSpin(playerConnection: PlayerConnection, active: Boolean): Modifier {
    var angle by remember { mutableFloatStateOf(0f) }
    val speed = remember { Animatable(0f) }
    val playing by playerConnection.isPlaying.collectAsState()
    LaunchedEffect(playing, active) {
        val target = if (playing && active && !ShellRuntime.calm) 360f / 40_000f else 0f
        speed.animateTo(target, tween(800))
    }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameMillis { t ->
                if (last != 0L && speed.value != 0f) angle = (angle + speed.value * (t - last)) % 360f
                last = t
            }
        }
    }
    return graphicsLayer { rotationZ = angle }
}

/**
 * Orbit's ring around the cover: how far the track has gone as an arc of the accent, a dot at its
 * end. A finger on the ring drags the dot around and seeks there on release (the time shows while
 * dragging); a touch anywhere else goes to the cover as usual (open it, seek by the edges, swipe).
 */
@Composable
fun OrbitRing(playerConnection: PlayerConnection, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    // While dragging: the fraction under the finger.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val settle = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(playerConnection) {
        while (true) {
            withFrameMillis { }
            runCatching {
                position = playerConnection.player.currentPosition
                duration = playerConnection.player.duration.coerceAtLeast(0L)
            }
            kotlinx.coroutines.delay(200)
        }
    }
    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(playerConnection) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val outer = size.width / 2f
                        val ringR = outer - 7.dp.toPx()
                        val d = hypot(down.position.x - c.x, down.position.y - c.y)
                        // Only a touch on the ring (±20 dp) is the ring's.
                        if (kotlin.math.abs(d - ringR) > 20.dp.toPx() || duration <= 0L) return@awaitEachGesture
                        down.consume()
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        fun fractionAt(p: Offset): Float {
                            val a = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())) + 90.0
                            return (((a % 360) + 360) % 360 / 360.0).toFloat()
                        }
                        dragFraction = fractionAt(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChange() != Offset.Zero) change.consume()
                            dragFraction = fractionAt(change.position)
                        }
                        dragFraction?.let { f ->
                            val to = (duration * f).toLong()
                            runCatching { playerConnection.player.seekTo(to) }
                            position = to
                        }
                        dragFraction = null
                        // The ring gives a little when let go.
                        scope.launch {
                            settle.snapTo(1.03f)
                            settle.animateTo(1f, ShellMotion.jelly())
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = settle.value
                    scaleY = settle.value
                },
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.width / 2f - 7.dp.toPx()
            val stroke = 3.dp.toPx()
            drawCircle(track, r, c, style = Stroke(stroke))
            val f = dragFraction ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            drawArc(
                accent,
                startAngle = -90f,
                sweepAngle = 360f * f,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val a = Math.toRadians((360.0 * f) - 90.0)
            val dot = if (dragFraction != null) 8.dp.toPx() else 5.dp.toPx()
            drawCircle(accent, dot, Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()))
        }
        AnimatedVisibility(
            visible = dragFraction != null,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp),
        ) {
            val shown = ((dragFraction ?: 0f) * duration).toLong() / 1000
            Text(
                "%d:%02d".format(shown / 60, shown % 60),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .background(accent, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
