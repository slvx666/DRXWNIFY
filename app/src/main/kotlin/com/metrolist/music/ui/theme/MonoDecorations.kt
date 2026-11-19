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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
            // The bottom edge as the bottom bar's top edge: a rule with thorns, here pointing up.
            thornLine(size.height - 1.dp.toPx(), ink.copy(alpha = if (dark) 0.42f else 0.5f), down = false, seed = 9)
        }
        AppThemePreset.GOTHIC -> Canvas(Modifier.matchParentSize()) {
            // A row of small upright crosses along the bottom, on a thin rule.
            val cross = ink.copy(alpha = if (dark) 0.3f else 0.36f)
            val step = 26.dp.toPx()
            var x = step / 2
            val h = 9.dp.toPx()
            val base = size.height - 5.dp.toPx()
            while (x < size.width) {
                drawLine(cross, Offset(x, base - h), Offset(x, base), 1.2.dp.toPx())
                drawLine(cross, Offset(x - h * 0.28f, base - h * 0.7f), Offset(x + h * 0.28f, base - h * 0.7f), 1.2.dp.toPx())
                x += step
            }
            hairline(ink.copy(alpha = 0.35f))
        }
        AppThemePreset.STREET -> Canvas(Modifier.matchParentSize()) { streetBar(accent, ink, dark) }
        AppThemePreset.BREAKCORE -> Canvas(Modifier.matchParentSize()) { glitchBar(ink, dark) }
        else -> return false
    }
    return true
}

private fun DrawScope.hairline(color: Color) {
    val h = 1.dp.toPx()
    drawRect(color, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
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

/** Spray along the top edge, drips hanging from it, splatter at the ends, a sparkle. Still. */
private fun DrawScope.streetBar(paint: Color, ink: Color, dark: Boolean) {
    val color = paint.copy(alpha = if (dark) 0.6f else 0.65f)
    val r = Random(7)
    // Overspray: a soft band and dust along the top.
    drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(0f to color.copy(alpha = color.alpha * 0.35f), 1f to Color.Transparent), size = Size(size.width, size.height * 0.35f))
    repeat(140) {
        drawCircle(color.copy(alpha = color.alpha * (0.3f + r.nextFloat() * 0.7f)), (0.5f + r.nextFloat()).dp.toPx(), Offset(r.nextFloat() * size.width, r.nextFloat() * r.nextFloat() * size.height * 0.5f))
    }
    val count = (size.width / 80.dp.toPx()).toInt().coerceAtLeast(3)
    repeat(count) {
        drip(Offset(size.width * (0.06f + 0.88f * r.nextFloat()), 0f), size.height * (0.15f + 0.4f * r.nextFloat()), (2f + r.nextFloat() * 1.5f).dp.toPx(), color)
    }
    splatter(Offset(6.dp.toPx(), size.height * 0.8f), 14.dp.toPx(), color, r)
    sparkle(Offset(size.width * 0.62f, size.height * 0.3f), 6.dp.toPx(), color)
    drawLine(ink.copy(alpha = if (dark) 0.25f else 0.3f), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
}

/** Scanlines and a shaky pen going over the bar: a wobbly underline, a few doodles. */
private fun DrawScope.glitchBar(ink: Color, dark: Boolean) {
    val line = ink.copy(alpha = if (dark) 0.1f else 0.08f)
    val step = 3.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawRect(line, topLeft = Offset(0f, y), size = Size(size.width, 1f))
        y += step
    }
    val pen = ink.copy(alpha = if (dark) 0.45f else 0.55f)
    val w = 1.2.dp.toPx()
    shakyLine(Offset(0f, size.height - 3.dp.toPx()), Offset(size.width, size.height - 5.dp.toPx()), pen, w, Random(3))
    shakyLine(Offset(size.width * 0.1f, size.height - 1.5.dp.toPx()), Offset(size.width * 0.7f, size.height - 2.dp.toPx()), pen, w * 0.8f, Random(4))
    // Doodles in the middle of the bar, between the title and the buttons.
    scribbleCluster(
        androidx.compose.ui.geometry.Rect(size.width * 0.5f, size.height * 0.15f, size.width * 0.66f, size.height * 0.8f),
        pen.copy(alpha = pen.alpha * 0.8f), w, Random(11),
    )
    stitches(Offset(size.width * 0.04f, size.height * 0.18f), 4, 9.dp.toPx(), pen.copy(alpha = pen.alpha * 0.7f), w * 0.9f, Random(12))
}

/** The mark after a screen title, for the themes of this file; false when there is none. */
@Composable
internal fun MonoTitleAccent(preset: AppThemePreset): Boolean {
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    when (preset) {
        AppThemePreset.SATANIC -> Canvas(Modifier.padding(start = 10.dp).offset(y = (-2).dp).size(width = 12.dp, height = 18.dp)) {
            // One inverted cross.
            invertedCross(Offset(size.width / 2, size.height / 2), size.height, ink.copy(alpha = 0.75f), 1.6.dp.toPx())
        }
        AppThemePreset.GOTHIC -> Canvas(Modifier.padding(start = 10.dp).offset(y = (-1).dp).size(width = 12.dp, height = 18.dp)) {
            // An upright cross, its arms a little flared.
            val color = ink.copy(alpha = 0.7f)
            val w = 1.6.dp.toPx()
            drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), w)
            drawLine(color, Offset(0f, size.height * 0.3f), Offset(size.width, size.height * 0.3f), w)
        }
        AppThemePreset.STREET -> Canvas(Modifier.padding(start = 8.dp).offset(y = (-4).dp).size(width = 22.dp, height = 22.dp)) {
            // A sprayed sparkle beside the tag.
            sparkle(Offset(size.width * 0.4f, size.height * 0.5f), size.height * 0.45f, accent.copy(alpha = 0.9f))
            drawCircle(accent.copy(alpha = 0.8f), 1.6.dp.toPx(), Offset(size.width * 0.85f, size.height * 0.2f))
        }
        else -> return false
    }
    return true
}

/**
 * Breakcore's title: the words with a faint ghost of them slipped to the side, like a tape that
 * didn't track, gone over with a shaky pen (an underline, a loop after it).
 */
@Composable
internal fun BreakcoreTitle(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        modifier.drawWithContent {
            drawContent()
            val pen = ink.copy(alpha = 0.6f)
            val w = 1.3.dp.toPx()
            val r = Random(text.hashCode())
            shakyLine(Offset(-2.dp.toPx(), size.height * 0.92f), Offset(size.width * (0.6f + r.nextFloat() * 0.4f), size.height * 0.88f), pen, w, r)
            scribbleLoops(Offset(size.width + 12.dp.toPx(), size.height * 0.45f), 7.dp.toPx(), 6.dp.toPx(), 3f, pen, w, r)
        },
    ) {
        Text(
            text = text,
            style = style.copy(fontFamily = GlitchFace, fontWeight = FontWeight.Normal),
            color = accent.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset(x = 3.dp, y = (-2).dp),
        )
        Text(text = text, style = style.copy(fontFamily = GlitchFace, fontWeight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Gothic's title: a book face, spaced out like a carved inscription, with its quatrefoil. */
@Composable
internal fun GothicTitle(text: String, style: TextStyle) {
    Text(
        text = text,
        style = style.copy(fontFamily = GothicFace, fontWeight = FontWeight.Normal, letterSpacing = 0.5.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    MonoTitleAccent(AppThemePreset.GOTHIC)
}

/** Satanic's title in a metal face (with Cyrillic serif fallback), with its crosses after it. */
@Composable
internal fun SatanicTitle(text: String, style: TextStyle) {
    Text(
        text = text,
        style = style.copy(fontFamily = SatanicThemeConfig.TitleFont, fontWeight = FontWeight.Normal, letterSpacing = style.letterSpacing),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    MonoTitleAccent(AppThemePreset.SATANIC)
}

/*
 * The experimental themes' faces (Google Fonts, OFL; cut down to Latin and Cyrillic so the Russian
 * words get them too — the metal face before had no Cyrillic and fell back to the system one):
 * Gothic Ruslan Display, Street art Rubik Wet Paint, Glitch Rubik Glitch.
 * One weight each: never asked to be bold (that would be faked).
 */
// Satanic keeps the metal face (Metal Mania) the owner chose; it has Latin only, Cyrillic falls back.
internal val MetalFace = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(com.metrolist.music.R.font.metal_mania))
internal val GothicFace = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(com.metrolist.music.R.font.theme_gothic))
internal val StreetFace = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(com.metrolist.music.R.font.theme_street))
internal val GlitchFace = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(com.metrolist.music.R.font.theme_glitch))

/**
 * Street art's title as a tag: heavy slanted letters in the paint colour with a hard shadow,
 * tilted a little, paint dripping from it. Only the screen titles and the section titles get it.
 */
@Composable
internal fun StreetTitle(text: String, style: TextStyle) {
    val paint = MaterialTheme.colorScheme.primary
    val shadow = MaterialTheme.colorScheme.surface
    Text(
        text = text,
        style = style.copy(
            fontFamily = StreetFace,
            fontWeight = FontWeight.Normal,
            shadow = androidx.compose.ui.graphics.Shadow(color = Color.Black.copy(alpha = if (shadow.dark()) 0.9f else 0.35f), offset = Offset(3f, 3f), blurRadius = 0f),
        ),
        color = paint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.graphicsLayer { rotationZ = -2f },
    )
    MonoTitleAccent(AppThemePreset.STREET)
}

/** Drips under a few letters of a tag (the same ones for the same words). */
private fun DrawScope.streetDrips(text: String, paint: Color) {
    val r = Random(text.hashCode())
    val n = 2 + r.nextInt(2)
    repeat(n) {
        val x = size.width * (0.1f + r.nextFloat() * 0.8f)
        drip(Offset(x, size.height * 0.72f), (3f + r.nextFloat() * 9f).dp.toPx(), (1.6f + r.nextFloat()).dp.toPx(), paint.copy(alpha = 0.9f))
    }
}

/**
 * The section titles (the home page's rows and the like): the same text, gone over the theme's way
 * without moving anything — drawn around the words, never in place of them.
 */
@Composable
fun Modifier.themeSectionTitle(title: String): Modifier {
    val preset = LocalAppThemePreset.current
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    return when (preset) {
        AppThemePreset.SATANIC -> drawWithContent {
            drawContent()
            // An inverted cross after the words, thorns under them.
            invertedCross(Offset(size.width + 9.dp.toPx(), size.height * 0.5f), 13.dp.toPx(), accent.copy(alpha = 0.75f), 1.4.dp.toPx())
        }
        AppThemePreset.GOTHIC -> drawWithContent {
            drawContent()
            // A slender upright cross after the words.
            val c = Offset(size.width + 9.dp.toPx(), size.height * 0.52f)
            val h = 13.dp.toPx()
            val color = accent.copy(alpha = 0.7f)
            drawLine(color, Offset(c.x, c.y - h / 2), Offset(c.x, c.y + h / 2), 1.3.dp.toPx())
            drawLine(color, Offset(c.x - h * 0.26f, c.y - h * 0.2f), Offset(c.x + h * 0.26f, c.y - h * 0.2f), 1.3.dp.toPx())
        }
        AppThemePreset.STREET -> graphicsLayer { rotationZ = -1.5f }
        AppThemePreset.BREAKCORE -> drawWithContent {
            drawContent()
            val r = Random(title.hashCode())
            val pen = ink.copy(alpha = 0.55f)
            val w = 1.2.dp.toPx()
            when (r.nextInt(4)) {
                0 -> shakyLine(Offset(0f, size.height * 0.95f), Offset(size.width * (0.5f + r.nextFloat() * 0.5f), size.height * 0.9f), pen, w, r)
                1 -> scribbleLoops(Offset(size.width + 12.dp.toPx(), size.height * 0.5f), 7.dp.toPx(), 6.dp.toPx(), 2.5f, pen, w, r)
                2 -> stitches(Offset(size.width + 8.dp.toPx(), size.height * 0.5f), 3, 9.dp.toPx(), pen, w, r)
                else -> arrow(Offset(size.width + 30.dp.toPx(), size.height * 0.9f), Offset(size.width + 6.dp.toPx(), size.height * 0.4f), pen, w, r)
            }
        }
        else -> this
    }
}

/**
 * How a section title is set in the theme: Satanic's metal face, Gothic's spaced book face, Street
 * art's tag letters; the others as they are.
 */
@Composable
fun themeSectionStyle(style: TextStyle): TextStyle = when (LocalAppThemePreset.current) {
    AppThemePreset.SATANIC -> style.copy(fontFamily = SatanicThemeConfig.TitleFont, fontWeight = FontWeight.Normal)
    AppThemePreset.GOTHIC -> style.copy(fontFamily = GothicFace, fontWeight = FontWeight.Normal)
    AppThemePreset.STREET -> style.copy(fontFamily = StreetFace, fontWeight = FontWeight.Normal)
    AppThemePreset.BREAKCORE -> style.copy(fontFamily = GlitchFace, fontWeight = FontWeight.Normal)
    else -> style
}

/**
 * The face of the words that stand out (settings section titles, the bottom bar's labels, the
 * words already in the accent colour): each experimental theme's own.
 */
@Composable
fun themeAccentStyle(style: TextStyle): TextStyle = when (LocalAppThemePreset.current) {
    AppThemePreset.SATANIC -> style.copy(fontFamily = SatanicThemeConfig.TitleFont, fontWeight = FontWeight.Normal)
    AppThemePreset.GOTHIC -> style.copy(fontFamily = GothicFace, fontWeight = FontWeight.Normal)
    AppThemePreset.STREET -> style.copy(fontFamily = StreetFace, fontWeight = FontWeight.Normal)
    AppThemePreset.BREAKCORE -> style.copy(fontFamily = GlitchFace, fontWeight = FontWeight.Normal)
    else -> style
}

/** The weight a section title is set in (the metal face has only its regular one). */
@Composable
fun themeSectionWeight(): FontWeight = when (LocalAppThemePreset.current) {
    AppThemePreset.SATANIC, AppThemePreset.GOTHIC, AppThemePreset.STREET, AppThemePreset.BREAKCORE -> FontWeight.Normal
    else -> FontWeight.Bold
}

/** Themes whose bottom bar marks the open tab with a line under it instead of the pill. */
fun AppThemePreset.underlinesTabs(): Boolean = this == AppThemePreset.MONOCHROME || group == AppThemePreset.Group.EXPERIMENTAL

/** The line under the open tab's label (or icon), each theme its own way. */
@Composable
fun Modifier.themeTabUnderline(selected: Boolean): Modifier {
    if (!selected) return this
    val preset = LocalAppThemePreset.current
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    return drawWithContent {
        drawContent()
        val y = size.height + 4.dp.toPx()
        val w = size.width + 8.dp.toPx()
        val x0 = (size.width - w) / 2
        when (preset) {
            AppThemePreset.STREET -> {
                drawLine(accent, Offset(x0, y), Offset(x0 + w, y - 1.dp.toPx()), 3.dp.toPx(), cap = StrokeCap.Round)
                drip(Offset(x0 + w * 0.7f, y), 5.dp.toPx(), 2.dp.toPx(), accent)
            }
            AppThemePreset.BREAKCORE -> shakyLine(Offset(x0, y), Offset(x0 + w, y), ink, 1.6.dp.toPx(), Random(5))
            AppThemePreset.GOTHIC -> {
                drawLine(ink, Offset(x0, y), Offset(x0 + w, y), 1.dp.toPx())
                drawLine(ink.copy(alpha = 0.6f), Offset(x0 + 4.dp.toPx(), y + 3.dp.toPx()), Offset(x0 + w - 4.dp.toPx(), y + 3.dp.toPx()), 1.dp.toPx())
            }
            AppThemePreset.SATANIC -> {
                drawLine(ink.copy(alpha = 0.85f), Offset(x0, y), Offset(x0 + w, y), 1.2.dp.toPx())
                val cx = size.width / 2
                val dSize = 2.5.dp.toPx()
                val path =
                    Path().apply {
                        moveTo(cx, y - dSize)
                        lineTo(cx + dSize, y)
                        lineTo(cx, y + dSize)
                        lineTo(cx - dSize, y)
                        close()
                    }
                drawPath(path, ink.copy(alpha = 0.9f))
            }
            else -> drawLine(ink, Offset(x0, y), Offset(x0 + w, y), 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/**
 * Along the top edge of the bottom bar, over it (its icons stay clear): Satanic's thorns pointing
 * up, Gothic's arcade, Street art's spray and a drip, Breakcore's shaky pen.
 */
@Composable
fun Modifier.themeNavBar(): Modifier {
    val preset = LocalAppThemePreset.current
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.surface.dark()
    return when (preset) {
        AppThemePreset.SATANIC -> drawWithContent {
            drawContent()
            drawLine(
                color = SatanicThemeConfig.Border,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx(),
            )
        }
        AppThemePreset.GOTHIC -> drawWithContent {
            drawContent()
            drawLine(ink.copy(alpha = 0.25f), Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
        }
        AppThemePreset.STREET -> drawWithContent {
            drawContent()
            val color = accent.copy(alpha = if (dark) 0.6f else 0.65f)
            drawRect(color, size = Size(size.width, 2.dp.toPx()))
            val r = Random(17)
            repeat(3) { drip(Offset(size.width * (0.15f + r.nextFloat() * 0.7f), 0f), (4f + r.nextFloat() * 8f).dp.toPx(), 2.dp.toPx(), color) }
        }
        AppThemePreset.BREAKCORE -> drawWithContent {
            drawContent()
            val pen = ink.copy(alpha = if (dark) 0.45f else 0.55f)
            shakyLine(Offset(0f, 2.dp.toPx()), Offset(size.width, 3.dp.toPx()), pen, 1.2.dp.toPx(), Random(21))
            stitches(Offset(size.width * 0.9f, 9.dp.toPx()), 2, 9.dp.toPx(), pen.copy(alpha = pen.alpha * 0.7f), 1.dp.toPx(), Random(22))
        }
        else -> this
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
    }
}
