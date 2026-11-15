/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme.shell.pulse

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.constants.PulseStrengthKey
import com.metrolist.music.ui.theme.shell.LocalShell
import com.metrolist.music.ui.theme.shell.ShellRuntime
import com.metrolist.music.ui.theme.shell.ShellTheme
import com.metrolist.music.utils.rememberPreference
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The music as the interface feels it, smoothed for the eye: a fast rise (40 ms) and a soft fall
 * (260 ms), so nothing flickers. Read only in draw lambdas — the screens never recompose for it.
 *  - [level]: overall loudness, 0..1;
 *  - [low]: the lows (bass), 0..1;
 *  - [beat]: 1 on a strong beat, falling back to 0 in ~180 ms;
 *  - [slow]: loudness over the last ~2 s (the background's breathing).
 * Everything is already scaled by the chosen strength; all zero with motion reduced.
 */
object PulseLevels {
    var level by mutableFloatStateOf(0f)
        private set
    var low by mutableFloatStateOf(0f)
        private set
    var beat by mutableFloatStateOf(0f)
        private set
    var slow by mutableFloatStateOf(0f)
        private set

    private val raw = FloatArray(4)
    private var lowAverage = 0f
    private var lastBeatMs = 0L
    private var noDataSinceMs = 0L
    private var level0 = 0f
    private var low0 = 0f
    private var beat0 = 0f
    private var slow0 = 0f

    internal fun step(nowMs: Long, dtMs: Float, playing: Boolean, strength: Float) {
        val fresh = Pulse.sample(nowMs, raw)
        var targetLevel = 0f
        var targetLow = 0f
        if (fresh) {
            noDataSinceMs = 0L
            targetLevel = (raw[0] * 3.2f).coerceIn(0f, 1f)
            targetLow = (raw[1] * 4.5f).coerceIn(0f, 1f)
        } else if (playing) {
            // Playing but nothing heard (casting, another output): a calm breath at ~70 a minute.
            if (noDataSinceMs == 0L) noDataSinceMs = nowMs
            if (nowMs - noDataSinceMs > 1_000) {
                val b = (0.5f + 0.5f * sin(2f * PI.toFloat() * nowMs / 857f))
                targetLevel = 0.12f + 0.1f * b
                targetLow = 0.1f * b
            }
        }
        fun follow(current: Float, target: Float): Float {
            val tau = if (target > current) 40f else 260f
            return current + (target - current) * (1f - exp(-dtMs / tau))
        }
        level0 = follow(level0, targetLevel)
        low0 = follow(low0, targetLow)
        slow0 += (targetLevel - slow0) * (1f - exp(-dtMs / 2_000f))
        // A beat: the lows jump well above their average of the last second (at most every 200 ms).
        lowAverage += (targetLow - lowAverage) * (1f - exp(-dtMs / 1_000f))
        if (fresh && targetLow > 0.12f && targetLow > lowAverage * 1.45f && nowMs - lastBeatMs > 200) {
            beat0 = 1f
            lastBeatMs = nowMs
        } else {
            beat0 *= exp(-dtMs / 180f)
        }
        val k = if (ShellRuntime.calm) 0f else strength
        level = (level0 * k).coerceIn(0f, 1.5f)
        low = (low0 * k).coerceIn(0f, 1.5f)
        beat = (beat0 * k).coerceIn(0f, 1.5f)
        slow = (slow0 * k).coerceIn(0f, 1.5f)
    }
}

/**
 * Runs Pulse while it is the theme and the app is on screen: switches the tap's measuring on and
 * moves [PulseLevels] every frame. Composed once (with the page frame).
 */
@Composable
fun PulseDriver() {
    if (LocalShell.current != ShellTheme.PULSE) return
    val playerConnection = LocalPlayerConnection.current
    val strengthIndex by rememberPreference(PulseStrengthKey, defaultValue = 0)
    val strength = when (strengthIndex) {
        2 -> 1.5f
        1 -> 1f
        else -> 0.6f
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            Pulse.listening = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            Pulse.listening = false
        }
    }
    LaunchedEffect(strength, playerConnection) {
        var last = 0L
        while (true) {
            withFrameMillis { frame ->
                val dt = if (last == 0L) 16f else (frame - last).toFloat().coerceIn(1f, 100f)
                last = frame
                val playing = runCatching { playerConnection?.player?.isPlaying == true }.getOrDefault(false)
                PulseLevels.step(android.os.SystemClock.uptimeMillis(), dt, playing, strength)
            }
        }
    }
}

/** Pulse: a thin line of the accent along the top edge, its brightness and width following the loudness. */
@Composable
fun Modifier.pulseEdge(): Modifier {
    val accent = MaterialTheme.colorScheme.primary
    return drawWithContent {
        drawContent()
        val l = PulseLevels.level
        val w = (1f + 1.5f * l.coerceAtMost(1f)) * 1.dp.toPx()
        val inset = size.height.coerceAtMost(size.width) * 0.25f
        drawLine(
            accent.copy(alpha = (0.35f + 0.65f * l).coerceIn(0f, 1f)),
            Offset(inset, w / 2),
            Offset(size.width - inset, w / 2),
            strokeWidth = w,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Pulse around the player's cover: a soft halo of the accent following the lows (0–35 % opacity),
 * and the cover itself a touch bigger on a strong beat (up to 2.5 %). Put before the cover's clip.
 */
@Composable
fun Modifier.pulseGlow(radius: Dp = 0.dp): Modifier {
    if (LocalShell.current != ShellTheme.PULSE) return this
    val accent = MaterialTheme.colorScheme.primary
    return this
        .drawBehind {
            val l = PulseLevels.low.coerceAtMost(1f)
            val alpha = (0.08f + 0.27f * l).coerceIn(0f, 0.35f)
            val reach = size.minDimension * (0.62f + 0.12f * l)
            drawCircle(
                Brush.radialGradient(listOf(accent.copy(alpha = alpha), Color.Transparent), center, reach),
                reach,
                center,
            )
        }
        .graphicsLayer {
            val s = 1f + 0.025f * PulseLevels.beat.coerceAtMost(1f)
            scaleX = s
            scaleY = s
        }
}

/** Pulse over the pages: the surface breathes with the last seconds' loudness (±2 % light). */
@Composable
fun PulseBreath() {
    if (LocalShell.current != ShellTheme.PULSE) return
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val a = (PulseLevels.slow.coerceAtMost(1f) * 0.035f)
        if (a > 0.002f) drawRect(accent.copy(alpha = a))
    }
}
