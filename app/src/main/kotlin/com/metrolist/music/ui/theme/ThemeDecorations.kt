package com.metrolist.music.ui.theme

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Seconds since the decoration appeared, ticking ~30 times a second on the frame clock (so it stops
 * with the app in the background). Stays put when the theme's animations are off.
 */
@Composable
private fun rememberDecorationClock(running: Boolean): MutableFloatState {
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val start = withFrameMillis { it } - (seconds.floatValue * 1000).toLong()
        while (true) {
            // Sleep between ticks instead of waiting on every vsync: awaiting each frame kept the
            // frame clock (and the whole window) busy at the display's 120 Hz for a 30 fps effect.
            kotlinx.coroutines.delay(FRAME_STEP_MS)
            withFrameMillis { now -> seconds.floatValue = (now - start) / 1000f }
        }
    }
    return seconds
}

private const val FRAME_STEP_MS = 33L

private fun Color.isDark() = luminance() < 0.4f

/**
 * The top bar's background with the theme's ornament behind the title and actions: Gruvbox's
 * colour stripe, the Linux bar's focus line, a blossoming sakura branch, a synthwave sunset.
 * [content] is the bar itself (transparent).
 */
@Composable
fun ThemeTopBarBackground(
    containerColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val preset = LocalAppThemePreset.current
    Box(modifier.background(containerColor)) {
        when (preset) {
            AppThemePreset.GRUVBOX -> Canvas(Modifier.matchParentSize()) { drawGruvboxStripe() }
            AppThemePreset.LINUX -> {
                val line = MaterialTheme.colorScheme.primary
                Canvas(Modifier.matchParentSize()) {
                    val h = 1.5.dp.toPx()
                    drawRect(line.copy(alpha = 0.8f), topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                }
            }
            AppThemePreset.SAKURA -> {
                val dark = containerColor.isDark()
                Canvas(Modifier.matchParentSize()) { drawSakuraBranch(dark) }
            }
            AppThemePreset.SYNTHWAVE -> {
                val clock = rememberDecorationClock(LocalThemeAnimations.current)
                val dark = containerColor.isDark()
                Canvas(Modifier.matchParentSize()) { drawSynthwaveSunset(clock.floatValue, dark) }
            }
            AppThemePreset.CATPPUCCIN -> Canvas(Modifier.matchParentSize()) {
                val h = 2.dp.toPx()
                drawRect(
                    Brush.horizontalGradient(Catppuccin.ribbon),
                    topLeft = Offset(0f, size.height - h),
                    size = Size(size.width, h),
                )
            }
            AppThemePreset.STANDARD -> Unit
        }
        content()
    }
}

/**
 * The screen title the way the theme writes it: plain, with a blinking cursor (Gruvbox), as a shell
 * prompt (Linux), with a hanko stamp (Sakura) or in neon (Synthwave).
 */
@Composable
fun ThemeTitle(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val preset = LocalAppThemePreset.current
    val colors = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        when (preset) {
            AppThemePreset.LINUX -> {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Gruvbox.green.adapt(colors.surface))) { append("λ ") }
                        withStyle(SpanStyle(color = colors.tertiary)) { append("~/") }
                        withStyle(SpanStyle(color = colors.onSurface)) { append(text.lowercase()) }
                        withStyle(SpanStyle(color = colors.primary)) { append(" ❯") }
                    },
                    style = style.copy(fontSize = style.fontSize * 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BlinkingCursor(Modifier.padding(start = 6.dp))
            }
            AppThemePreset.SYNTHWAVE -> Text(
                text = text,
                style = style.copy(
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic,
                    shadow = Shadow(color = colors.primary.copy(alpha = 0.9f), blurRadius = 18f),
                ),
                color = if (colors.surface.isDark()) Color.White else colors.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            else -> {
                Text(text = text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ThemeTitleAccent()
            }
        }
    }
}

/** On a light background the bright gruvbox colours are swapped for their dark counterparts. */
private fun Color.adapt(background: Color): Color =
    if (background.isDark()) this else when (this) {
        Gruvbox.green -> Color(0xFF79740E)
        else -> this
    }

@Composable
private fun BlinkingCursor(modifier: Modifier = Modifier) {
    val blink = if (LocalThemeAnimations.current) {
        rememberInfiniteTransition(label = "cursor").animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = 1060
                    1f at 0
                    1f at 529
                    0f at 530
                    0f at 1059
                },
            ),
            label = "cursorAlpha",
        ).value
    } else {
        1f
    }
    Box(
        modifier
            .size(width = 10.dp, height = 20.dp)
            .alpha(blink)
            .background(MaterialTheme.colorScheme.primary),
    )
}

/** A small mark after the screen title: a blinking cursor or a hanko stamp. */
@Composable
fun ThemeTitleAccent() {
    when (LocalAppThemePreset.current) {
        AppThemePreset.GRUVBOX -> BlinkingCursor(Modifier.padding(start = 6.dp))
        AppThemePreset.SAKURA -> Box(
            Modifier
                .padding(start = 10.dp)
                // The title's line box sits low; lift the stamp to the letters' middle.
                .offset(y = (-3).dp)
                .size(28.dp)
                .background(Sakura.vermilion, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "桜",
                color = Color.White,
                style = TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 18.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                ),
            )
        }
        AppThemePreset.CATPPUCCIN -> Text(
            "ᓚᘏᗢ",
            color = MaterialTheme.colorScheme.primary,
            style = TextStyle(fontSize = 18.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)),
            modifier = Modifier.padding(start = 10.dp).offset(y = (-2).dp),
        )
        else -> Unit
    }
}

/**
 * Drawn under the pages (it shows where a page is see-through — between rows, around covers):
 * sakura petals drifting down.
 */
@Composable
fun ThemeContentBackground(visible: Boolean, modifier: Modifier = Modifier) {
    // Only where it can be seen: under an opaque page (search, settings, the player) the petals
    // redrew the whole window 30 times a second for nothing — the search screen dropped frames.
    if (!visible) return
    when (LocalAppThemePreset.current) {
        AppThemePreset.SAKURA -> if (LocalThemeAnimations.current) SakuraPetals(modifier)
        else -> Unit
    }
}

/** Drawn over the pages (touches pass through): only the Linux tiling-window border. */
@Composable
fun ThemeContentOverlay(modifier: Modifier = Modifier) {
    if (LocalAppThemePreset.current != AppThemePreset.LINUX) return
    val colors = MaterialTheme.colorScheme
    val frame = colors.primary.copy(alpha = if (colors.surface.isDark()) 0.75f else 0.65f)
    Canvas(modifier.fillMaxSize()) {
        // The focused window's border, as a tiling window manager draws it (with a small gap).
        val inset = 3.dp.toPx()
        drawRect(
            color = frame,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

@Composable
private fun SakuraPetals(modifier: Modifier) {
    val petals = remember { List(PETAL_COUNT) { Petal.random(Random(it * 7919 + 13)) } }
    val clock = rememberDecorationClock(true)
    val dark = MaterialTheme.colorScheme.surface.isDark()
    // Paths and colours made once: building a Path per petal on every frame fed the GC 30×/s.
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val shapes = remember(density) { petals.map { petalPath(it.size * density) } }
    val colors = remember(dark) {
        val a = if (dark) 0.62f else 0.78f
        listOf(Sakura.petal.copy(alpha = a), Sakura.petalLight.copy(alpha = a), Sakura.petalDeep.copy(alpha = a))
    }
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val t = clock.floatValue
        val unit = 1.dp.toPx()
        petals.forEachIndexed { i, petal -> drawPetal(petal, shapes[i], colors[petal.tint], t, unit) }
    }
}

// ── Sakura ───────────────────────────────────────────────────────────────────────────────────────

private const val PETAL_COUNT = 9

private class Petal(
    val x: Float,
    val fallSpeed: Float,
    val size: Float,
    val phase: Float,
    val swayFreq: Float,
    val swayAmp: Float,
    val spin: Float,
    val tint: Int,
) {
    companion object {
        fun random(r: Random) = Petal(
            x = r.nextFloat(),
            fallSpeed = 18f + r.nextFloat() * 22f,
            size = 9f + r.nextFloat() * 7f,
            phase = r.nextFloat() * 1000f,
            swayFreq = 0.35f + r.nextFloat() * 0.5f,
            swayAmp = 14f + r.nextFloat() * 22f,
            spin = (if (r.nextBoolean()) 1 else -1) * (20f + r.nextFloat() * 50f),
            tint = r.nextInt(3),
        )
    }
}

private fun DrawScope.drawPetal(p: Petal, path: Path, color: Color, t: Float, unit: Float) {
    val s = p.size * unit
    val travel = size.height + s * 4
    val y = ((p.phase * unit + t * p.fallSpeed * unit) % travel) - s * 2
    val x = p.x * size.width + sin(t * p.swayFreq * 2 * PI.toFloat() + p.phase) * p.swayAmp * unit
    translate(x, y) {
        rotate(t * p.spin + p.phase, pivot = Offset.Zero) {
            drawPath(path, color)
        }
    }
}

/** A sakura petal: rounded, with the notch at its tip; centred on the origin, [s] tall. */
private fun petalPath(s: Float) = Path().apply {
    moveTo(0f, s * 0.5f)
    cubicTo(-s * 0.55f, s * 0.2f, -s * 0.5f, -s * 0.45f, -s * 0.13f, -s * 0.5f)
    lineTo(0f, -s * 0.36f)
    lineTo(s * 0.13f, -s * 0.5f)
    cubicTo(s * 0.5f, -s * 0.45f, s * 0.55f, s * 0.2f, 0f, s * 0.5f)
    close()
}

private fun DrawScope.drawGruvboxStripe() {
    val h = 3.dp.toPx()
    val w = size.width / Gruvbox.stripe.size
    Gruvbox.stripe.forEachIndexed { i, c ->
        drawRect(c, topLeft = Offset(i * w, size.height - h), size = Size(w + 1f, h))
    }
}

/** A blossoming branch reaching in from the top-right corner. */
private fun DrawScope.drawSakuraBranch(dark: Boolean) {
    val u = size.height / 100f
    val right = size.width
    val wood = (if (dark) Color(0xFF8A6068) else Sakura.branch).copy(alpha = if (dark) 0.75f else 0.55f)
    val main = Path().apply {
        moveTo(right + 4 * u, 8 * u)
        cubicTo(right - 40 * u, 14 * u, right - 70 * u, 30 * u, right - 120 * u, 26 * u)
        cubicTo(right - 150 * u, 24 * u, right - 175 * u, 34 * u, right - 205 * u, 46 * u)
    }
    val twig1 = Path().apply {
        moveTo(right - 70 * u, 28 * u)
        cubicTo(right - 80 * u, 44 * u, right - 92 * u, 54 * u, right - 108 * u, 62 * u)
    }
    val twig2 = Path().apply {
        moveTo(right - 150 * u, 25 * u)
        cubicTo(right - 158 * u, 14 * u, right - 168 * u, 8 * u, right - 182 * u, 6 * u)
    }
    drawPath(main, wood, style = Stroke(width = 4 * u, cap = StrokeCap.Round))
    drawPath(twig1, wood, style = Stroke(width = 2.6f * u, cap = StrokeCap.Round))
    drawPath(twig2, wood, style = Stroke(width = 2.2f * u, cap = StrokeCap.Round))
    val alpha = if (dark) 0.85f else 0.9f
    listOf(
        Triple(right - 38 * u, 13 * u, 9f),
        Triple(right - 96 * u, 26 * u, 11f),
        Triple(right - 108 * u, 62 * u, 8f),
        Triple(right - 140 * u, 30 * u, 7f),
        Triple(right - 182 * u, 6 * u, 9f),
        Triple(right - 205 * u, 46 * u, 10f),
    ).forEachIndexed { i, (x, y, r) ->
        drawBlossom(Offset(x, y), r * u, (i * 23f), alpha)
    }
    listOf(Offset(right - 60 * u, 21 * u), Offset(right - 165 * u, 36 * u), Offset(right - 86 * u, 47 * u))
        .forEach { drawCircle(Sakura.petalDeep.copy(alpha = alpha), radius = 2.6f * u, center = it) }
}

private fun DrawScope.drawBlossom(center: Offset, radius: Float, turn: Float, alpha: Float) {
    val petal = petalPath(radius)
    for (k in 0 until 5) {
        rotate(turn + k * 72f, pivot = center) {
            // Base at the centre, notched tip outward — the way a real petal sits on the flower.
            translate(center.x, center.y - radius * 0.55f) {
                drawPath(petal, (if (k % 2 == 0) Sakura.petal else Sakura.petalLight).copy(alpha = alpha))
            }
        }
    }
    drawCircle(Sakura.petalDeep.copy(alpha = alpha), radius = radius * 0.28f, center = center)
    drawCircle(Color(0xFFFFE08A).copy(alpha = alpha), radius = radius * 0.12f, center = center)
}

// ── Synthwave ────────────────────────────────────────────────────────────────────────────────────

/** A striped retro sun sinking behind a neon grid that rolls toward the viewer. */
private fun DrawScope.drawSynthwaveSunset(t: Float, dark: Boolean) {
    val horizon = size.height * 0.62f
    val a = if (dark) 1f else 0.7f
    // Sun on the right, behind the action icons' gap.
    val r = size.height * 0.42f
    val center = Offset(size.width * 0.72f, horizon)
    clipRect(bottom = horizon) {
        drawCircle(
            brush = Brush.verticalGradient(
                listOf(Synthwave.yellow.copy(alpha = 0.55f * a), Synthwave.orange.copy(alpha = 0.5f * a), Synthwave.pink.copy(alpha = 0.55f * a)),
                startY = center.y - r,
                endY = center.y,
            ),
            radius = r,
            center = center,
        )
        // The sun's cut lines, thicker toward the horizon.
        for (k in 0 until 5) {
            val y = center.y - r * (0.12f + k * 0.17f)
            val thick = (5 - k) * 0.9f * (r / 40f)
            drawRect(
                color = (if (dark) Color(0xFF241848) else Color(0xFFF2E7FC)),
                topLeft = Offset(center.x - r, y - thick / 2),
                size = Size(r * 2, thick),
            )
        }
    }
    // Horizon glow and the grid floor.
    drawRect(Synthwave.pink.copy(alpha = 0.7f * a), topLeft = Offset(0f, horizon - 1f), size = Size(size.width, 2f))
    clipRect(top = horizon) {
        val floor = size.height - horizon
        val line = Synthwave.cyan.copy(alpha = 0.45f * a)
        val stroke = 1.dp.toPx()
        // Horizontal lines come closer and spread out as they roll in.
        val phase = (t * 0.6f) % 1f
        for (k in 0 until 6) {
            val p = ((k + phase) / 6f)
            val y = horizon + floor * p * p
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
        }
        val vanishing = size.width * 0.5f
        for (k in -12..12) {
            val xBottom = vanishing + k * size.width / 10f
            drawLine(line, Offset(vanishing + k * size.width / 70f, horizon), Offset(xBottom, size.height), strokeWidth = stroke)
        }
    }
}
