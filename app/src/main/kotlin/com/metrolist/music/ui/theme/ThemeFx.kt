/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/*
 * The moving layer of the experimental themes, over the pages (touches pass through, ~30 frames
 * a second on the frame clock, still when the theme's animations are off):
 *  - Satanic: ash rising (white on black, black on the ash-grey day), a large inverted pentagram
 *    turning slowly in the dark;
 *  - Gothic: dust drifting down, bats crossing now and then, the corners in shadow;
 *  - Street art: marker drips running down from the top and drying, a big tag on the wall;
 *  - Breakcore: scanlines, flickering grain, glitch bands, crossed-out words floating up.
 */

@Composable
private fun rememberFxClock(running: Boolean): MutableFloatState {
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val start = withFrameMillis { it } - (seconds.floatValue * 1000).toLong()
        while (true) {
            kotlinx.coroutines.delay(33)
            withFrameMillis { now -> seconds.floatValue = (now - start) / 1000f }
        }
    }
    return seconds
}

/** The layer for [preset]; nothing for the themes without one. */
@Composable
internal fun ExperimentalFx(preset: AppThemePreset, modifier: Modifier = Modifier) {
    when (preset) {
        AppThemePreset.SATANIC -> SatanicFx(modifier)
        AppThemePreset.GOTHIC -> GothicFx(modifier)
        AppThemePreset.STREET -> StreetFx(modifier)
        AppThemePreset.BREAKCORE -> BreakcoreFx(modifier)
        else -> Unit
    }
}

private fun frac(x: Float) = x - floor(x)

// ── Satanic ─────────────────────────────────────────────────────────────────────────────────────

private class Ash(val x: Float, val speed: Float, val size: Float, val phase: Float, val sway: Float, val alpha: Float)

@Composable
private fun SatanicFx(modifier: Modifier) {
    val ash = remember {
        val r = Random(666)
        List(54) { Ash(r.nextFloat(), 0.035f + r.nextFloat() * 0.06f, 0.8f + r.nextFloat() * 2f, r.nextFloat(), 6f + r.nextFloat() * 16f, 0.2f + r.nextFloat() * 0.4f) }
    }
    val clock = rememberFxClock(LocalThemeAnimations.current)
    val ink = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val t = clock.floatValue
        // The pentagram in the dark, turning a degree every two seconds.
        val r = size.minDimension * 0.42f
        val c = Offset(size.width * 0.62f, size.height * 0.58f)
        rotate(t * 0.5f, c) { pentagram(c, r, ink.copy(alpha = if (dark) 0.045f else 0.06f), 1.5f * density) }
        // Ash: up from below, swaying, fading out near the top.
        ash.forEach { a ->
            val p = frac(a.phase + t * a.speed)
            val y = size.height * (1.05f - p * 1.1f)
            val x = a.x * size.width + sin((t * 0.7f + a.phase * 10f).toDouble()).toFloat() * a.sway * density
            val fade = (minOf(p, 1f - p) * 4f).coerceIn(0f, 1f)
            val flicker = 0.75f + 0.25f * sin((t * 5f + a.phase * 30f).toDouble()).toFloat()
            drawCircle(ink.copy(alpha = a.alpha * fade * flicker), a.size * density, Offset(x, y))
        }
    }
}

/** A five-pointed star, point down, with its circle. */
internal fun DrawScope.pentagram(center: Offset, r: Float, color: Color, width: Float) {
    val pts = (0 until 5).map { k ->
        val a = PI / 2 + k * 2 * PI / 5
        Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
    }
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (k in 1..5) pts[(k * 2) % 5].let { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, color, style = Stroke(width))
    drawCircle(color, r * 1.1f, center, style = Stroke(width))
    drawCircle(color, r * 1.18f, center, style = Stroke(width * 0.6f))
}

// ── Gothic ──────────────────────────────────────────────────────────────────────────────────────

private class Mote(val x: Float, val speed: Float, val size: Float, val phase: Float, val alpha: Float)

@Composable
private fun GothicFx(modifier: Modifier) {
    val motes = remember {
        val r = Random(1347)
        List(30) { Mote(r.nextFloat(), 0.012f + r.nextFloat() * 0.025f, 0.8f + r.nextFloat() * 1.6f, r.nextFloat(), 0.15f + r.nextFloat() * 0.25f) }
    }
    val clock = rememberFxClock(LocalThemeAnimations.current)
    val ink = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val t = clock.floatValue
        // The corners in shadow.
        drawRect(
            Brush.radialGradient(
                0.55f to Color.Transparent,
                1f to Color.Black.copy(alpha = if (dark) 0.38f else 0.14f),
                center = Offset(size.width / 2, size.height / 2),
                radius = size.maxDimension * 0.75f,
            ),
        )
        motes.forEach { m ->
            val p = frac(m.phase + t * m.speed)
            val y = size.height * (p * 1.1f - 0.05f)
            val x = m.x * size.width + sin((t * 0.3f + m.phase * 9f).toDouble()).toFloat() * 18f * density
            drawCircle(ink.copy(alpha = m.alpha * (minOf(p, 1f - p) * 5f).coerceIn(0f, 1f)), m.size * density, Offset(x, y))
        }
        // Bats crossing the upper part of the screen, each every ~20 s.
        listOf(0f to 0.18f, 7.3f to 0.3f, 13.1f to 0.12f).forEachIndexed { i, (offset, height) ->
            val cycle = 21f + i * 3f
            val p = (t + offset) % cycle / 9f
            if (p in 0f..1f) {
                val x = if (i % 2 == 0) size.width * (1.1f - p * 1.2f) else size.width * (-0.1f + p * 1.2f)
                val y = size.height * height + sin((p * 6).toDouble()).toFloat() * 14f * density
                bat(Offset(x, y), (9f + i * 2f) * density, sin((t * 14f + i).toDouble()).toFloat(), ink.copy(alpha = if (dark) 0.55f else 0.6f))
            }
        }
    }
}

/** A bat: body and two scalloped wings, [flap] −1..1 lifting and lowering them. */
private fun DrawScope.bat(c: Offset, s: Float, flap: Float, color: Color) {
    val lift = flap * s * 0.45f
    val wing = Path().apply {
        moveTo(c.x, c.y)
        lineTo(c.x - s * 0.5f, c.y - s * 0.35f - lift * 0.5f)
        lineTo(c.x - s * 1.4f, c.y - s * 0.5f - lift)
        lineTo(c.x - s * 1.1f, c.y + s * 0.05f - lift * 0.6f)
        lineTo(c.x - s * 0.8f, c.y - s * 0.05f - lift * 0.4f)
        lineTo(c.x - s * 0.5f, c.y + s * 0.2f - lift * 0.2f)
        close()
        moveTo(c.x, c.y)
        lineTo(c.x + s * 0.5f, c.y - s * 0.35f - lift * 0.5f)
        lineTo(c.x + s * 1.4f, c.y - s * 0.5f - lift)
        lineTo(c.x + s * 1.1f, c.y + s * 0.05f - lift * 0.6f)
        lineTo(c.x + s * 0.8f, c.y - s * 0.05f - lift * 0.4f)
        lineTo(c.x + s * 0.5f, c.y + s * 0.2f - lift * 0.2f)
        close()
    }
    drawPath(wing, color)
    drawCircle(color, s * 0.22f, Offset(c.x, c.y + s * 0.05f))
    drawCircle(color, s * 0.14f, Offset(c.x, c.y - s * 0.2f))
}

// ── Street art ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun StreetFx(modifier: Modifier) {
    val clock = rememberFxClock(LocalThemeAnimations.current)
    val marker = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    val tag = remember { tagStrokes(Random(93)) }
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val t = clock.floatValue
        // The tag on the wall, low on the left, big and faint.
        val w = size.width * 0.7f
        translate(size.width * 0.06f, size.height * 0.62f) {
            tag.forEach { stroke ->
                val path = Path().apply {
                    stroke.forEachIndexed { k, (x, y) -> if (k == 0) moveTo(x * w, y * w * 0.45f) else lineTo(x * w, y * w * 0.45f) }
                }
                drawPath(path, ink.copy(alpha = if (dark) 0.06f else 0.07f), style = Stroke(9f * density, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
        // Drips: each runs down for a few seconds, holds, dries and starts again somewhere else.
        for (i in 0 until 6) {
            val cycle = 7f + i * 1.3f
            val n = floor((t + i * 2.1f) / cycle)
            val p = ((t + i * 2.1f) % cycle) / cycle
            val r = Random((n * 31 + i).toInt())
            val x = size.width * (0.05f + r.nextFloat() * 0.9f)
            val len = size.height * (0.08f + r.nextFloat() * 0.22f)
            val grow = (p / 0.45f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }
            val alpha = if (p < 0.75f) 1f else (1f - (p - 0.75f) / 0.25f)
            val color = marker.copy(alpha = (if (dark) 0.45f else 0.55f) * alpha)
            val width = (2.5f + r.nextFloat() * 2f) * density
            drawLine(color, Offset(x, 0f), Offset(x, len * grow), width, cap = StrokeCap.Round)
            drawCircle(color, width * 0.9f, Offset(x, len * grow + width * 0.4f))
        }
    }
}

/** A made-up tag: a few looping strokes, in a 1 × 0.45 box. */
private fun tagStrokes(r: Random): List<List<Pair<Float, Float>>> = List(4) { s ->
    val x0 = s * 0.24f
    List(9) { k ->
        val x = x0 + k * 0.03f + r.nextFloat() * 0.02f
        val y = 0.5f + sin(k * 1.7 + s).toFloat() * 0.38f + (r.nextFloat() - 0.5f) * 0.1f
        x to y
    }
}

// ── Breakcore ───────────────────────────────────────────────────────────────────────────────────

private val LOST_WORDS = listOf("no signal", "lost", "sleep", "×××", "forget me", "blank", "404", "why", "static", "rewind")

@Composable
private fun BreakcoreFx(modifier: Modifier) {
    val clock = rememberFxClock(LocalThemeAnimations.current)
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    val measurer = rememberTextMeasurer()
    val wordStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    val words = remember(measurer) { LOST_WORDS.map { measurer.measure(it, wordStyle) } }
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val t = clock.floatValue
        // Scanlines.
        val line = ink.copy(alpha = if (dark) 0.06f else 0.05f)
        val step = 3f * density
        var y = 0f
        while (y < size.height) {
            drawRect(line, topLeft = Offset(0f, y), size = Size(size.width, 1f))
            y += step
        }
        // Grain: short dashes, reshuffled eight times a second.
        val g = Random(floor(t * 8f).toInt())
        repeat(140) {
            drawRect(
                ink.copy(alpha = if (dark) 0.12f else 0.1f),
                topLeft = Offset(g.nextFloat() * size.width, g.nextFloat() * size.height),
                size = Size((1f + g.nextFloat() * 5f) * density, 1f * density),
            )
        }
        // A glitch every couple of seconds: bands of the lilac and of light, slipped sideways.
        val beat = floor(t / 2.3f)
        val inGlitch = (t % 2.3f) < 0.22f
        if (inGlitch) {
            val r = Random(beat.toInt())
            repeat(3) {
                val by = r.nextFloat() * size.height
                val bh = (4f + r.nextFloat() * 26f) * density
                val shift = (r.nextFloat() - 0.5f) * 40f * density
                drawRect(accent.copy(alpha = if (dark) 0.22f else 0.18f), topLeft = Offset(shift, by), size = Size(size.width, bh))
                drawRect(ink.copy(alpha = 0.08f), topLeft = Offset(-shift, by + bh * 0.3f), size = Size(size.width, bh * 0.3f))
            }
        }
        // Crossed-out words floating up slowly.
        words.forEachIndexed { i, layout ->
            val r = Random(i * 13 + 1)
            val speed = 0.01f + r.nextFloat() * 0.015f
            val p = frac(r.nextFloat() + t * speed)
            val x = r.nextFloat() * (size.width - layout.size.width).coerceAtLeast(1f)
            val wy = size.height * (1.05f - p * 1.1f)
            val a = (minOf(p, 1f - p) * 5f).coerceIn(0f, 1f) * (if (dark) 0.22f else 0.2f)
            drawText(layout, color = ink.copy(alpha = a), topLeft = Offset(x, wy))
            val mid = wy + layout.size.height / 2f
            drawLine(accent.copy(alpha = a * 1.4f), Offset(x - 3f * density, mid), Offset(x + layout.size.width + 3f * density, mid), 1.2f * density)
        }
    }
}
