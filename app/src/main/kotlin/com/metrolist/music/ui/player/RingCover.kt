/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.playback.PlayerConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The "ring" slider style: instead of a bar under the cover, the cover is round and the track's
 * progress runs around it. The round cover's share of the square it stands in (the rest is the ring).
 */
const val RING_COVER_SHARE = 0.84f

/**
 * The round cover turning while the track plays (a turn in 40 s), when chosen. On pause it slows
 * to a stop over ~0.8 s instead of freezing, and speeds up the same way again.
 */
@Composable
fun Modifier.ringSpin(playerConnection: PlayerConnection, active: Boolean): Modifier {
    if (!active) return this
    var angle by remember { mutableFloatStateOf(0f) }
    val speed = remember { Animatable(0f) }
    val playing by playerConnection.isPlaying.collectAsState()
    LaunchedEffect(playing) {
        speed.animateTo(if (playing) 360f / 40_000f else 0f, tween(800))
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
 * The ring around the round cover: how far the track has gone as an arc, a dot at its end. A finger
 * on the ring drags the dot round and seeks there on release (the time shows while dragging); a
 * touch anywhere else goes to the cover as usual (open it, seek by the edges, swipe to the next).
 *
 * [onSeek] does the seek (the player, or the cast device); [enabled] is false for a Listen Together guest.
 */
@Composable
fun RingProgress(
    playerConnection: PlayerConnection,
    activeColor: Color,
    trackColor: Color,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    // While dragging: the share of the track under the finger.
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
            delay(200)
        }
    }
    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(playerConnection, enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val ringR = size.width / 2f - 8.dp.toPx()
                        val d = hypot(down.position.x - c.x, down.position.y - c.y)
                        // Only a touch on the ring (±22 dp) is the ring's.
                        if (abs(d - ringR) > 22.dp.toPx() || duration <= 0L) return@awaitEachGesture
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
                            onSeek(to)
                            position = to
                        }
                        dragFraction = null
                        // The ring gives a little when let go.
                        scope.launch {
                            settle.snapTo(1.025f)
                            settle.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 520f))
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = settle.value
                    scaleY = settle.value
                },
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.width / 2f - 8.dp.toPx()
            val stroke = 4.dp.toPx()
            drawCircle(trackColor, r, c, style = Stroke(stroke))
            val f = dragFraction ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            drawArc(
                activeColor,
                startAngle = -90f,
                sweepAngle = 360f * f,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val a = Math.toRadians((360.0 * f) - 90.0)
            val dot = if (dragFraction != null) 9.dp.toPx() else 6.dp.toPx()
            drawCircle(activeColor, dot, Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()))
        }
        AnimatedVisibility(
            visible = dragFraction != null,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            val shown = ((dragFraction ?: 0f) * duration).toLong() / 1000
            Text(
                "%d:%02d".format(shown / 60, shown % 60),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}
