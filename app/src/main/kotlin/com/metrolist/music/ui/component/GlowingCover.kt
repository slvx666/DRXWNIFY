/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Album/playlist cover with a soft glow around it. The glow is not one flat color: each part of the
 * perimeter (corners and edge midpoints) glows with the color of the cover right at that spot.
 * Colors are sampled once from a tiny downscaled copy, so drawing costs a handful of gradients.
 */
@Composable
fun GlowingCover(
    url: String?,
    size: Dp,
    shape: Shape,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var glow by remember(url) { mutableStateOf<List<Color>?>(null) }
    val glowAlpha by animateFloatAsState(
        targetValue = if (glow != null) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "coverGlow",
    )

    AsyncImage(
        model = url,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        onSuccess = { state ->
            if (glow == null) {
                val bitmap = state.result.image.toBitmap()
                scope.launch {
                    glow = withContext(Dispatchers.Default) { runCatching { perimeterColors(bitmap) }.getOrNull() }
                }
            }
        },
        modifier = modifier
            .drawBehind {
                val colors = glow ?: return@drawBehind
                if (glowAlpha <= 0f) return@drawBehind
                val w = this.size.width
                val h = this.size.height
                val radius = minOf(w, h) * 0.34f
                // Order matches perimeterColors(): TL, T, TR, R, BR, B, BL, L.
                val points = listOf(
                    Offset(0f, 0f), Offset(w / 2, 0f), Offset(w, 0f), Offset(w, h / 2),
                    Offset(w, h), Offset(w / 2, h), Offset(0f, h), Offset(0f, h / 2),
                )
                points.forEachIndexed { i, p ->
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(colors[i].copy(alpha = 0.5f * glowAlpha), Color.Transparent),
                            center = p,
                            radius = radius,
                        ),
                        radius = radius,
                        center = p,
                    )
                }
            }
            .size(size)
            .clip(shape),
    )
}

/** Average colors of 8 perimeter zones (TL, T, TR, R, BR, B, BL, L) of the image. */
private fun perimeterColors(source: Bitmap): List<Color> {
    // Hardware bitmaps can't be read pixel by pixel; copy to a software one first.
    val readable = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
    val n = 12
    val small = Bitmap.createScaledBitmap(readable, n, n, true)
    val b = n / 3 // zone thickness
    fun avg(x0: Int, y0: Int, x1: Int, y1: Int): Color {
        var r = 0L
        var g = 0L
        var bl = 0L
        var count = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = small.getPixel(x, y)
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            bl += c and 0xFF
            count++
        }
        return Color(red = (r / count).toInt(), green = (g / count).toInt(), blue = (bl / count).toInt())
    }
    val mid0 = b
    val mid1 = n - b
    val colors = listOf(
        avg(0, 0, b, b), avg(mid0, 0, mid1, b), avg(n - b, 0, n, b), avg(n - b, mid0, n, mid1),
        avg(n - b, n - b, n, n), avg(mid0, n - b, mid1, n), avg(0, n - b, b, n), avg(0, mid0, b, mid1),
    )
    if (small !== readable) small.recycle()
    if (readable !== source) readable.recycle()
    return colors
}
