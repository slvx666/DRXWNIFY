/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/*
 * The drawing kit of the experimental themes, all in plain strokes:
 *  - the shaky pen of Breakcore (a hand that trembles: loops, cross-outs, hatching, spirals,
 *    arrows, wobbly lines);
 *  - Satanic's thorns, inverted crosses and ritual circle;
 *  - Street art's spray: splatter, drips, sparkles, a halo.
 * Every random choice comes from a seed, so a drawing stays the same from frame to frame (Breakcore
 * changes the seed a few times a second when its animations are on: the lines "boil").
 */

private fun stroke(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** A path through [points] with a tremble of [shake] px across it, every ~[step] px. */
private fun shakyPath(points: List<Offset>, shake: Float, step: Float, r: Random): Path {
    val path = Path()
    var first = true
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val len = hypot(b.x - a.x, b.y - a.y)
        val n = (len / step).toInt().coerceAtLeast(1)
        val nx = if (len > 0f) -(b.y - a.y) / len else 0f
        val ny = if (len > 0f) (b.x - a.x) / len else 0f
        for (k in 0..n) {
            if (k == 0 && !first) continue
            val t = k.toFloat() / n
            val j = (r.nextFloat() - 0.5f) * 2f * shake
            val p = Offset(a.x + (b.x - a.x) * t + nx * j, a.y + (b.y - a.y) * t + ny * j)
            if (first) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            first = false
        }
    }
    return path
}

private fun DrawScope.pen(points: List<Offset>, color: Color, width: Float, r: Random, shake: Float = 1.4f * density) {
    drawPath(shakyPath(points, shake, 5f * density, r), color, style = stroke(width))
}

/** A wobbly line from [a] to [b]. */
internal fun DrawScope.shakyLine(a: Offset, b: Offset, color: Color, width: Float, r: Random) =
    pen(listOf(a, b), color, width, r)

/** Loops round and round an oval, never closing neatly. */
internal fun DrawScope.scribbleLoops(c: Offset, rx: Float, ry: Float, turns: Float, color: Color, width: Float, r: Random) {
    val pts = ArrayList<Offset>()
    val n = (turns * 18).toInt()
    val start = r.nextFloat() * 6.28f
    for (k in 0..n) {
        val a = start + k / 18f * 2 * PI.toFloat()
        val wob = 0.82f + r.nextFloat() * 0.3f
        pts += Offset(c.x + rx * wob * cos(a), c.y + ry * wob * sin(a))
    }
    pen(pts, color, width, r, shake = 0.8f * density)
}

/** A box scratched out: zig-zags back and forth over it. */
internal fun DrawScope.crossOut(rect: Rect, color: Color, width: Float, r: Random) {
    val pts = ArrayList<Offset>()
    val rows = 4 + r.nextInt(4)
    for (k in 0..rows) {
        val y = rect.top + rect.height * k / rows + (r.nextFloat() - 0.5f) * rect.height * 0.15f
        pts += Offset(if (k % 2 == 0) rect.left else rect.right, y)
    }
    pen(pts, color, width, r)
    // And an X over it.
    shakyLine(rect.topLeft, rect.bottomRight, color, width, r)
    shakyLine(Offset(rect.right, rect.top), Offset(rect.left, rect.bottom), color, width, r)
}

/** Slanted strokes filling a patch. */
internal fun DrawScope.hatch(rect: Rect, color: Color, width: Float, r: Random) {
    val gap = 4f * density + r.nextFloat() * 3f * density
    var x = rect.left - rect.height
    while (x < rect.right) {
        val a = Offset(x.coerceAtLeast(rect.left), rect.bottom - (x.coerceAtLeast(rect.left) - x))
        val b = Offset((x + rect.height).coerceAtMost(rect.right), rect.top + ((x + rect.height) - (x + rect.height).coerceAtMost(rect.right)))
        shakyLine(a, b, color, width, r)
        x += gap
    }
}

/** A spiral drawn outward in one go. */
internal fun DrawScope.spiral(c: Offset, radius: Float, color: Color, width: Float, r: Random) {
    val pts = ArrayList<Offset>()
    val turns = 2.5f + r.nextFloat() * 2f
    val n = (turns * 20).toInt()
    for (k in 0..n) {
        val t = k.toFloat() / n
        val a = t * turns * 2 * PI.toFloat()
        pts += Offset(c.x + radius * t * cos(a), c.y + radius * t * sin(a))
    }
    pen(pts, color, width, r, shake = 0.7f * density)
}

/** A crooked arrow from [a] to [b]. */
internal fun DrawScope.arrow(a: Offset, b: Offset, color: Color, width: Float, r: Random) {
    val mid = Offset((a.x + b.x) / 2 + (r.nextFloat() - 0.5f) * 30f * density, (a.y + b.y) / 2 + (r.nextFloat() - 0.5f) * 30f * density)
    pen(listOf(a, mid, b), color, width, r)
    val ang = kotlin.math.atan2(b.y - mid.y, b.x - mid.x)
    val head = 9f * density
    listOf(ang + 2.6f, ang - 2.6f).forEach { h ->
        shakyLine(b, Offset(b.x + head * cos(h), b.y + head * sin(h)), color, width, r)
    }
}

/** Little crosses, like stitches or counting marks. */
internal fun DrawScope.stitches(start: Offset, count: Int, step: Float, color: Color, width: Float, r: Random) {
    val s = 3.5f * density
    for (k in 0 until count) {
        val c = Offset(start.x + k * step + (r.nextFloat() - 0.5f) * 3f * density, start.y + (r.nextFloat() - 0.5f) * 4f * density)
        shakyLine(Offset(c.x - s, c.y - s), Offset(c.x + s, c.y + s), color, width, r)
        shakyLine(Offset(c.x - s, c.y + s), Offset(c.x + s, c.y - s), color, width, r)
    }
}

/** A few of the doodles above, crowded into [rect]: what a restless hand leaves in a margin. */
internal fun DrawScope.scribbleCluster(rect: Rect, color: Color, width: Float, r: Random) {
    val pieces = 2 + r.nextInt(3)
    repeat(pieces) {
        val w = rect.width * (0.35f + r.nextFloat() * 0.55f)
        val h = rect.height * (0.3f + r.nextFloat() * 0.6f)
        val x = rect.left + r.nextFloat() * (rect.width - w).coerceAtLeast(0f)
        val y = rect.top + r.nextFloat() * (rect.height - h).coerceAtLeast(0f)
        val box = Rect(x, y, x + w, y + h)
        when (r.nextInt(6)) {
            0 -> scribbleLoops(box.center, w / 2, h / 2, 2f + r.nextFloat() * 3f, color, width, r)
            1 -> crossOut(box, color, width, r)
            2 -> hatch(box, color, width * 0.8f, r)
            3 -> spiral(box.center, minOf(w, h) / 2, color, width, r)
            4 -> arrow(Offset(box.left, box.bottom), Offset(box.right, box.top), color, width, r)
            else -> stitches(Offset(box.left, box.center.y), (w / (12f * density)).toInt().coerceIn(2, 8), 12f * density, color, width, r)
        }
    }
}

// ── Satanic ─────────────────────────────────────────────────────────────────────────────────────

/** A rule with thorns along it, pointing [down] or up — the spikes of a black metal logo. */
internal fun DrawScope.thornLine(y: Float, color: Color, down: Boolean, seed: Int) {
    val r = Random(seed)
    val w = 1f * density
    drawLine(color, Offset(0f, y), Offset(size.width, y), w)
    var x = 6f * density
    while (x < size.width) {
        val len = (3f + r.nextFloat() * 7f) * density
        val half = (1.2f + r.nextFloat() * 1.6f) * density
        val tip = Offset(x + (r.nextFloat() - 0.5f) * 3f * density, if (down) y + len else y - len)
        val path = Path().apply {
            moveTo(x - half, y)
            lineTo(tip.x, tip.y)
            lineTo(x + half, y)
            close()
        }
        drawPath(path, color)
        x += (10f + r.nextFloat() * 16f) * density
    }
}

/** An inverted cross: the bar low on the upright. */
internal fun DrawScope.invertedCross(c: Offset, h: Float, color: Color, width: Float) {
    drawLine(color, Offset(c.x, c.y - h / 2), Offset(c.x, c.y + h / 2), width)
    drawLine(color, Offset(c.x - h * 0.28f, c.y + h * 0.18f), Offset(c.x + h * 0.28f, c.y + h * 0.18f), width)
}

/** The five points of a star, point down, upright (no turn). */
private fun starPoints(center: Offset, r: Float) = (0 until 5).map { k ->
    val a = PI / 2 + k * 2 * PI / 5
    Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
}

/** A ritual circle: the inverted pentagram, two rings with ticks between, crosses at its points. */
internal fun DrawScope.ritualCircle(center: Offset, r: Float, color: Color, width: Float) {
    val pts = starPoints(center, r)
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (k in 1..5) pts[(k * 2) % 5].let { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, color, style = Stroke(width, join = StrokeJoin.Miter))
    drawCircle(color, r * 1.04f, center, style = Stroke(width))
    drawCircle(color, r * 1.2f, center, style = Stroke(width * 0.7f))
    // Ticks between the rings, like a dial of runes.
    for (k in 0 until 60) {
        val a = k * 2 * PI / 60
        val inner = if (k % 5 == 0) r * 1.06f else r * 1.12f
        drawLine(
            color,
            Offset(center.x + inner * cos(a).toFloat(), center.y + inner * sin(a).toFloat()),
            Offset(center.x + r * 1.18f * cos(a).toFloat(), center.y + r * 1.18f * sin(a).toFloat()),
            width * 0.6f,
        )
    }
    // An inverted cross beyond each point.
    pts.forEach { p ->
        val dir = Offset(p.x - center.x, p.y - center.y) / r
        invertedCross(Offset(center.x + dir.x * r * 1.36f, center.y + dir.y * r * 1.36f), r * 0.16f, color, width)
    }
}

// ── Street art ──────────────────────────────────────────────────────────────────────────────────

/** A burst of spray paint: a soft blot and droplets flung around it. */
internal fun DrawScope.splatter(c: Offset, radius: Float, color: Color, r: Random) {
    drawCircle(color.copy(alpha = color.alpha * 0.35f), radius * 0.9f, c)
    drawCircle(color.copy(alpha = color.alpha * 0.6f), radius * 0.55f, c)
    repeat(26) {
        val a = r.nextFloat() * 2 * PI.toFloat()
        val d = radius * (0.7f + r.nextFloat() * r.nextFloat() * 1.6f)
        drawCircle(color.copy(alpha = color.alpha * (0.4f + r.nextFloat() * 0.6f)), (0.6f + r.nextFloat() * 2.4f) * density, Offset(c.x + d * cos(a), c.y + d * sin(a)))
    }
}

/** A drip of paint hanging from [top], [length] long, with its drop. */
internal fun DrawScope.drip(top: Offset, length: Float, width: Float, color: Color) {
    drawLine(color, top, Offset(top.x, top.y + length), width, cap = StrokeCap.Round)
    drawCircle(color, width * 0.85f, Offset(top.x, top.y + length + width * 0.3f))
}

/** A four-pointed sparkle (✦). */
internal fun DrawScope.sparkle(c: Offset, s: Float, color: Color) {
    val w = 1.4f * density
    drawLine(color, Offset(c.x, c.y - s), Offset(c.x, c.y + s), w, cap = StrokeCap.Round)
    drawLine(color, Offset(c.x - s * 0.6f, c.y), Offset(c.x + s * 0.6f, c.y), w, cap = StrokeCap.Round)
}

/** A sprayed halo: a tilted ring of thick paint with a couple of drips. */
internal fun DrawScope.halo(c: Offset, rx: Float, color: Color, r: Random) {
    val ry = rx * 0.32f
    val w = 3.5f * density
    drawOval(color, topLeft = Offset(c.x - rx, c.y - ry), size = androidx.compose.ui.geometry.Size(rx * 2, ry * 2), style = Stroke(w))
    repeat(2) {
        val x = c.x + (r.nextFloat() - 0.5f) * rx * 1.2f
        drip(Offset(x, c.y + ry * 0.9f), (4f + r.nextFloat() * 10f) * density, w * 0.6f, color)
    }
}
