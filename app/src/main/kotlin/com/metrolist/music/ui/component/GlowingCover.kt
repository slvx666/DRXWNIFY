/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import kotlin.math.roundToInt

/** Large album/playlist cover with an ambient glow behind it (see [coverGlow]). */
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

// Glow geometry: the cover occupies the middle COVER_PX of a GLOW_PX canvas; the blur spreads its
// colors into the transparent margin, so the glow follows the cover's own colors along every edge
// and fades out smoothly (like YouTube's ambient mode), instead of a flat single-color shadow.
private const val COVER_PX = 40
private const val MARGIN_PX = 14
private const val GLOW_PX = COVER_PX + 2 * MARGIN_PX
private const val BLUR_RADIUS = 5
private const val GLOW_ALPHA = 0.75f

private val glowCache = ConcurrentHashMap<String, ImageBitmap>()

/**
 * Draws a soft ambient glow behind a large cover, built from the cover itself: a tiny copy is
 * blurred once on a background thread (a 68×68 bitmap, cached per URL) and then drawn scaled up
 * behind the element. Pass several [models] for 2×2 mosaic covers. Apply before `clip`/`size`.
 */
@Composable
fun Modifier.coverGlow(vararg models: Any?): Modifier {
    val context = LocalContext.current
    val sources = models.filterNotNull().map { it.toString() }.take(4)
    val key = sources.joinToString("|")
    var glow by remember(key) { mutableStateOf(glowCache[key]) }
    val alpha by animateFloatAsState(
        targetValue = if (glow != null) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "coverGlowAlpha",
    )
    LaunchedEffect(key) {
        if (sources.isEmpty() || glow != null) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        val tiles = sources.map { src ->
            val request = ImageRequest.Builder(context)
                .data(src)
                .size(Size(COVER_PX, COVER_PX))
                .allowHardware(false)
                .build()
            (loader.execute(request) as? SuccessResult)?.image?.toBitmap()
        }
        if (tiles.all { it == null }) return@LaunchedEffect
        val built = withContext(Dispatchers.Default) { runCatching { buildGlow(tiles) }.getOrNull() }
        if (built != null) {
            glowCache[key] = built
            glow = built
        }
    }
    return this.drawBehind {
        val image = glow ?: return@drawBehind
        if (alpha <= 0f) return@drawBehind
        val scale = size.width / COVER_PX
        val glowW = (GLOW_PX * scale)
        val glowH = (GLOW_PX * (size.height / COVER_PX))
        val offset = Offset((size.width - glowW) / 2f, (size.height - glowH) / 2f)
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
            dstSize = IntSize(glowW.roundToInt(), glowH.roundToInt()),
            alpha = GLOW_ALPHA * alpha,
            filterQuality = FilterQuality.Low,
        )
    }
}

private fun buildGlow(tiles: List<Bitmap?>): ImageBitmap {
    val canvasBitmap = Bitmap.createBitmap(GLOW_PX, GLOW_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(canvasBitmap)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    if (tiles.size == 1) {
        tiles[0]?.let { canvas.drawBitmap(it, null, Rect(MARGIN_PX, MARGIN_PX, MARGIN_PX + COVER_PX, MARGIN_PX + COVER_PX), paint) }
    } else {
        val half = COVER_PX / 2
        tiles.forEachIndexed { i, tile ->
            tile ?: return@forEachIndexed
            val x = MARGIN_PX + (i % 2) * half
            val y = MARGIN_PX + (i / 2) * half
            canvas.drawBitmap(tile, null, Rect(x, y, x + half, y + half), paint)
        }
    }

    val n = GLOW_PX
    val pixels = IntArray(n * n)
    canvasBitmap.getPixels(pixels, 0, n, 0, 0, n, n)
    // Premultiplied channels so transparent margin pixels don't darken the blurred colors.
    val a = FloatArray(n * n)
    val r = FloatArray(n * n)
    val g = FloatArray(n * n)
    val b = FloatArray(n * n)
    for (i in pixels.indices) {
        val c = pixels[i]
        val alpha = ((c ushr 24) and 0xFF) / 255f
        a[i] = alpha
        r[i] = ((c shr 16) and 0xFF) / 255f * alpha
        g[i] = ((c shr 8) and 0xFF) / 255f * alpha
        b[i] = (c and 0xFF) / 255f * alpha
    }
    // Three box-blur passes ≈ a gaussian blur.
    repeat(3) {
        for (ch in arrayOf(a, r, g, b)) boxBlur(ch, n, BLUR_RADIUS)
    }
    for (i in pixels.indices) {
        val alpha = a[i].coerceIn(0f, 1f)
        if (alpha <= 0.002f) {
            pixels[i] = 0
            continue
        }
        // Slight saturation/brightness lift so dark or muted covers still read as a glow.
        var rr = (r[i] / alpha)
        var gg = (g[i] / alpha)
        var bb = (b[i] / alpha)
        val lum = 0.299f * rr + 0.587f * gg + 0.114f * bb
        rr = (lum + (rr - lum) * 1.25f) * 1.08f
        gg = (lum + (gg - lum) * 1.25f) * 1.08f
        bb = (lum + (bb - lum) * 1.25f) * 1.08f
        pixels[i] = ((alpha * 255).roundToInt() shl 24) or
            ((rr.coerceIn(0f, 1f) * 255).roundToInt() shl 16) or
            ((gg.coerceIn(0f, 1f) * 255).roundToInt() shl 8) or
            (bb.coerceIn(0f, 1f) * 255).roundToInt()
    }
    canvasBitmap.setPixels(pixels, 0, n, 0, 0, n, n)
    return canvasBitmap.asImageBitmap()
}

/** Separable box blur (horizontal then vertical); pixels outside the canvas count as transparent. */
private fun boxBlur(ch: FloatArray, n: Int, radius: Int) {
    val tmp = FloatArray(n)
    val window = (2 * radius + 1).toFloat()
    for (y in 0 until n) {
        val row = y * n
        var sum = 0f
        for (x in -radius..radius) if (x in 0 until n) sum += ch[row + x]
        for (x in 0 until n) {
            tmp[x] = sum / window
            val out = x - radius
            val inn = x + radius + 1
            if (out >= 0) sum -= ch[row + out]
            if (inn < n) sum += ch[row + inn]
        }
        System.arraycopy(tmp, 0, ch, row, n)
    }
    for (x in 0 until n) {
        var sum = 0f
        for (y in -radius..radius) if (y in 0 until n) sum += ch[y * n + x]
        for (y in 0 until n) {
            tmp[y] = sum / window
            val out = y - radius
            val inn = y + radius + 1
            if (out >= 0) sum -= ch[out * n + x]
            if (inn < n) sum += ch[inn * n + x]
        }
        for (y in 0 until n) ch[y * n + x] = tmp[y]
    }
}
