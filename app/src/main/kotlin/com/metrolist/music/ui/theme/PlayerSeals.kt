/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/*
 * The full-screen player's cover dressed by the experimental themes, all drawn in strokes (no
 * pictures), scaled with the cover's box, still, and never over the cover or the progress ring:
 *  - with the ring slider the round cover becomes the middle of a seal — Satanic: an occult seal
 *    (gapped rings, runes, thorn branches, a spire above, a short finial below, crescents at the
 *    sides); Gothic: a cathedral seal (pointed arches, pinnacles, crosses); Street art: sprayed
 *    rings, a crown, drips, sparkles; Glitch: a trembling pen going round it;
 *  - with the usual bar (a square cover): Satanic a crown of thorns round the cover (it may cross
 *    its edge a little); Street art drips under it; Glitch a pen line; Gothic nothing.
 * Everything below the cover stays short: the track's title is right under it.
 */

@Composable
fun ThemePlayerDecor(ring: Boolean, color: Color, modifier: Modifier = Modifier) {
    val preset = LocalAppThemePreset.current
    if (preset.group != AppThemePreset.Group.EXPERIMENTAL) return
    if (!ring && preset == AppThemePreset.GOTHIC) return
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2
        if (ring) {
            when (preset) {
                AppThemePreset.SATANIC -> occultSeal(c, r, color)
                AppThemePreset.GOTHIC -> cathedralSeal(c, r, color)
                AppThemePreset.STREET -> sprayedSeal(c, r, color)
                AppThemePreset.BREAKCORE -> penSeal(c, r, color)
                else -> Unit
            }
        } else {
            when (preset) {
                AppThemePreset.SATANIC -> thornWreath(color)
                AppThemePreset.STREET -> {
                    val rnd = Random(4)
                    // Longer drips, but short of the title under the cover.
                    listOf(0.12f, 0.27f, 0.48f, 0.66f, 0.85f).forEach { f ->
                        drip(Offset(size.width * f, size.height - 3.dp.toPx()), (8f + rnd.nextFloat() * 12f).dp.toPx(), (2.5f + rnd.nextFloat()).dp.toPx(), color.copy(alpha = 0.75f))
                    }
                }
                AppThemePreset.BREAKCORE -> {
                    val ink = color.copy(alpha = 0.55f)
                    shakyLine(Offset(0f, size.height + 8.dp.toPx()), Offset(size.width * 0.7f, size.height + 6.dp.toPx()), ink, 1.3.dp.toPx(), Random(6))
                    stitches(Offset(-2.dp.toPx(), -8.dp.toPx()), 3, 9.dp.toPx(), ink, 1.3.dp.toPx(), Random(7))
                }
                else -> Unit
            }
        }
    }
}

private fun stroke(w: Float) = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun polar(c: Offset, d: Float, a: Double) = Offset(c.x + (d * cos(a)).toFloat(), c.y + (d * sin(a)).toFloat())

/** A ring in [parts] arcs with small gaps, each arc a hair off true, like inked by hand. */
private fun DrawScope.gappedRing(c: Offset, d: Float, parts: Int, gapDeg: Float, color: Color, w: Float, seed: Int) {
    val r = Random(seed)
    val step = 360f / parts
    for (k in 0 until parts) {
        val wobble = (r.nextFloat() - 0.5f) * 1.2f * density
        val rr = d + wobble
        drawArc(
            color, startAngle = k * step + gapDeg / 2 + r.nextFloat() * 2f, sweepAngle = step - gapDeg - r.nextFloat() * 3f, useCenter = false,
            topLeft = Offset(c.x - rr, c.y - rr), size = Size(rr * 2, rr * 2), style = stroke(w),
        )
    }
}

/** A line with a slight unevenness, as a pen leaves it. */
private fun DrawScope.inked(a: Offset, b: Offset, color: Color, w: Float, r: Random) {
    val mid = Offset((a.x + b.x) / 2 + (r.nextFloat() - 0.5f) * 0.8f * density, (a.y + b.y) / 2 + (r.nextFloat() - 0.5f) * 0.8f * density)
    drawPath(Path().apply { moveTo(a.x, a.y); quadraticTo(mid.x, mid.y, b.x, b.y) }, color, style = stroke(w))
}

// Runes: strokes in a box from (-1,-1) to (1,1), "up" pointing away from the centre.
private val RUNES: List<List<FloatArray>> = listOf(
    listOf(floatArrayOf(0f, -1f, 0f, 1f), floatArrayOf(0f, -1f, 0.6f, -0.4f)),
    listOf(floatArrayOf(0f, -1f, 0f, 1f), floatArrayOf(0f, 0f, 0.6f, -0.6f), floatArrayOf(0f, 0f, 0.6f, 0.6f)),
    listOf(floatArrayOf(-0.5f, -1f, 0.5f, 1f), floatArrayOf(0.5f, -1f, -0.5f, 1f)),
    listOf(floatArrayOf(0f, -1f, 0f, 1f), floatArrayOf(-0.5f, 0.3f, 0.5f, 0.3f)),
    listOf(floatArrayOf(-0.5f, 1f, 0f, -1f), floatArrayOf(0f, -1f, 0.5f, 1f)),
    listOf(floatArrayOf(0f, -1f, 0f, 1f), floatArrayOf(0f, -1f, 0.55f, -0.5f), floatArrayOf(0.55f, -0.5f, 0f, 0f)),
    listOf(floatArrayOf(0f, -1f, 0.5f, 0f), floatArrayOf(0.5f, 0f, 0f, 1f), floatArrayOf(0f, 1f, -0.5f, 0f), floatArrayOf(-0.5f, 0f, 0f, -1f)),
    listOf(floatArrayOf(0f, -1f, 0f, 1f), floatArrayOf(0f, 0.3f, -0.5f, 1f), floatArrayOf(0f, 0.3f, 0.5f, 1f)),
    listOf(floatArrayOf(-0.45f, -1f, -0.45f, 1f), floatArrayOf(0.45f, -1f, 0.45f, 1f), floatArrayOf(-0.45f, -0.2f, 0.45f, 0.3f)),
)

private fun DrawScope.rune(at: Offset, s: Float, angleDeg: Float, which: Int, color: Color, w: Float) {
    rotate(angleDeg, at) {
        RUNES[which % RUNES.size].forEach { l ->
            drawLine(color, Offset(at.x + l[0] * s * 0.6f, at.y + l[1] * s), Offset(at.x + l[2] * s * 0.6f, at.y + l[3] * s), w, cap = StrokeCap.Round)
        }
    }
}

/** A thorn branch along an arc from [fromDeg] over [sweepDeg] at distance [d]: a wavy stem, thorns both ways. */
private fun DrawScope.thornBranch(c: Offset, d: Float, fromDeg: Float, sweepDeg: Float, color: Color, w: Float, seed: Int) {
    val r = Random(seed)
    val n = 40
    val pts = (0..n).map { k ->
        val t = k.toFloat() / n
        val a = Math.toRadians((fromDeg + sweepDeg * t).toDouble())
        val wave = sin(t * PI * 5).toFloat() * 2.2f * density * (1f - t * 0.4f)
        polar(c, d + wave, a)
    }
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    }
    // The stem thins out towards its end.
    drawPath(path, color, style = stroke(w))
    for (i in 2 until pts.size - 1 step 3) {
        val a = pts[i]
        val b = pts[i + 1]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(0.01f)
        val side = if ((i / 3) % 2 == 0) 1f else -1f
        val nx = -dy / len * side
        val ny = dx / len * side
        val thorn = (3f + r.nextFloat() * 3f) * density * (1f - i.toFloat() / pts.size * 0.5f)
        val base = Offset(a.x + dx * 0.5f, a.y + dy * 0.5f)
        val tip = Offset(base.x + nx * thorn + dx / len * thorn * 0.5f, base.y + ny * thorn + dy / len * thorn * 0.5f)
        val half = 1.1f * density
        drawPath(Path().apply {
            moveTo(base.x - dx / len * half, base.y - dy / len * half)
            lineTo(tip.x, tip.y)
            lineTo(base.x + dx / len * half, base.y + dy / len * half)
            close()
        }, color)
    }
}

/** A crescent moon at [at], its hollow facing away from the centre ([facing], radians). */
private fun DrawScope.crescent(at: Offset, s: Float, facing: Double, color: Color, w: Float) {
    val deg = Math.toDegrees(facing).toFloat()
    drawArc(color, deg + 70f, 220f, false, topLeft = Offset(at.x - s, at.y - s), size = Size(s * 2, s * 2), style = stroke(w))
    val inner = Offset(at.x + (s * 0.4f * cos(facing)).toFloat(), at.y + (s * 0.4f * sin(facing)).toFloat())
    val ri = s * 0.8f
    drawArc(color, deg + 95f, 170f, false, topLeft = Offset(inner.x - ri, inner.y - ri), size = Size(ri * 2, ri * 2), style = stroke(w))
}

// ── Satanic: an occult seal ─────────────────────────────────────────────────────────────────────

private fun DrawScope.occultSeal(c: Offset, r: Float, base: Color) {
    val ink = base.copy(alpha = 0.62f)
    val faint = base.copy(alpha = 0.38f)
    val w = 1.1.dp.toPx()
    val d0 = r + 4.dp.toPx()
    val rnd = Random(66)
    gappedRing(c, d0, 12, 5f, faint, w * 0.8f, 1)
    gappedRing(c, d0 + 4.dp.toPx(), 5, 9f, ink, w, 2)
    // Runes between the rings, upright to the centre, four left out where the spire and moons sit.
    val runeD = d0 + 10.dp.toPx()
    for (k in 0 until 28) {
        val deg = k * 360f / 28 - 90f
        if (listOf(-90f, 90f, 0f, 180f).any { kotlin.math.abs(((deg - it + 540f) % 360f) - 180f) < 8f }) continue
        rune(polar(c, runeD, Math.toRadians(deg.toDouble())), 3.2.dp.toPx(), deg + 90f, rnd.nextInt(RUNES.size), faint, w * 0.9f)
    }
    gappedRing(c, d0 + 16.dp.toPx(), 8, 6f, ink, w, 3)
    // Spikes out of the outer ring at the eight points.
    for (k in 0 until 8) {
        val a = Math.toRadians(k * 45.0 + 22.5)
        val p0 = polar(c, d0 + 16.dp.toPx(), a)
        val tip = polar(c, d0 + 23.dp.toPx(), a)
        val side = Math.toRadians(k * 45.0 + 22.5 + 90)
        val h = 1.6.dp.toPx()
        drawPath(Path().apply {
            moveTo(p0.x + (h * cos(side)).toFloat(), p0.y + (h * sin(side)).toFloat())
            lineTo(tip.x, tip.y)
            lineTo(p0.x - (h * cos(side)).toFloat(), p0.y - (h * sin(side)).toFloat())
            close()
        }, ink)
    }
    // Thorn branches wound round the lower half and up the sides.
    thornBranch(c, d0 + 20.dp.toPx(), 100f, 150f, ink, w, 10)
    thornBranch(c, d0 + 20.dp.toPx(), 80f, -150f, ink, w, 11)
    // Above: an elongated spire — a staff with an inverted cross, flanked by two short spikes.
    val top = c.y - d0 - 16.dp.toPx()
    inked(Offset(c.x, top), Offset(c.x, top - 30.dp.toPx()), ink, w * 1.2f, rnd)
    invertedCross(Offset(c.x, top - 18.dp.toPx()), 16.dp.toPx(), ink, w * 1.3f)
    drawCircle(ink, 1.6.dp.toPx(), Offset(c.x, top - 32.dp.toPx()))
    listOf(-1f, 1f).forEach { s ->
        inked(Offset(c.x + s * 8.dp.toPx(), top + 2.dp.toPx()), Offset(c.x + s * 11.dp.toPx(), top - 14.dp.toPx()), faint, w, rnd)
        inked(Offset(c.x + s * 15.dp.toPx(), top + 5.dp.toPx()), Offset(c.x + s * 19.dp.toPx(), top - 6.dp.toPx()), faint, w, rnd)
    }
    // Below: a short finial — a small inverted pentagram on a stub (the title is close).
    val bottom = c.y + d0 + 16.dp.toPx()
    inked(Offset(c.x, bottom), Offset(c.x, bottom + 5.dp.toPx()), ink, w, rnd)
    smallPentagram(Offset(c.x, bottom + 11.dp.toPx()), 5.5.dp.toPx(), ink, w)
    // The sides: crescents facing out, a vertical ornament above and below each.
    listOf(0.0, PI).forEach { a ->
        val at = polar(c, d0 + 26.dp.toPx(), a)
        crescent(at, 6.dp.toPx(), a, ink, w)
        listOf(-1f, 1f).forEach { s ->
            inked(Offset(at.x, at.y + s * 9.dp.toPx()), Offset(at.x, at.y + s * 20.dp.toPx()), faint, w, rnd)
            drawCircle(faint, 1.3.dp.toPx(), Offset(at.x, at.y + s * 23.dp.toPx()))
        }
    }
}

private fun DrawScope.smallPentagram(c: Offset, r: Float, color: Color, w: Float) {
    val pts = (0 until 5).map { k -> polar(c, r, PI / 2 + k * 2 * PI / 5) }
    drawPath(Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (k in 1..5) pts[(k * 2) % 5].let { lineTo(it.x, it.y) }
        close()
    }, color, style = stroke(w))
    drawCircle(color, r * 1.15f, c, style = stroke(w * 0.8f))
}

/** A crown of thorns round a square cover: two vines winding along its edge, crossing it a little. */
private fun DrawScope.thornWreath(base: Color) {
    val ink = base.copy(alpha = 0.7f)
    val w = 1.3.dp.toPx()
    val inset = -3.dp.toPx()
    val rect = Rect(inset, inset, size.width - inset, size.height - inset)
    val perimeter = 2 * (rect.width + rect.height)
    fun along(t: Float): Pair<Offset, Offset> {
        // A point on the rectangle at [t] (0..1 round it) and the outward normal there.
        var s = t * perimeter
        if (s < rect.width) return Offset(rect.left + s, rect.top) to Offset(0f, -1f)
        s -= rect.width
        if (s < rect.height) return Offset(rect.right, rect.top + s) to Offset(1f, 0f)
        s -= rect.height
        if (s < rect.width) return Offset(rect.right - s, rect.bottom) to Offset(0f, 1f)
        s -= rect.width
        return Offset(rect.left, rect.bottom - s) to Offset(-1f, 0f)
    }
    val rnd = Random(13)
    listOf(0f, PI.toFloat()).forEachIndexed { vine, phase ->
        val n = 220
        val path = Path()
        for (k in 0..n) {
            val t = k.toFloat() / n
            val (p, nrm) = along(t)
            val wave = sin(t * 2 * PI.toFloat() * 26 + phase) * 4.dp.toPx()
            val q = Offset(p.x + nrm.x * wave, p.y + nrm.y * wave)
            if (k == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
        }
        drawPath(path, ink, style = stroke(w))
        // Thorns at the outer crests of this vine.
        for (k in 0 until 26) {
            val t = (k + (if (vine == 0) 0.25f else 0.75f)) / 26f
            val (p, nrm) = along(t)
            val out = if (vine == 0) 1f else -1f
            val crest = Offset(p.x + nrm.x * 4.dp.toPx() * out, p.y + nrm.y * 4.dp.toPx() * out)
            val len = (4f + rnd.nextFloat() * 3f).dp.toPx()
            val tip = Offset(crest.x + nrm.x * len * out, crest.y + nrm.y * len * out)
            val tx = -nrm.y
            val ty = nrm.x
            val h = 1.3.dp.toPx()
            drawPath(Path().apply {
                moveTo(crest.x + tx * h, crest.y + ty * h)
                lineTo(tip.x, tip.y)
                lineTo(crest.x - tx * h, crest.y - ty * h)
                close()
            }, ink)
        }
    }
}

// ── Gothic: a cathedral seal ────────────────────────────────────────────────────────────────────

private fun DrawScope.cathedralSeal(c: Offset, r: Float, base: Color) {
    val ink = base.copy(alpha = 0.6f)
    val faint = base.copy(alpha = 0.36f)
    val w = 1.1.dp.toPx()
    val d0 = r + 4.dp.toPx()
    val rnd = Random(77)
    gappedRing(c, d0, 16, 4f, faint, w * 0.8f, 4)
    gappedRing(c, d0 + 4.dp.toPx(), 4, 14f, ink, w, 5)
    // A crown of pointed arches round it, opening outward, like a cloister seen from above.
    val arches = 20
    for (k in 0 until arches) {
        val a0 = Math.toRadians(k * 360.0 / arches)
        val a1 = Math.toRadians((k + 1) * 360.0 / arches)
        val am = (a0 + a1) / 2
        val p0 = polar(c, d0 + 5.dp.toPx(), a0)
        val p1 = polar(c, d0 + 5.dp.toPx(), a1)
        val tip = polar(c, d0 + 15.dp.toPx(), am)
        val s0 = polar(c, d0 + 11.dp.toPx(), a0 + (am - a0) * 0.2)
        val s1 = polar(c, d0 + 11.dp.toPx(), a1 - (a1 - am) * 0.2)
        drawPath(Path().apply {
            moveTo(p0.x, p0.y)
            quadraticTo(s0.x, s0.y, tip.x, tip.y)
            quadraticTo(s1.x, s1.y, p1.x, p1.y)
        }, faint, style = stroke(w * 0.9f))
        // A mullion in each arch.
        inked(polar(c, d0 + 5.dp.toPx(), am), polar(c, d0 + 11.dp.toPx(), am), faint, w * 0.7f, rnd)
    }
    gappedRing(c, d0 + 16.dp.toPx(), 8, 5f, ink, w, 6)
    // Above: a pinnacle with a cross; flanking smaller pinnacles.
    val top = c.y - d0 - 16.dp.toPx()
    pinnacle(Offset(c.x, top), 34.dp.toPx(), ink, w * 1.2f, cross = true)
    listOf(-1f, 1f).forEach { s -> pinnacle(Offset(c.x + s * 16.dp.toPx(), top + 3.dp.toPx()), 16.dp.toPx(), faint, w, cross = false) }
    // Below: a short pointed finial.
    val bottom = c.y + d0 + 16.dp.toPx()
    drawPath(Path().apply {
        moveTo(c.x - 4.dp.toPx(), bottom)
        lineTo(c.x, bottom + 12.dp.toPx())
        lineTo(c.x + 4.dp.toPx(), bottom)
    }, ink, style = stroke(w))
    // The sides: tall lancet windows with an upright cross above.
    listOf(-1f, 1f).forEach { s ->
        val x = c.x + s * (d0 + 25.dp.toPx())
        val h = 34.dp.toPx()
        val ww = 7.dp.toPx()
        drawPath(Path().apply {
            moveTo(x - ww / 2, c.y + h / 2)
            lineTo(x - ww / 2, c.y - h / 2 + ww)
            quadraticTo(x - ww / 2, c.y - h / 2, x, c.y - h / 2 - ww * 0.5f)
            quadraticTo(x + ww / 2, c.y - h / 2, x + ww / 2, c.y - h / 2 + ww)
            lineTo(x + ww / 2, c.y + h / 2)
        }, ink, style = stroke(w))
        inked(Offset(x, c.y - h / 2 + ww * 1.2f), Offset(x, c.y + h / 2), faint, w * 0.7f, rnd)
        val cy = c.y - h / 2 - ww * 0.5f - 9.dp.toPx()
        drawLine(ink, Offset(x, cy - 5.dp.toPx()), Offset(x, cy + 5.dp.toPx()), w)
        drawLine(ink, Offset(x - 3.dp.toPx(), cy - 1.5.dp.toPx()), Offset(x + 3.dp.toPx(), cy - 1.5.dp.toPx()), w)
    }
}

/** A gothic pinnacle rising from [base]: a slender spire with crockets, a cross on top if [cross]. */
private fun DrawScope.pinnacle(base: Offset, h: Float, color: Color, w: Float, cross: Boolean) {
    val half = h * 0.14f
    drawPath(Path().apply {
        moveTo(base.x - half, base.y)
        lineTo(base.x, base.y - h)
        lineTo(base.x + half, base.y)
    }, color, style = stroke(w))
    // Crockets: little hooks up the edges.
    for (k in 1..3) {
        val t = k / 4f
        val y = base.y - h * t
        val x = half * (1 - t)
        drawLine(color, Offset(base.x - x, y), Offset(base.x - x - 2.dp.toPx(), y - 2.dp.toPx()), w * 0.8f)
        drawLine(color, Offset(base.x + x, y), Offset(base.x + x + 2.dp.toPx(), y - 2.dp.toPx()), w * 0.8f)
    }
    if (cross) {
        val cy = base.y - h - 6.dp.toPx()
        drawLine(color, Offset(base.x, base.y - h), Offset(base.x, cy - 6.dp.toPx()), w)
        drawLine(color, Offset(base.x - 4.dp.toPx(), cy - 2.dp.toPx()), Offset(base.x + 4.dp.toPx(), cy - 2.dp.toPx()), w)
    }
}

// ── Street art: a sprayed seal ──────────────────────────────────────────────────────────────────

private fun DrawScope.sprayedSeal(c: Offset, r: Float, paint: Color) {
    val color = paint.copy(alpha = 0.8f)
    val soft = paint.copy(alpha = 0.45f)
    val rnd = Random(21)
    // Two quick sprayed rings, uneven in width, one not closed.
    val d0 = r + 6.dp.toPx()
    drawArc(color, -80f, 330f, false, topLeft = Offset(c.x - d0, c.y - d0), size = Size(d0 * 2, d0 * 2), style = stroke(3.dp.toPx()))
    val d1 = d0 + 7.dp.toPx()
    drawArc(soft, 120f, 260f, false, topLeft = Offset(c.x - d1, c.y - d1 + 2.dp.toPx()), size = Size(d1 * 2, d1 * 2), style = stroke(1.6.dp.toPx()))
    // Overspray dust along the rings.
    repeat(90) {
        val a = rnd.nextDouble() * 2 * PI
        val dd = d0 + (rnd.nextFloat() - 0.3f) * 14.dp.toPx()
        drawCircle(soft.copy(alpha = soft.alpha * rnd.nextFloat()), (0.5f + rnd.nextFloat()).dp.toPx(), polar(c, dd, a))
    }
    // A graffiti crown above.
    val cy = c.y - d1 - 8.dp.toPx()
    val cw = 30.dp.toPx()
    drawPath(Path().apply {
        moveTo(c.x - cw / 2, cy + 6.dp.toPx())
        lineTo(c.x - cw / 2 - 2.dp.toPx(), cy - 12.dp.toPx())
        lineTo(c.x - cw / 4, cy - 2.dp.toPx())
        lineTo(c.x, cy - 16.dp.toPx())
        lineTo(c.x + cw / 4, cy - 2.dp.toPx())
        lineTo(c.x + cw / 2 + 2.dp.toPx(), cy - 12.dp.toPx())
        lineTo(c.x + cw / 2, cy + 6.dp.toPx())
        close()
    }, color, style = stroke(2.4.dp.toPx()))
    listOf(-cw / 2 - 2.dp.toPx(), 0f, cw / 2 + 2.dp.toPx()).zip(listOf(-12f, -16f, -12f)).forEach { (dx, dy) ->
        drawCircle(color, 2.2.dp.toPx(), Offset(c.x + dx, cy + dy.dp.toPx() - 3.dp.toPx()))
    }
    // Drips hanging from the bottom of the ring, short of the title.
    listOf(-0.35f, -0.12f, 0.08f, 0.3f).forEach { f ->
        val x = c.x + d0 * f * 1.6f
        val y = c.y + kotlin.math.sqrt((d0 * d0 - (x - c.x) * (x - c.x)).coerceAtLeast(0f)) - 1.dp.toPx()
        drip(Offset(x, y), (5f + rnd.nextFloat() * 9f).dp.toPx(), 2.4.dp.toPx(), color)
    }
    // Sparkles at the sides, a splatter low on the left, an arrow scrawl on the right.
    sparkle(polar(c, d1 + 14.dp.toPx(), PI * 1.15), 8.dp.toPx(), color)
    sparkle(polar(c, d1 + 12.dp.toPx(), -PI * 0.15), 6.dp.toPx(), color)
    splatter(polar(c, d1 + 8.dp.toPx(), PI * 0.78), 9.dp.toPx(), soft, rnd)
    val a0 = polar(c, d1 + 6.dp.toPx(), PI * 0.12)
    val a1 = polar(c, d1 + 20.dp.toPx(), -PI * 0.05)
    drawLine(color, a0, a1, 2.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, a1, Offset(a1.x - 6.dp.toPx(), a1.y - 1.dp.toPx()), 2.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, a1, Offset(a1.x - 2.dp.toPx(), a1.y + 6.dp.toPx()), 2.dp.toPx(), cap = StrokeCap.Round)
}

// ── Glitch: a trembling pen round the cover ─────────────────────────────────────────────────────

private fun DrawScope.penSeal(c: Offset, r: Float, base: Color) {
    val ink = base.copy(alpha = 0.6f)
    val w = 1.3.dp.toPx()
    scribbleLoops(c, r + 7.dp.toPx(), r + 6.dp.toPx(), 1.3f, ink, w, Random(8))
    scribbleLoops(c, r + 13.dp.toPx(), r + 11.dp.toPx(), 0.8f, ink.copy(alpha = 0.4f), w * 0.8f, Random(9))
    crossOut(Rect(c.x + r + 10.dp.toPx(), c.y - r * 0.7f, c.x + r + 24.dp.toPx(), c.y - r * 0.7f + 14.dp.toPx()), ink, w, Random(10))
    spiral(Offset(c.x - r - 16.dp.toPx(), c.y - r * 0.5f), 9.dp.toPx(), ink, w, Random(11))
    stitches(Offset(c.x - 14.dp.toPx(), c.y - r - 20.dp.toPx()), 4, 9.dp.toPx(), ink, w, Random(12))
    arrow(Offset(c.x - r - 18.dp.toPx(), c.y + r * 0.7f), Offset(c.x - r - 4.dp.toPx(), c.y + r * 0.35f), ink, w, Random(13))
}
