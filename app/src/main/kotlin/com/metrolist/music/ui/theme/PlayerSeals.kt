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
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

@Composable
fun ThemePlayerDecor(ring: Boolean, color: Color, modifier: Modifier = Modifier) {
    val preset = LocalAppThemePreset.current
    if (preset.group != AppThemePreset.Group.EXPERIMENTAL) return
    if (preset == AppThemePreset.SATANIC) {
        if (ring) {
            val frame = SatanicThemeConfig.rememberAsset(SatanicThemeConfig.PATH_ALBUM_FRAME)
            if (frame != null) {
                androidx.compose.foundation.Image(
                    bitmap = frame,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = modifier,
                )
            }
        }
        return
    }
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
                    listOf(0.16f, 0.34f, 0.54f, 0.76f).forEach { f ->
                        drip(
                            Offset(size.width * f, size.height - 2.dp.toPx()),
                            (7f + rnd.nextFloat() * 8f).dp.toPx(),
                            2.2.dp.toPx(),
                            color.copy(alpha = 0.7f)
                        )
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

private fun polar(c: Offset, d: Float, a: Double) =
    Offset(c.x + (d * cos(a)).toFloat(), c.y + (d * sin(a)).toFloat())

/** Single clean ring split into 4 arcs with gaps at Top (-90°), Bottom (90°), Left (180°), Right (0°). */
private fun DrawScope.fourArcRing(c: Offset, radius: Float, gapDeg: Float, color: Color, w: Float) {
    for (k in 0 until 4) {
        val start = k * 90f + gapDeg / 2f
        val sweep = 90f - gapDeg
        drawArc(
            color = color,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(c.x - radius, c.y - radius),
            size = Size(radius * 2, radius * 2),
            style = stroke(w)
        )
    }
}

/** Small diamond accent centered at [at]. */
private fun DrawScope.smallDiamond(at: Offset, rx: Float, ry: Float, color: Color, w: Float, filled: Boolean = false) {
    val path = Path().apply {
        moveTo(at.x, at.y - ry)
        lineTo(at.x + rx, at.y)
        lineTo(at.x, at.y + ry)
        lineTo(at.x - rx, at.y)
        close()
    }
    if (filled) drawPath(path, color) else drawPath(path, color, style = stroke(w))
}

/** Small crescent moon placed inside the side gap so it never exceeds horizontal bounds. */
private fun DrawScope.miniCrescent(at: Offset, s: Float, facing: Double, color: Color, w: Float) {
    val deg = Math.toDegrees(facing).toFloat()
    val path = Path()
    val steps = 20
    val startDeg = deg + 65f
    val sweepDeg = 230f
    for (i in 0..steps) {
        val a = Math.toRadians((startDeg + sweepDeg * (i.toFloat() / steps)).toDouble())
        val p = polar(at, s, a)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    val inner = polar(at, s * 0.42f, facing)
    val ri = s * 0.82f
    val pStart = polar(at, s, Math.toRadians(startDeg.toDouble()))
    val pEnd = polar(at, s, Math.toRadians((startDeg + sweepDeg).toDouble()))
    val aEndInner = atan2((pEnd.y - inner.y).toDouble(), (pEnd.x - inner.x).toDouble())
    val aStartInner = atan2((pStart.y - inner.y).toDouble(), (pStart.x - inner.x).toDouble())
    var diff = aStartInner - aEndInner
    while (diff > PI) diff -= 2 * PI
    while (diff < -PI) diff += 2 * PI
    for (i in 1..steps) {
        val a = aEndInner + diff * (i.toFloat() / steps)
        val p = polar(inner, ri, a)
        path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, color, style = stroke(w))
}

// ── 1. Satanic: Minimalist Occult Ring ───────────────────────────────────────────────────────────

private fun DrawScope.occultSeal(c: Offset, r: Float, base: Color) {
    val ink = base.copy(alpha = 0.68f)
    val faint = base.copy(alpha = 0.42f)
    val w = 1.3.dp.toPx()

    // Fits comfortably inside horizontal clip boundary
    val ringR = r + 1.5.dp.toPx()

    // 4 clean arcs with gaps at top, bottom, left, right
    fourArcRing(c, ringR, gapDeg = 14f, color = ink, w = w)

    // 4 tiny subtle dots on the 45° diagonals
    listOf(-135.0, -45.0, 45.0, 135.0).forEach { deg ->
        val pt = polar(c, ringR + 3.dp.toPx(), Math.toRadians(deg))
        drawCircle(faint, 1.3.dp.toPx(), pt)
    }

    // Top gap: Simple, clean inverted cross
    val topCenterY = c.y - ringR - 6.dp.toPx()
    invertedCross(Offset(c.x, topCenterY), 13.dp.toPx(), ink, w * 1.15f)

    // Bottom gap: Small clean pentagram seated right in the bottom gap
    val botCenter = Offset(c.x, c.y + ringR + 2.dp.toPx())
    smallPentagram(botCenter, 5.dp.toPx(), ink, w * 0.9f)

    // Left & Right gaps: Tiny crescents sitting right ON the ring radius (never clipped!)
    listOf(0.0, PI).forEach { a ->
        val at = polar(c, ringR, a)
        miniCrescent(at, 3.2.dp.toPx(), a, ink, w * 0.95f)
    }
}

private fun DrawScope.smallPentagram(c: Offset, r: Float, color: Color, w: Float) {
    val pts = (0 until 5).map { k -> polar(c, r, PI / 2 + k * 2 * PI / 5) }
    drawPath(
        Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (k in 1..5) pts[(k * 2) % 5].let { lineTo(it.x, it.y) }
            close()
        },
        color = color,
        style = stroke(w)
    )
    drawCircle(color, r * 1.15f, c, style = stroke(w * 0.85f))
}

/** Minimalist thin frame with 4 tiny corner ticks for square cover. */
private fun DrawScope.thornWreath(base: Color) {
    val ink = base.copy(alpha = 0.6f)
    val w = 1.2.dp.toPx()
    val pad = 1.5.dp.toPx()
    drawRect(
        color = ink,
        topLeft = Offset(-pad, -pad),
        size = Size(size.width + pad * 2, size.height + pad * 2),
        style = stroke(w)
    )
}

// ── 2. Gothic: Minimalist Dark Gothic Ring ───────────────────────────────────────────────────────

private fun DrawScope.cathedralSeal(c: Offset, r: Float, base: Color) {
    val ink = base.copy(alpha = 0.68f)
    val faint = base.copy(alpha = 0.42f)
    val w = 1.3.dp.toPx()

    val ringR = r + 1.5.dp.toPx()

    // 4 clean arcs with gaps at cardinal points
    fourArcRing(c, ringR, gapDeg = 12f, color = ink, w = w)

    // 4 small, neat pointed ticks at the 45° diagonals
    listOf(-135.0, -45.0, 45.0, 135.0).forEach { deg ->
        val rad = Math.toRadians(deg)
        val pIn = polar(c, ringR - 2.dp.toPx(), rad)
        val pOut = polar(c, ringR + 3.5.dp.toPx(), rad)
        drawLine(ink, pIn, pOut, w, cap = StrokeCap.Round)
    }

    // Top gap: Slender minimalist Gothic cross
    val topBaseY = c.y - ringR + 2.dp.toPx()
    val topTipY = c.y - ringR - 17.dp.toPx()
    val crossY = c.y - ringR - 11.dp.toPx()
    drawLine(ink, Offset(c.x, topBaseY), Offset(c.x, topTipY), w * 1.15f, cap = StrokeCap.Round)
    drawLine(ink, Offset(c.x - 5.dp.toPx(), crossY), Offset(c.x + 5.dp.toPx(), crossY), w * 1.15f, cap = StrokeCap.Round)
    smallDiamond(Offset(c.x, c.y - ringR), 2.dp.toPx(), 2.5.dp.toPx(), faint, w * 0.85f, filled = true)

    // Bottom gap: Small clean pointed diamond tip
    val botY = c.y + ringR
    smallDiamond(Offset(c.x, botY + 2.dp.toPx()), 2.5.dp.toPx(), 4.5.dp.toPx(), ink, w)

    // Left & Right gaps: Tiny diamond points sitting inside the ring gap (never clipped!)
    listOf(-1f, 1f).forEach { s ->
        val at = Offset(c.x + s * ringR, c.y)
        smallDiamond(at, 2.2.dp.toPx(), 1.6.dp.toPx(), faint, w, filled = true)
    }
}

// ── 3. Street Art: Minimalist Crown & Drips Ring ─────────────────────────────────────────────────

private fun DrawScope.sprayedSeal(c: Offset, r: Float, paint: Color) {
    val color = paint.copy(alpha = 0.78f)
    val soft = paint.copy(alpha = 0.42f)
    val rnd = Random(21)

    // Primary ring close to the slider so it never clips horizontally
    val d0 = r + 1.5.dp.toPx()
    drawArc(
        color = color,
        startAngle = -72f,
        sweepAngle = 324f,
        useCenter = false,
        topLeft = Offset(c.x - d0, c.y - d0),
        size = Size(d0 * 2, d0 * 2),
        style = stroke(2.dp.toPx())
    )

    // Secondary light sketch arcs ONLY on diagonals (avoids horizontal clipping at 0° and 180°)
    val d1 = d0 + 3.dp.toPx()
    drawArc(
        color = soft,
        startAngle = -155f,
        sweepAngle = 60f,
        useCenter = false,
        topLeft = Offset(c.x - d1, c.y - d1),
        size = Size(d1 * 2, d1 * 2),
        style = stroke(1.2.dp.toPx())
    )
    drawArc(
        color = soft,
        startAngle = 25f,
        sweepAngle = 110f,
        useCenter = false,
        topLeft = Offset(c.x - d1, c.y - d1),
        size = Size(d1 * 2, d1 * 2),
        style = stroke(1.2.dp.toPx())
    )

    // Small, clean 3-pointed crown at the top
    val cy = c.y - d0 - 4.dp.toPx()
    val cw = 20.dp.toPx()
    drawPath(
        Path().apply {
            moveTo(c.x - cw * 0.42f, cy)
            lineTo(c.x - cw * 0.48f, cy - 10.dp.toPx())
            lineTo(c.x - cw * 0.16f, cy - 3.5.dp.toPx())
            lineTo(c.x, cy - 13.dp.toPx())
            lineTo(c.x + cw * 0.16f, cy - 3.5.dp.toPx())
            lineTo(c.x + cw * 0.48f, cy - 10.dp.toPx())
            lineTo(c.x + cw * 0.42f, cy)
            close()
        },
        color = color,
        style = stroke(1.7.dp.toPx())
    )
    listOf(
        Offset(c.x - cw * 0.48f, cy - 12.8.dp.toPx()),
        Offset(c.x, cy - 15.8.dp.toPx()),
        Offset(c.x + cw * 0.48f, cy - 12.8.dp.toPx())
    ).forEach { pt ->
        drawCircle(color, 1.4.dp.toPx(), pt)
    }

    // 4 short, clean drips at the bottom
    listOf(-0.26f, -0.08f, 0.09f, 0.24f).forEach { f ->
        val x = c.x + d0 * f * 1.4f
        val y = c.y + sqrt((d0 * d0 - (x - c.x) * (x - c.x)).coerceAtLeast(0f)) - 0.5.dp.toPx()
        drip(Offset(x, y), (4.5f + rnd.nextFloat() * 6f).dp.toPx(), 1.9.dp.toPx(), color)
    }

    // Two small, subtle 4-point sparkles on the diagonals
    sparkle(polar(c, d1 + 1.5.dp.toPx(), -PI * 0.78), 5.dp.toPx(), color)
    sparkle(polar(c, d1 + 1.5.dp.toPx(), PI * 0.22), 4.5.dp.toPx(), soft)
}

// ── 4. Glitch: Untouched ─────────────────────────────────────────────────────────────────────────

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
