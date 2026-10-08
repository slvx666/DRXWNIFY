/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The picture behind the "Drxwnify · player" widget, in the site's colours (near-black, signal
 * red): the cover blown up, blurred and darkened as the backdrop, a red glow that breathes with the
 * music, film grain, a band of equalizer bars that dance while playing and the progress as a glowing
 * red line. Redrawn with every widget refresh (each second while playing), which is its animation.
 * The controls and text are real views laid over it (crisp, tappable).
 */
internal object ClassicWidgetArt {
    private val RED = Color.rgb(224, 40, 58)
    private val RED_DEEP = Color.rgb(142, 15, 27)
    private val BG = Color.rgb(7, 7, 10)

    private var blurredFor: Bitmap? = null
    private var blurred: Bitmap? = null
    private var accentFor: Bitmap? = null
    private var accent: Int = RED

    /**
     * The cover's accent colour, as AIMP takes it: the most present vivid hue, lifted so it glows on
     * black. No cover (or a grey one) keeps the brand red.
     */
    fun accentOf(cover: Bitmap?): Int {
        if (cover == null) return RED
        if (cover === accentFor) return accent
        accentFor = cover
        accent = runCatching {
            val small = Bitmap.createScaledBitmap(cover, 32, 32, true)
            val weights = FloatArray(24)
            val sums = Array(24) { FloatArray(3) }
            val hsv = FloatArray(3)
            for (y in 0 until 32) for (x in 0 until 32) {
                val c = small.getPixel(x, y)
                Color.colorToHSV(c, hsv)
                if (hsv[1] < 0.28f || hsv[2] < 0.22f) continue
                val bucket = ((hsv[0] / 15f).toInt()).coerceIn(0, 23)
                val w = hsv[1] * hsv[2]
                weights[bucket] += w
                sums[bucket][0] += Color.red(c) * w
                sums[bucket][1] += Color.green(c) * w
                sums[bucket][2] += Color.blue(c) * w
            }
            val best = weights.indices.maxByOrNull { weights[it] } ?: return@runCatching RED
            // Too little colour in the picture: not worth a tint.
            if (weights[best] < 6f) return@runCatching RED
            val w = weights[best]
            val avg = Color.rgb((sums[best][0] / w).toInt(), (sums[best][1] / w).toInt(), (sums[best][2] / w).toInt())
            Color.colorToHSV(avg, hsv)
            hsv[1] = hsv[1].coerceAtLeast(0.6f)
            hsv[2] = hsv[2].coerceIn(0.78f, 0.96f)
            Color.HSVToColor(hsv)
        }.getOrDefault(RED)
        return accent
    }

    private fun darker(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] *= factor
        return Color.HSVToColor(hsv)
    }

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    private val grain: Bitmap by lazy { makeGrain() }

    fun draw(w: Int, h: Int, cover: Bitmap?, isPlaying: Boolean, progress: Float, seed: Int, frame: Long): Bitmap {
        val tint = accentOf(cover)
        val tintDeep = darker(tint, 0.55f)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val radius = min(w, h) * 0.16f
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW) }
        c.clipPath(clip)
        c.drawColor(BG)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Backdrop: the cover, tiny-scaled (a cheap blur), darkened and desaturated a little.
        backdrop(cover)?.let { b ->
            val scale = max(w.toFloat() / b.width, h.toFloat() / b.height) * 1.15f
            val dw = b.width * scale
            val dh = b.height * scale
            p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.75f) })
            p.alpha = 150
            c.drawBitmap(b, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), p)
            p.colorFilter = null
            p.alpha = 255
        }
        // Darkening from the left, where the text sits.
        p.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(Color.argb(235, 7, 7, 10), Color.argb(150, 7, 7, 10), Color.argb(200, 7, 7, 10)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)

        // The red glow, breathing while music plays.
        val breath = if (isPlaying) 0.75f + 0.25f * sin(frame * 0.9f).toFloat() else 0.45f
        p.shader = RadialGradient(
            w * 0.86f, h * 1.05f, max(w, h) * 0.75f,
            intArrayOf(withAlpha(tint, (120 * breath).toInt()), withAlpha(tintDeep, (40 * breath).toInt()), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(
            w * 0.05f, -h * 0.2f, max(w, h) * 0.6f,
            intArrayOf(Color.argb(55, 185, 199, 220), Color.TRANSPARENT), null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        // Equalizer: thin bars along the bottom, each with its own rhythm (seeded by the track).
        val bars = (w / (h * 0.045f + 6f)).toInt().coerceIn(24, 72)
        val gap = w / bars.toFloat()
        val barW = gap * 0.42f
        val base = h * 0.985f
        val rnd = Random(seed)
        val phases = FloatArray(bars) { rnd.nextFloat() * 2f * PI.toFloat() }
        val speeds = FloatArray(bars) { 0.6f + rnd.nextFloat() * 1.6f }
        val shape = FloatArray(bars) { i -> 0.35f + 0.65f * sin(PI * (i + 0.5) / bars).toFloat() }
        for (i in 0 until bars) {
            val level = if (isPlaying) {
                (0.25f + 0.75f * abs(sin(phases[i] + frame * speeds[i]))) * shape[i]
            } else {
                0.12f * shape[i]
            }
            val barH = h * 0.30f * level
            val x = i * gap + (gap - barW) / 2
            val played = (i + 0.5f) / bars <= progress
            p.shader = LinearGradient(0f, base - barH, 0f, base, if (played) tint else Color.argb(90, 238, 242, 248), if (played) tintDeep else Color.argb(20, 238, 242, 248), Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(x, base - barH, x + barW, base), barW / 2, barW / 2, p)
        }
        p.shader = null

        // Progress: a glowing red line on the very bottom.
        val lineH = max(3f, h * 0.022f)
        p.color = Color.argb(45, 238, 242, 248)
        c.drawRect(0f, h - lineH, w.toFloat(), h.toFloat(), p)
        if (progress > 0f) {
            val end = w * progress.coerceIn(0f, 1f)
            p.color = tint
            p.maskFilter = BlurMaskFilter(lineH * 3, BlurMaskFilter.Blur.NORMAL)
            c.drawRect(0f, h - lineH * 2, end, h.toFloat(), p)
            p.maskFilter = null
            c.drawRect(0f, h - lineH, end, h.toFloat(), p)
            p.color = Color.WHITE
            c.drawCircle(end, h - lineH / 2, lineH * 1.1f, p)
        }

        // Grain over everything, as on the site.
        p.shader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        p.alpha = 26
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.alpha = 255

        // A hairline edge.
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1f, h * 0.006f)
        p.color = Color.argb(40, 238, 242, 248)
        c.drawPath(clip, p)
        return bmp
    }

    /** The cover with rounded corners and a soft red halo, for the cover slot. */
    fun cover(cover: Bitmap, size: Int, glow: Int = RED): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val inset = size * 0.085f
        val rect = RectF(inset, inset, size - inset, size - inset)
        val r = size * 0.14f
        // A soft halo in the cover's own colour, spread a little past its edge.
        p.color = withAlpha(glow, 170)
        p.maskFilter = BlurMaskFilter(inset * 1.15f, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(RectF(rect.left - inset * 0.15f, rect.top - inset * 0.15f, rect.right + inset * 0.15f, rect.bottom + inset * 0.15f), r, r, p)
        p.maskFilter = null
        p.color = Color.WHITE
        val side = min(cover.width, cover.height)
        val src = android.graphics.Rect((cover.width - side) / 2, (cover.height - side) / 2, (cover.width + side) / 2, (cover.height + side) / 2)
        c.saveLayer(rect, null)
        c.drawRoundRect(rect, r, r, p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(cover, src, rect, p)
        p.xfermode = null
        c.restore()
        return out
    }

    private fun backdrop(cover: Bitmap?): Bitmap? {
        if (cover == null) return null
        if (cover !== blurredFor) {
            blurredFor = cover
            // Down to 12 px and back up with filtering = a wide, smooth blur, almost for free.
            blurred = runCatching { Bitmap.createScaledBitmap(cover, 12, 12, true) }.getOrNull()
        }
        return blurred
    }

    private fun makeGrain(): Bitmap {
        val size = 96
        val rnd = Random(7)
        val pixels = IntArray(size * size) { val v = rnd.nextInt(256); Color.argb(255, v, v, v) }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
