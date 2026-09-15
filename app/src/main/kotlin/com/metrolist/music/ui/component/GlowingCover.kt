/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Large album/playlist cover with a soft glow around it (see [coverGlow]). */
@Composable
fun GlowingCover(
    url: String?,
    size: Dp,
    shape: Shape,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = url,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .coverGlow(url)
            .size(size)
            .clip(shape),
    )
}

/** How far the glow spreads past the cover edge, and its strength: a faint aura, barely noticeable. */
private val GlowSpread = 32.dp
private const val GLOW_ALPHA = 0.16f
private const val SAMPLE_PX = 64

private val colorCache = ConcurrentHashMap<String, Color>()

/** Blurred rounded-rect masks keyed by pixel size: shared by every cover of the same size. */
private val maskCache = object : LruCache<Long, ImageBitmap>(6) {}

/**
 * Soft single-color glow behind a large cover. The color is one picked from the cover itself (its
 * accent: vibrant → muted → dominant swatch), lifted so it never ends up as a dark, invisible glow.
 * The glow shape is a blurred rounded rectangle rendered once per size and tinted at draw time,
 * so it's uniform along every edge. Pass several [models] for 2×2 mosaic covers.
 * Apply before `clip`/`size`.
 */
@Composable
fun Modifier.coverGlow(vararg models: Any?): Modifier {
    val context = LocalContext.current
    val sources = models.filterNotNull().map { it.toString() }.take(4)
    val key = sources.joinToString("|")
    var glowColor by remember(key) { mutableStateOf(colorCache[key]) }
    val alpha by animateFloatAsState(
        targetValue = if (glowColor != null) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "coverGlowAlpha",
    )
    LaunchedEffect(key) {
        if (sources.isEmpty() || glowColor != null) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        val bitmaps = sources.mapNotNull { src ->
            val request = ImageRequest.Builder(context)
                .data(src)
                .size(Size(SAMPLE_PX, SAMPLE_PX))
                .allowHardware(false)
                .build()
            (loader.execute(request) as? SuccessResult)?.image?.toBitmap()
        }
        if (bitmaps.isEmpty()) return@LaunchedEffect
        val color = withContext(Dispatchers.Default) { runCatching { pickGlowColor(bitmaps) }.getOrNull() }
        if (color != null) {
            colorCache[key] = color
            glowColor = color
        }
    }
    return this.drawWithCache {
        val spread = GlowSpread.toPx()
        // The bitmap margin is wider than the blur radius so the soft falloff is never cut off.
        val pad = spread * 2f
        val mask = glowMask(size.width.toInt(), size.height.toInt(), pad.toInt(), spread, corner = 3.dp.toPx())
        onDrawBehind {
            val color = glowColor ?: return@onDrawBehind
            if (alpha <= 0f) return@onDrawBehind
            drawImage(
                image = mask,
                topLeft = Offset(-pad.toInt().toFloat(), -pad.toInt().toFloat()),
                alpha = GLOW_ALPHA * alpha,
                colorFilter = ColorFilter.tint(color, BlendMode.SrcIn),
            )
        }
    }
}

private fun glowMask(width: Int, height: Int, pad: Int, blurRadius: Float, corner: Float): ImageBitmap {
    val key = (width.toLong() shl 42) or (height.toLong() shl 21) or pad.toLong()
    maskCache.get(key)?.let { return it }
    val w = (width + 2 * pad).coerceAtLeast(1)
    val h = (height + 2 * pad).coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        maskFilter = BlurMaskFilter(blurRadius.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
    }
    Canvas(bitmap).drawRoundRect(
        RectF(pad.toFloat(), pad.toFloat(), (pad + width).toFloat(), (pad + height).toFloat()),
        corner, corner, paint,
    )
    return bitmap.asImageBitmap().also { maskCache.put(key, it) }
}

/**
 * One color from the cover(s): the most "accent" swatch available, falling back to the dominant
 * one (so a mostly dark cover glows with its own tone), then lifted to a visible lightness.
 */
private fun pickGlowColor(bitmaps: List<Bitmap>): Color {
    val source = if (bitmaps.size == 1) bitmaps[0] else {
        val half = SAMPLE_PX / 2
        Bitmap.createBitmap(SAMPLE_PX, SAMPLE_PX, Bitmap.Config.ARGB_8888).also { out ->
            val canvas = Canvas(out)
            bitmaps.forEachIndexed { i, b ->
                val x = (i % 2) * half
                val y = (i / 2) * half
                canvas.drawBitmap(b, null, Rect(x, y, x + half, y + half), null)
            }
        }
    }
    val palette = Palette.from(source).maximumColorCount(16).generate()
    val total = palette.swatches.sumOf { it.population }.coerceAtLeast(1)
    // An accent only counts if it's actually visible on the cover (not a few stray pixels).
    fun Palette.Swatch?.visible() = this?.takeIf { it.population >= total * 0.03f }
    val dominant = palette.dominantSwatch
    // A mostly dark cover glows with its own dark tone (lifted below), not with a small bright detail.
    val darkDominates = dominant != null && dominant.hsl[2] < 0.25f && dominant.population >= total * 0.6f
    val swatch = (if (darkDominates) dominant else null)
        ?: palette.vibrantSwatch.visible()
        ?: palette.lightVibrantSwatch.visible()
        ?: palette.mutedSwatch.visible()
        ?: palette.lightMutedSwatch.visible()
        ?: dominant
        ?: return Color.White
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(swatch.rgb, hsl)
    hsl[1] = (hsl[1] * 1.1f).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceIn(0.5f, 0.72f)
    return Color(ColorUtils.HSLToColor(hsl))
}
