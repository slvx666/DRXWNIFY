/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/*
 * The ornaments of the near-colourless themes — all quiet, all drawn once (nothing moves):
 *  - Monochrome: a hairline under the top bar; in the light version, faint paper grain and a few
 *    scratches over the pages;
 *  - Satanic: a thin inverted pentagram in the top bar's corner, an inverted cross after titles;
 *  - Gothic: a row of small pointed arches along the top bar's edge, a quatrefoil after titles;
 *  - Street art: marker drips from the top bar, a scribbled underline after titles;
 *  - Breakcore: scanlines and a couple of displaced slices in the top bar, a crossed-out mark,
 *    titles with a faint displaced ghost, scanlines over the pages.
 */

private fun Color.dark() = luminance() < 0.4f

/** The top bar's ornament for the themes of this file; false when [preset] isn't one of them. */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.MonoTopBar(preset: AppThemePreset, container: Color): Boolean {
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val dark = container.dark()
    when (preset) {
        AppThemePreset.MONOCHROME -> Canvas(Modifier.matchParentSize()) { hairline(ink.copy(alpha = 0.35f)) }
        AppThemePreset.SATANIC -> Canvas(Modifier.matchParentSize()) {
            // A row of small inverted crosses along the bottom, a double rule, the pentagram in the corner.
            val cross = ink.copy(alpha = if (dark) 0.22f else 0.28f)
            val step = 26.dp.toPx()
            var x = step / 2
            val w = 1.2.dp.toPx()
            val h = 9.dp.toPx()
            val base = size.height - 5.dp.toPx()
            while (x < size.width) {
                drawLine(cross, Offset(x, base - h), Offset(x, base), w)
                drawLine(cross, Offset(x - h * 0.3f, base - h * 0.3f), Offset(x + h * 0.3f, base - h * 0.3f), w)
                x += step
            }
            hairline(ink.copy(alpha = 0.4f))
            drawRect(ink.copy(alpha = 0.25f), topLeft = Offset(0f, size.height - 3.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
            val r = size.height * 0.36f
            invertedPentagram(Offset(size.width - r - 12.dp.toPx(), size.height * 0.46f), r, ink.copy(alpha = if (dark) 0.34f else 0.4f))
        }
        AppThemePreset.GOTHIC -> Canvas(Modifier.matchParentSize()) {
            arches(ink.copy(alpha = if (dark) 0.3f else 0.34f))
            roseWindow(Offset(size.width - size.height * 0.42f - 10.dp.toPx(), size.height * 0.44f), size.height * 0.36f, ink.copy(alpha = if (dark) 0.3f else 0.36f))
        }
        AppThemePreset.STREET -> Canvas(Modifier.matchParentSize()) {
            drips(accent.copy(alpha = if (dark) 0.6f else 0.65f))
            sprayDust(accent.copy(alpha = if (dark) 0.35f else 0.4f))
        }
        AppThemePreset.BREAKCORE -> Canvas(Modifier.matchParentSize()) { glitchBar(ink, accent, dark) }
        else -> return false
    }
    return true
}

private fun DrawScope.hairline(color: Color) {
    val h = 1.dp.toPx()
    drawRect(color, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
}

/** A five-pointed star, point down, as one thin unbroken line (plus its circle). */
private fun DrawScope.invertedPentagram(center: Offset, r: Float, color: Color) {
    val points = (0 until 5).map { k ->
        // Point down: the first vertex at 90° (straight down in screen coordinates).
        val a = PI / 2 + k * 2 * PI / 5
        Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
    }
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (k in 1..5) {
            val p = points[(k * 2) % 5]
            lineTo(p.x, p.y)
        }
        close()
    }
    val stroke = Stroke(1.dp.toPx())
    drawPath(path, color, style = stroke)
    drawCircle(color, r * 1.12f, center, style = stroke)
}

/** Small pointed arches side by side along the bottom edge, like a cloister's arcade. */
private fun DrawScope.arches(color: Color) {
    val w = 18.dp.toPx()
    val h = 9.dp.toPx()
    val base = size.height - 1.dp.toPx()
    val stroke = Stroke(1.dp.toPx())
    var x = 0f
    while (x < size.width) {
        val path = Path().apply {
            moveTo(x, base)
            // Two arcs meeting in a point: the lancet.
            quadraticTo(x, base - h * 0.85f, x + w / 2, base - h)
            quadraticTo(x + w, base - h * 0.85f, x + w, base)
        }
        drawPath(path, color, style = stroke)
        x += w
    }
    drawLine(color, Offset(0f, base), Offset(size.width, base), 1.dp.toPx())
}

/** A rose window: a circle of eight petals around a small circle, in thin tracery. */
private fun DrawScope.roseWindow(c: Offset, r: Float, color: Color) {
    val stroke = Stroke(1.dp.toPx())
    drawCircle(color, r, c, style = stroke)
    drawCircle(color, r * 0.28f, c, style = stroke)
    for (k in 0 until 8) {
        val a = k * PI / 4
        val p = Offset(c.x + (r * 0.62f * cos(a)).toFloat(), c.y + (r * 0.62f * sin(a)).toFloat())
        drawCircle(color, r * 0.3f, p, style = stroke)
        drawLine(color, Offset(c.x + (r * 0.28f * cos(a)).toFloat(), c.y + (r * 0.28f * sin(a)).toFloat()),
            Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat()), 0.8.dp.toPx())
    }
}

/** The fine dust a spray can leaves around its line. */
private fun DrawScope.sprayDust(color: Color) {
    val r = Random(21)
    repeat(160) {
        val x = r.nextFloat() * size.width
        val y = (r.nextFloat() * r.nextFloat()) * size.height * 0.5f
        drawCircle(color.copy(alpha = color.alpha * (0.3f + r.nextFloat() * 0.7f)), (0.5f + r.nextFloat()).dp.toPx(), Offset(x, y))
    }
}

/** Marker drips running down from the top edge, a few, uneven. */
private fun DrawScope.drips(color: Color) {
    val r = Random(7)
    val count = (size.width / 70.dp.toPx()).toInt().coerceAtLeast(3)
    val stroke = 2.5.dp.toPx()
    drawRect(color.copy(alpha = color.alpha * 0.6f), size = Size(size.width, 2.dp.toPx()))
    repeat(count) {
        val x = size.width * (0.08f + 0.84f * r.nextFloat())
        val len = size.height * (0.2f + 0.45f * r.nextFloat())
        drawLine(color, Offset(x, 0f), Offset(x, len), stroke, cap = StrokeCap.Round)
        drawCircle(color, stroke * 0.85f, Offset(x, len + stroke * 0.4f))
    }
}

/** Scanlines, a couple of displaced slices and a crossed-out mark — a worn tape cover. */
private fun DrawScope.glitchBar(ink: Color, accent: Color, dark: Boolean) {
    val line = ink.copy(alpha = if (dark) 0.1f else 0.08f)
    val step = 3.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawRect(line, topLeft = Offset(0f, y), size = Size(size.width, 1f))
        y += step
    }
    val slice = accent.copy(alpha = if (dark) 0.4f else 0.3f)
    drawRect(slice, topLeft = Offset(size.width * 0.55f, size.height * 0.28f), size = Size(size.width * 0.3f, 2.dp.toPx()))
    drawRect(slice, topLeft = Offset(size.width * 0.62f, size.height * 0.66f), size = Size(size.width * 0.18f, 3.dp.toPx()))
    // The crossed-out mark in the corner.
    val c = Offset(size.width - 26.dp.toPx(), size.height * 0.5f)
    val s = 7.dp.toPx()
    val cross = ink.copy(alpha = if (dark) 0.45f else 0.4f)
    drawLine(cross, Offset(c.x - s, c.y - s), Offset(c.x + s, c.y + s), 1.dp.toPx())
    drawLine(cross, Offset(c.x - s, c.y + s), Offset(c.x + s, c.y - s), 1.dp.toPx())
    drawLine(ink.copy(alpha = if (dark) 0.2f else 0.25f), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
}

/** The mark after a screen title, for the themes of this file; false when there is none. */
@Composable
internal fun MonoTitleAccent(preset: AppThemePreset): Boolean {
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    when (preset) {
        AppThemePreset.SATANIC -> Canvas(Modifier.padding(start = 10.dp).offset(y = (-2).dp).size(width = 12.dp, height = 18.dp)) {
            // The inverted cross: the bar low on the upright.
            val color = ink.copy(alpha = 0.7f)
            val w = 1.6.dp.toPx()
            drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), w)
            drawLine(color, Offset(0f, size.height * 0.68f), Offset(size.width, size.height * 0.68f), w)
        }
        AppThemePreset.GOTHIC -> Canvas(Modifier.padding(start = 10.dp).offset(y = (-2).dp).size(14.dp)) {
            // A quatrefoil: four small circles around a point.
            val color = ink.copy(alpha = 0.55f)
            val r = size.minDimension * 0.22f
            val c = center
            val stroke = Stroke(1.2.dp.toPx())
            listOf(Offset(0f, -r), Offset(r, 0f), Offset(0f, r), Offset(-r, 0f)).forEach {
                drawCircle(color, r, c + it, style = stroke)
            }
        }
        AppThemePreset.STREET -> Canvas(Modifier.padding(start = 8.dp).offset(y = 4.dp).size(width = 30.dp, height = 10.dp)) {
            // A quick marker scribble.
            val path = Path().apply {
                moveTo(0f, size.height * 0.7f)
                var x = 0f
                var up = true
                while (x < size.width) {
                    x += size.width / 6
                    lineTo(x, if (up) size.height * 0.15f else size.height * 0.85f)
                    up = !up
                }
            }
            drawPath(path, accent.copy(alpha = 0.85f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        else -> return false
    }
    return true
}

/**
 * Breakcore's title: the words with a faint ghost of them slipped to the side, like a tape that
 * didn't track. Null for the other themes.
 */
@Composable
internal fun BreakcoreTitle(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier) {
        Text(
            text = text,
            style = style,
            color = accent.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset(x = 3.dp, y = (-2).dp),
        )
        Text(text = text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Over the pages (touches pass through): Monochrome's paper in the light version (grain and a few
 * scratches, barely there), Breakcore's scanlines. Drawn once into a layer.
 */
@Composable
internal fun MonoOverlay(preset: AppThemePreset, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    when {
        preset == AppThemePreset.MONOCHROME && !surface.dark() -> {
            val grain = remember { List(420) { Random(it * 31 + 5).let { r -> Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) } } }
            val scratches = remember { List(16) { Random(it * 97 + 3).let { r -> listOf(r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextFloat()) } } }
            Canvas(modifier.fillMaxSize().graphicsLayer()) {
                grain.forEach { (x, y, s) ->
                    drawCircle(ink.copy(alpha = 0.035f), (0.6f + s) * 0.8f * density, Offset(x * size.width, y * size.height))
                }
                scratches.forEach { (x, y, angle, len) ->
                    val start = Offset(x * size.width, y * size.height)
                    val a = angle * PI * 2
                    val l = (40 + len * 140) * density
                    drawLine(
                        ink.copy(alpha = 0.03f),
                        start,
                        Offset(start.x + (l * cos(a)).toFloat(), start.y + (l * sin(a)).toFloat()),
                        strokeWidth = 0.7f * density,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        preset == AppThemePreset.BREAKCORE -> {
            val line = ink.copy(alpha = if (surface.dark()) 0.022f else 0.03f)
            Canvas(modifier.fillMaxSize().graphicsLayer()) {
                val step = 3.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    drawRect(line, topLeft = Offset(0f, y), size = Size(size.width, 1f))
                    y += step
                }
            }
        }
    }
}
