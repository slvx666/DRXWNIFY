/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/*
 * The background art of the experimental themes. It is drawn UNDER the pages, so it shows only
 * where a page is see-through (between rows, around covers) and never over a cover, a photo or
 * text. What moves moves only while the theme's animations are on; switched off, it is not drawn
 * at all (not left frozen):
 *  - Satanic: an upright ritual circle with the inverted pentagram, heavy shadow at the edges; ash
 *    rising (animations on);
 *  - Gothic: lancet windows down the sides, shadowed corners; dust settling (animations on);
 *  - Street art: concrete grain, spray splatter in the corners, drips from the top, a halo and
 *    sparkles, a big faint tag — nothing moves;
 *  - Breakcore: scanlines, grain, and a trembling pen's doodles in the margins (they boil a little
 *    and the grain flickers while animations are on).
 */

@Composable
private fun rememberFxClock(running: Boolean, frameMs: Long = 33): MutableFloatState {
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running, frameMs) {
        if (!running) return@LaunchedEffect
        val start = withFrameMillis { it } - (seconds.floatValue * 1000).toLong()
        while (true) {
            kotlinx.coroutines.delay(frameMs)
            withFrameMillis { now -> seconds.floatValue = (now - start) / 1000f }
        }
    }
    return seconds
}

/** The background for [preset]; nothing for the themes without one. */
@Composable
internal fun ExperimentalBackground(preset: AppThemePreset, modifier: Modifier = Modifier) {
    when (preset) {
        AppThemePreset.SATANIC -> SatanicBackground(modifier)
        AppThemePreset.GOTHIC -> GothicBackground(modifier)
        AppThemePreset.STREET -> StreetBackground(modifier)
        AppThemePreset.BREAKCORE -> BreakcoreBackground(modifier)
        else -> Unit
    }
}

private fun frac(x: Float) = x - floor(x)

// ── Satanic ─────────────────────────────────────────────────────────────────────────────────────

private class Ash(val x: Float, val speed: Float, val size: Float, val phase: Float, val sway: Float, val alpha: Float)

@Composable
private fun SatanicBackground(modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    val animate = LocalThemeAnimations.current
    Box(modifier.fillMaxSize()) {
        // Still: drawn once into its layer.
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            // Darkness gathering at the top and the bottom.
            val shade = if (dark) Color.Black.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.12f)
            drawRect(Brush.verticalGradient(0f to shade, 0.22f to Color.Transparent, 0.78f to Color.Transparent, 1f to shade))
            // The circle upright, a little below the middle.
            val r = size.minDimension * 0.33f
            ritualCircle(Offset(size.width * 0.5f, size.height * 0.56f), r, ink.copy(alpha = if (dark) 0.075f else 0.09f), 1.4f * density)
        }
        // Ash rising only while the animations are on; off, there is none at all.
        if (animate) SatanicAsh(ink)
    }
}

@Composable
private fun SatanicAsh(ink: Color) {
    val ash = remember {
        val r = Random(666)
        // Slow: it drifts up rather than flies.
        List(64) { Ash(r.nextFloat(), 0.018f + r.nextFloat() * 0.032f, 0.8f + r.nextFloat() * 2f, r.nextFloat(), 6f + r.nextFloat() * 16f, 0.25f + r.nextFloat() * 0.45f) }
    }
    val clock = rememberFxClock(true)
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        val t = clock.floatValue
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

// ── Gothic ──────────────────────────────────────────────────────────────────────────────────────

private class Mote(val x: Float, val speed: Float, val size: Float, val phase: Float, val alpha: Float)

@Composable
private fun GothicBackground(modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    val animate = LocalThemeAnimations.current
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            drawRect(
                Brush.radialGradient(
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = if (dark) 0.42f else 0.15f),
                    center = Offset(size.width / 2, size.height / 2),
                    radius = size.maxDimension * 0.75f,
                ),
            )
        }
        if (animate) GothicDust(ink)
    }
}

private fun DrawScope.lancet(rect: Rect, color: Color, width: Float) {
    val s = Stroke(width)
    val tip = rect.top
    val shoulder = rect.top + rect.width * 0.9f
    val outline = Path().apply {
        moveTo(rect.left, rect.bottom)
        lineTo(rect.left, shoulder)
        quadraticTo(rect.left, tip + rect.width * 0.15f, rect.center.x, tip)
        quadraticTo(rect.right, tip + rect.width * 0.15f, rect.right, shoulder)
        lineTo(rect.right, rect.bottom)
    }
    drawPath(outline, color, style = s)
    // The mullion and two lights, a small circle above them.
    drawLine(color, Offset(rect.center.x, shoulder + rect.width * 0.2f), Offset(rect.center.x, rect.bottom), width)
    drawCircle(color, rect.width * 0.16f, Offset(rect.center.x, shoulder - rect.width * 0.05f), style = s)
    var y = shoulder + rect.width * 0.6f
    while (y < rect.bottom) {
        drawLine(color, Offset(rect.left, y), Offset(rect.right, y), width * 0.6f)
        y += rect.width * 0.7f
    }
}

@Composable
private fun GothicDust(ink: Color) {
    val motes = remember {
        val r = Random(1347)
        List(30) { Mote(r.nextFloat(), 0.012f + r.nextFloat() * 0.025f, 0.8f + r.nextFloat() * 1.6f, r.nextFloat(), 0.15f + r.nextFloat() * 0.25f) }
    }
    val clock = rememberFxClock(true)
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        val t = clock.floatValue
        motes.forEach { m ->
            val p = frac(m.phase + t * m.speed)
            val y = size.height * (p * 1.1f - 0.05f)
            val x = m.x * size.width + sin((t * 0.3f + m.phase * 9f).toDouble()).toFloat() * 18f * density
            drawCircle(ink.copy(alpha = m.alpha * (minOf(p, 1f - p) * 5f).coerceIn(0f, 1f)), m.size * density, Offset(x, y))
        }
    }
}

// ── Street art ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun StreetBackground(modifier: Modifier) {
    val paint = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val r = Random(404)
        // Concrete: specks and a few scuffs.
        repeat(500) {
            drawCircle(ink.copy(alpha = if (dark) 0.05f else 0.06f), (0.4f + r.nextFloat()) * density, Offset(r.nextFloat() * size.width, r.nextFloat() * size.height))
        }
        repeat(18) {
            val a = Offset(r.nextFloat() * size.width, r.nextFloat() * size.height)
            drawLine(ink.copy(alpha = 0.035f), a, Offset(a.x + (r.nextFloat() - 0.5f) * 120f * density, a.y + (r.nextFloat() - 0.5f) * 30f * density), 0.8f * density)
        }
        val color = paint.copy(alpha = if (dark) 0.5f else 0.55f)
        // Splatter in the corners, where nothing else is.
        splatter(Offset(size.width * 0.04f, size.height * 0.08f), 26f * density, color, r)
        splatter(Offset(size.width * 0.97f, size.height * 0.42f), 20f * density, color, r)
        splatter(Offset(size.width * 0.1f, size.height * 0.94f), 30f * density, color, r)
        // Drips hanging from the top edge.
        repeat(5) {
            drip(Offset(size.width * (0.05f + r.nextFloat() * 0.9f), 0f), (20f + r.nextFloat() * 70f) * density, (2f + r.nextFloat() * 2f) * density, color)
        }
        halo(Offset(size.width * 0.8f, size.height * 0.13f), 34f * density, color, r)
        sparkle(Offset(size.width * 0.9f, size.height * 0.07f), 9f * density, color)
        sparkle(Offset(size.width * 0.12f, size.height * 0.5f), 7f * density, color)
        sparkle(Offset(size.width * 0.88f, size.height * 0.86f), 8f * density, color)
        // Fine spray flecks scattered over the wall, a few small bursts in the gaps.
        repeat(140) {
            drawCircle(color.copy(alpha = color.alpha * (0.25f + r.nextFloat() * 0.5f)), (0.6f + r.nextFloat() * 1.6f) * density, Offset(r.nextFloat() * size.width, r.nextFloat() * size.height))
        }
        listOf(0.55f to 0.25f, 0.3f to 0.62f, 0.75f to 0.7f).forEach { (fx, fy) ->
            splatter(Offset(size.width * fx, size.height * fy), 9f * density, color.copy(alpha = color.alpha * 0.7f), r)
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

@Composable
private fun BreakcoreBackground(modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    val animate = LocalThemeAnimations.current
    // Slow on purpose: the doodles boil four times a second, the grain flickers with them.
    val clock = rememberFxClock(animate, frameMs = 250)
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val beat = if (animate) floor(clock.floatValue * 4f).toInt() % 3 else 0
        // Scanlines.
        val line = ink.copy(alpha = if (dark) 0.045f else 0.04f)
        val step = 3f * density
        var y = 0f
        while (y < size.height) {
            drawRect(line, topLeft = Offset(0f, y), size = Size(size.width, 1f))
            y += step
        }
        // Grain.
        val g = Random(77 + beat)
        repeat(160) {
            drawRect(
                ink.copy(alpha = if (dark) 0.1f else 0.09f),
                topLeft = Offset(g.nextFloat() * size.width, g.nextFloat() * size.height),
                size = Size((1f + g.nextFloat() * 5f) * density, 1f * density),
            )
        }
        // The pen: doodle clusters along the margins and in the corners; the same shapes each
        // time, only the tremble changes with the beat.
        val pen = ink.copy(alpha = if (dark) 0.32f else 0.42f)
        val width = 1.3f * density
        val places = listOf(
            Rect(0f, size.height * 0.02f, size.width * 0.3f, size.height * 0.14f),
            Rect(size.width * 0.72f, size.height * 0.18f, size.width, size.height * 0.3f),
            Rect(0f, size.height * 0.44f, size.width * 0.16f, size.height * 0.6f),
            Rect(size.width * 0.84f, size.height * 0.55f, size.width, size.height * 0.72f),
            Rect(size.width * 0.6f, size.height * 0.86f, size.width * 0.98f, size.height * 0.98f),
        )
        places.forEachIndexed { i, rect ->
            // The same doodle every time; with the beat it shifts by a hair, as if redrawn.
            val boil = Random(i * 13 + beat * 7)
            translate((boil.nextFloat() - 0.5f) * 2f * density, (boil.nextFloat() - 0.5f) * 2f * density) {
                scribbleCluster(rect, pen, width, Random(i * 31 + 5))
            }
        }
        // A long wobbly line wandering down one side.
        shakyLine(Offset(size.width * 0.97f, size.height * 0.05f), Offset(size.width * 0.93f, size.height * 0.5f), pen, width, Random(900))
    }
}
