/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.RemoteViews
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.playback.MusicService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Four odd widgets, each drawn whole as a picture (so they can look like anything) and redrawn every
 * second while music plays, which is their animation:
 *  - [FreakKind.TV]: a haunted CRT showing the cover through VHS glitches. Tap: next "channel".
 *  - [FreakKind.RECEIPT]: a till receipt of today's listening. Tap: open the app.
 *  - [FreakKind.CYBER]: a cyberpunk neural-jack HUD — neon signal bars, bandwidth, upload. Tap: rewind
 *    (previous track).
 *  - [FreakKind.SHOES]: clown shoes sneaking along as the track goes, no background. Tap: play / pause.
 */
enum class FreakKind { TV, RECEIPT, CYBER, SHOES }

abstract class FreakWidget(private val kind: FreakKind) : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Drawn at once, music or not: idle faces need no player.
        FreakWidgets.render(context, kind, appWidgetIds, FreakWidgets.lastState)
        poke(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        FreakWidgets.render(context, kind, intArrayOf(appWidgetId), FreakWidgets.lastState)
    }

    private fun poke(context: Context) {
        if (!MusicService.isRunning) return
        runCatching {
            context.startService(Intent(context, MusicService::class.java).setAction(MusicWidgetReceiver.ACTION_UPDATE_WIDGET))
        }
    }
}

class FreakTvWidget : FreakWidget(FreakKind.TV)
class FreakReceiptWidget : FreakWidget(FreakKind.RECEIPT)
class FreakCyberWidget : FreakWidget(FreakKind.CYBER)
class FreakShoesWidget : FreakWidget(FreakKind.SHOES)

/** What the widgets draw from. */
data class FreakState(
    val title: String,
    val artist: String,
    val cover: Bitmap?,
    val isPlaying: Boolean,
    val isLiked: Boolean,
    /** 0..1 through the track. */
    val progress: Float,
    /** False when no player runs at all (idle faces). */
    val live: Boolean,
    /** "320 kbps · MP3 · VK" when known. */
    val quality: String? = null,
)

object FreakWidgets {
    @Volatile
    var lastState: FreakState? = null
        private set

    private fun provider(kind: FreakKind): Class<out AppWidgetProvider> = when (kind) {
        FreakKind.TV -> FreakTvWidget::class.java
        FreakKind.RECEIPT -> FreakReceiptWidget::class.java
        FreakKind.CYBER -> FreakCyberWidget::class.java
        FreakKind.SHOES -> FreakShoesWidget::class.java
    }

    /** Ids of every placed freak widget, by kind; empty when none is on the home screen. */
    fun placed(context: Context): Map<FreakKind, IntArray> {
        val manager = AppWidgetManager.getInstance(context) ?: return emptyMap()
        return FreakKind.entries.associateWith { kind ->
            runCatching { manager.getAppWidgetIds(ComponentName(context, provider(kind))) }.getOrNull() ?: IntArray(0)
        }.filterValues { it.isNotEmpty() }
    }

    /** Called with the player's state (every second while playing). */
    fun update(context: Context, state: FreakState) {
        lastState = state
        WidgetStats.tick(context, state)
        placed(context).forEach { (kind, ids) -> render(context, kind, ids, state) }
    }

    fun render(context: Context, kind: FreakKind, ids: IntArray, state: FreakState?) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val s = state ?: FreakState("", "", null, isPlaying = false, isLiked = false, progress = 0f, live = false)
        val stats = WidgetStats.snapshot(context)
        val frame = System.currentTimeMillis() / 1000
        ids.forEach { id ->
            val (w, h) = sizePx(context, manager.getAppWidgetOptions(id), kind)
            val bitmap = runCatching {
                when (kind) {
                    FreakKind.TV -> drawTv(context, w, h, s, frame)
                    FreakKind.RECEIPT -> drawReceipt(context, w, h, s, stats)
                    FreakKind.CYBER -> drawCyber(w, h, s, frame)
                    FreakKind.SHOES -> drawShoes(w, h, s, frame)
                }
            }.getOrNull() ?: return@forEach
            val views = RemoteViews(context.packageName, R.layout.widget_freak).apply {
                setImageViewBitmap(R.id.freak_image, bitmap)
                setOnClickPendingIntent(R.id.freak_image, tapIntent(context, kind))
            }
            runCatching { manager.updateAppWidget(id, views) }
        }
    }

    private fun sizePx(context: Context, options: Bundle, kind: FreakKind): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val defaultW = if (kind == FreakKind.TV || kind == FreakKind.CYBER || kind == FreakKind.SHOES) 180 else 110
        val defaultH = if (kind == FreakKind.RECEIPT) 180 else 110
        val wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: defaultW
        val hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: defaultH
        // Kept modest: a widget's pictures share a small budget, and this is redrawn every second.
        val scale = min(density, 2.2f)
        return (wDp * scale).toInt().coerceIn(120, 720) to (hDp * scale).toInt().coerceIn(120, 720)
    }

    private fun tapIntent(context: Context, kind: FreakKind): PendingIntent = when (kind) {
        FreakKind.TV -> broadcast(context, 22, MusicWidgetReceiver.ACTION_NEXT)
        FreakKind.CYBER -> broadcast(context, 26, MusicWidgetReceiver.ACTION_PREVIOUS)
        FreakKind.SHOES -> broadcast(context, 30, MusicWidgetReceiver.ACTION_PLAY_PAUSE)
        FreakKind.RECEIPT -> PendingIntent.getActivity(
            context, 25, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun broadcast(context: Context, code: Int, action: String) = PendingIntent.getBroadcast(
        context, code, Intent(context, MusicWidgetReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ru(context: Context) = context.resources.configuration.locales[0].language == "ru"

    // ── 2. Haunted CRT ───────────────────────────────────────────────────────────────────────────

    private fun drawTv(context: Context, w: Int, h: Int, s: FreakState, frame: Long): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = Random(frame * 31 + s.title.hashCode())
        // Antennas.
        p.color = Color.rgb(40, 40, 44); p.strokeWidth = w * 0.012f
        c.drawLine(w * 0.45f, h * 0.14f, w * 0.30f, 0f, p)
        c.drawLine(w * 0.55f, h * 0.14f, w * 0.72f, h * 0.02f, p)
        // The set: brown plastic, a screen, knobs on the right.
        val body = RectF(0f, h * 0.12f, w.toFloat(), h.toFloat())
        p.shader = LinearGradient(0f, body.top, 0f, body.bottom, Color.rgb(110, 72, 44), Color.rgb(62, 38, 22), Shader.TileMode.CLAMP)
        c.drawRoundRect(body, w * 0.06f, w * 0.06f, p)
        p.shader = null
        val screen = RectF(w * 0.05f, h * 0.18f, w * 0.78f, h * 0.94f)
        p.color = Color.BLACK
        c.drawRoundRect(screen, w * 0.07f, w * 0.07f, p)
        for (i in 0..1) {
            p.color = Color.rgb(30, 30, 30)
            c.drawCircle(w * 0.89f, h * (0.38f + i * 0.28f), w * 0.055f, p)
            p.color = Color.rgb(170, 170, 170)
            val a = (frame % 8) * 0.8f + i
            c.drawLine(w * 0.89f, h * (0.38f + i * 0.28f), w * 0.89f + cos(a) * w * 0.045f, h * (0.38f + i * 0.28f) + sin(a) * w * 0.045f, p)
        }
        c.save()
        val clip = Path().apply { addRoundRect(screen, w * 0.07f, w * 0.07f, Path.Direction.CW) }
        c.clipPath(clip)
        val cover = s.cover
        if (s.live && cover != null) {
            // The picture three times, one per colour, slightly apart: a misaligned tube.
            val shift = (if (s.isPlaying) 3 + rnd.nextInt(5) else 2) * w / 300f
            val dst = RectF(screen)
            val add = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD); isFilterBitmap = true }
            p.color = Color.BLACK; c.drawRect(screen, p)
            listOf(Color.RED to -shift, Color.GREEN to 0f, Color.BLUE to shift).forEach { (color, dx) ->
                add.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY)
                c.drawBitmap(cover, null, RectF(dst.left + dx, dst.top, dst.right + dx, dst.bottom), add)
            }
            // Torn bands: slices of the picture shoved sideways.
            val bands = if (s.isPlaying) 2 + rnd.nextInt(3) else 1
            repeat(bands) {
                val y = screen.top + rnd.nextFloat() * screen.height()
                val bh = screen.height() * (0.02f + rnd.nextFloat() * 0.06f)
                val dx = (rnd.nextFloat() - 0.5f) * w * 0.15f
                val src = Rect(0, ((y - screen.top) / screen.height() * cover.height).toInt(), cover.width, (((y + bh) - screen.top) / screen.height() * cover.height).toInt().coerceAtMost(cover.height))
                if (src.height() > 0) c.drawBitmap(cover, src, RectF(screen.left + dx, y, screen.right + dx, y + bh), null)
            }
        } else {
            // No signal: snow.
            val dot = maxOf(2, w / 160)
            var y = screen.top.toInt()
            while (y < screen.bottom) {
                var x = screen.left.toInt()
                while (x < screen.right) {
                    val v = rnd.nextInt(256)
                    p.color = Color.rgb(v, v, v)
                    c.drawRect(x.toFloat(), y.toFloat(), (x + dot).toFloat(), (y + dot).toFloat(), p)
                    x += dot
                }
                y += dot
            }
        }
        // Scanlines and a vignette of the curved glass.
        p.color = Color.argb(70, 0, 0, 0)
        var line = screen.top
        while (line < screen.bottom) { c.drawRect(screen.left, line, screen.right, line + h / 220f + 1, p); line += h / 70f }
        p.shader = RadialGradient(screen.centerX(), screen.centerY(), screen.width() * 0.7f, Color.TRANSPARENT, Color.argb(170, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(screen, p)
        p.shader = null
        // The VCR's on-screen text.
        val osd = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = screen.height() * 0.11f
            color = Color.rgb(230, 255, 230); setShadowLayer(4f, 0f, 0f, Color.rgb(0, 255, 120))
        }
        val channel = (abs(s.title.hashCode()) % 98) + 1
        val r = ru(context)
        val corner = when {
            !s.live -> if (r) "НЕТ СИГНАЛА" else "NO SIGNAL"
            s.isPlaying -> "PLAY ▶"
            else -> "PAUSE ❚❚"
        }
        c.drawText(corner, screen.left + screen.width() * 0.06f, screen.top + screen.height() * 0.16f, osd)
        if (s.live) {
            osd.textAlign = Paint.Align.RIGHT
            c.drawText("CH %02d".format(channel), screen.right - screen.width() * 0.06f, screen.top + screen.height() * 0.16f, osd)
            osd.textAlign = Paint.Align.LEFT
            osd.textSize = screen.height() * 0.09f
            c.drawText(ellipsize(s.title.uppercase(), osd, screen.width() * 0.88f), screen.left + screen.width() * 0.06f, screen.bottom - screen.height() * 0.08f, osd)
            if (frame % 2 == 0L && s.isPlaying) {
                p.color = Color.RED
                c.drawCircle(screen.right - screen.width() * 0.09f, screen.bottom - screen.height() * 0.11f, screen.height() * 0.035f, p)
            }
        }
        c.restore()
        return bmp
    }

    // ── 4. Receipt ───────────────────────────────────────────────────────────────────────────────

    private fun drawReceipt(context: Context, w: Int, h: Int, s: FreakState, stats: WidgetStats.Snapshot): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = ru(context)
        val paper = Path()
        val tooth = w / 22f
        val top = h * 0.02f
        val bottom = h * 0.98f
        paper.moveTo(w * 0.06f, top)
        var x = w * 0.06f
        var up = true
        while (x < w * 0.94f) { x += tooth; paper.lineTo(x.coerceAtMost(w * 0.94f), if (up) top + tooth * 0.6f else top); up = !up }
        paper.lineTo(w * 0.94f, bottom)
        x = w * 0.94f
        while (x > w * 0.06f) { x -= tooth; paper.lineTo(x.coerceAtLeast(w * 0.06f), if (up) bottom - tooth * 0.6f else bottom); up = !up }
        paper.close()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(90, 0, 0, 0)
        p.maskFilter = BlurMaskFilter(w * 0.02f, BlurMaskFilter.Blur.NORMAL)
        c.save(); c.translate(w * 0.01f, h * 0.01f); c.drawPath(paper, p); c.restore()
        p.maskFilter = null
        p.color = Color.rgb(247, 244, 236)
        c.drawPath(paper, p)

        val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 46); typeface = Typeface.MONOSPACE; textSize = w * 0.05f }
        val left = w * 0.12f
        val right = w * 0.88f
        var y = top + tooth + ink.textSize
        val step = ink.textSize * 1.3f
        fun center(text: String, bold: Boolean = false) {
            ink.isFakeBoldText = bold
            ink.textAlign = Paint.Align.CENTER
            c.drawText(ellipsize(text, ink, right - left), w / 2f, y, ink)
            ink.textAlign = Paint.Align.LEFT
            ink.isFakeBoldText = false
            y += step
        }
        fun row(name: String, value: String) {
            val vw = ink.measureText(value)
            c.drawText(ellipsize(name, ink, right - left - vw - ink.textSize), left, y, ink)
            c.drawText(value, right - vw, y, ink)
            y += step
        }
        fun dashes() { center("-".repeat(((right - left) / ink.measureText("-")).toInt())) }
        center(if (r) "МАГАЗИН «DRXWNIFY»" else "DRXWNIFY MUSIC STORE", bold = true)
        center(SimpleDateFormat("dd.MM.yyyy  HH:mm", Locale.getDefault()).format(Date()))
        dashes()
        val lines = ((bottom - y - step * 6) / step).toInt().coerceAtLeast(1)
        stats.recent.take(lines).forEachIndexed { i, t -> row("${i + 1}.${t.uppercase()}", if (r) "1ШТ" else "1PC") }
        if (stats.recent.isEmpty()) center(if (r) "ПУСТО. СЛУШАЙ!" else "EMPTY. LISTEN!")
        dashes()
        ink.isFakeBoldText = true
        row(if (r) "ИТОГО МИНУТ" else "TOTAL MIN", "${stats.secondsToday / 60}")
        row(if (r) "ТРЕКОВ" else "TRACKS", "${stats.tracksToday}")
        ink.isFakeBoldText = false
        // A barcode of whatever plays now.
        if (y < bottom - step * 2.5f) {
            val code = Random((s.title + s.artist).hashCode())
            var bx = left
            val bTop = y - ink.textSize * 0.6f
            val bBottom = (bTop + step * 1.4f).coerceAtMost(bottom - step * 1.3f)
            p.color = Color.rgb(30, 30, 34)
            while (bx < right) {
                val bw = (1 + code.nextInt(3)) * w / 260f
                c.drawRect(bx, bTop, bx + bw, bBottom, p)
                bx += bw + (1 + code.nextInt(3)) * w / 260f
            }
            y = bBottom + step
        }
        center(if (r) "СПАСИБО ЗА ПРОСЛУШКУ!" else "THANKS FOR LISTENING!", bold = true)
        return bmp
    }

    // ── 6. Cyberpunk neural jack ─────────────────────────────────────────────────────────────────

    private fun drawCyber(w: Int, h: Int, s: FreakState, frame: Long): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val magenta = Color.rgb(255, 32, 160)
        val cyan = Color.rgb(0, 240, 255)
        val yellow = Color.rgb(250, 235, 40)
        val rnd = Random(frame * 17 + s.title.hashCode())
        // A plate with cut corners, not a rounded card.
        val cut = min(w, h) * 0.12f
        val plate = Path().apply {
            moveTo(cut, 0f); lineTo(w.toFloat(), 0f); lineTo(w.toFloat(), h - cut)
            lineTo(w - cut, h.toFloat()); lineTo(0f, h.toFloat()); lineTo(0f, cut); close()
        }
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(14, 4, 26), Color.rgb(4, 10, 22), Shader.TileMode.CLAMP)
        c.drawPath(plate, p)
        p.shader = null
        // A grid fading into the dark, like a floor of the net.
        p.color = Color.argb(40, 0, 240, 255); p.strokeWidth = 1f
        var gx = 0f
        while (gx < w) { c.drawLine(gx, h * 0.55f, gx + (gx - w / 2f) * 0.6f, h.toFloat(), p); gx += w / 14f }
        var gy = h * 0.55f
        var gap = h / 40f
        while (gy < h) { c.drawLine(0f, gy, w.toFloat(), gy, p); gy += gap; gap *= 1.35f }
        // Neon rim.
        p.style = Paint.Style.STROKE
        p.strokeWidth = min(w, h) * 0.012f
        p.color = magenta
        p.maskFilter = BlurMaskFilter(min(w, h) * 0.02f, BlurMaskFilter.Blur.SOLID)
        c.drawPath(plate, p)
        p.maskFilter = null
        p.style = Paint.Style.FILL

        val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        val pad = w * 0.06f
        // Header: a status line with a blinking cursor.
        mono.textSize = h * 0.075f
        mono.color = yellow
        val status = when {
            !s.live -> "// NO LINK"
            s.isPlaying -> "// NEURAL JACK: ONLINE"
            else -> "// LINK HOLD"
        }
        c.drawText(status + if (frame % 2 == 0L) "_" else "", cut * 0.9f, h * 0.13f, mono)
        // The title as a glitching payload: drawn in cyan and magenta a hair apart.
        val title = if (s.live) s.title.uppercase() else "JACK IN TO PLAY"
        mono.textSize = h * 0.12f
        val titleText = ellipsize(title, mono, w - pad * 2)
        val jitter = if (s.isPlaying && rnd.nextInt(3) == 0) h * 0.012f else h * 0.004f
        mono.color = Color.argb(200, 255, 32, 160)
        c.drawText(titleText, pad - jitter, h * 0.3f, mono)
        mono.color = Color.argb(200, 0, 240, 255)
        c.drawText(titleText, pad + jitter, h * 0.3f + jitter / 2, mono)
        mono.color = Color.WHITE
        c.drawText(titleText, pad, h * 0.3f, mono)
        mono.textSize = h * 0.07f
        mono.color = Color.argb(220, 0, 240, 255)
        c.drawText(ellipsize("SRC> " + s.artist.uppercase(), mono, w - pad * 2), pad, h * 0.4f, mono)

        // Signal bars: an analyser that moves while music plays.
        val bars = 18
        val barArea = RectF(pad, h * 0.46f, w - pad, h * 0.72f)
        val bw = barArea.width() / bars
        for (i in 0 until bars) {
            val level = if (s.isPlaying) {
                val base = 0.25f + 0.5f * abs(sin((i * 0.7f + frame * 1.3f).toDouble())).toFloat()
                (base + rnd.nextFloat() * 0.3f).coerceAtMost(1f)
            } else 0.06f
            val top = barArea.bottom - barArea.height() * level
            p.shader = LinearGradient(0f, barArea.bottom, 0f, barArea.top, cyan, magenta, Shader.TileMode.CLAMP)
            c.drawRect(barArea.left + i * bw + bw * 0.15f, top, barArea.left + (i + 1) * bw - bw * 0.15f, barArea.bottom, p)
        }
        p.shader = null
        // Bandwidth (the real bitrate) and upload (how far into the track).
        mono.textSize = h * 0.065f
        mono.color = yellow
        val bandwidth = s.quality?.substringBefore(" ·")?.uppercase() ?: "--- KBPS"
        c.drawText("BANDWIDTH $bandwidth", pad, h * 0.81f, mono)
        val up = (s.progress * 100).toInt().coerceIn(0, 100)
        val upLabel = "UPLOAD $up%"
        c.drawText(upLabel, w - pad - mono.measureText(upLabel), h * 0.81f, mono)
        // Segmented progress.
        val segs = 24
        val segW = (w - pad * 2) / segs
        for (i in 0 until segs) {
            p.color = if (i < (s.progress * segs).toInt()) magenta else Color.argb(60, 255, 32, 160)
            c.drawRect(pad + i * segW + segW * 0.12f, h * 0.85f, pad + (i + 1) * segW - segW * 0.12f, h * 0.9f, p)
        }
        // Random hex noise in the corner, and scanlines over everything.
        mono.textSize = h * 0.045f
        mono.color = Color.argb(120, 0, 240, 255)
        val hex = (0 until 4).joinToString(" ") { "%02X".format(rnd.nextInt(256)) }
        c.drawText("0x$hex", w - pad - mono.measureText("0x$hex"), h * 0.96f, mono)
        p.color = Color.argb(35, 0, 0, 0)
        var line = 0f
        while (line < h) { c.drawRect(0f, line, w.toFloat(), line + 1.5f, p); line += 4f }
        return bmp
    }

    // ── Clown shoes ──────────────────────────────────────────────────────────────────────────────

    private fun drawShoes(w: Int, h: Int, s: FreakState, frame: Long): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val side = min(w, h).toFloat()
        // The pair walks across with the track; each frame one shoe is up, the other down.
        val walking = s.isPlaying
        val pairX = w * 0.14f + (w * 0.62f) * (if (s.live) s.progress else 0.4f)
        val groundY = h * 0.86f
        val shoeLen = side * 0.42f
        val leftUp = walking && frame % 2 == 0L
        val rightUp = walking && frame % 2 == 1L
        fun shoe(x: Float, lifted: Boolean, back: Boolean) {
            c.save()
            val lift = if (lifted) side * 0.07f else 0f
            c.translate(x, groundY - lift)
            if (lifted) c.rotate(-8f, 0f, 0f)
            val tone = if (back) 0.82f else 1f
            // Shadow on the floor.
            if (!lifted) {
                p.color = Color.argb(70, 0, 0, 0)
                c.drawOval(RectF(-shoeLen * 0.15f, -side * 0.01f, shoeLen * 0.95f, side * 0.04f), p)
            }
            // The sole and the huge round toe.
            p.color = Color.rgb((210 * tone).toInt(), (30 * tone).toInt(), (40 * tone).toInt())
            c.drawRoundRect(RectF(-shoeLen * 0.1f, -side * 0.13f, shoeLen * 0.55f, 0f), side * 0.05f, side * 0.05f, p)
            c.drawOval(RectF(shoeLen * 0.35f, -side * 0.18f, shoeLen, side * 0.005f), p)
            p.color = Color.argb(110, 255, 255, 255)
            c.drawOval(RectF(shoeLen * 0.55f, -side * 0.155f, shoeLen * 0.8f, -side * 0.1f), p)
            p.color = Color.rgb(250, 240, 230)
            c.drawRect(-shoeLen * 0.1f, -side * 0.02f, shoeLen * 0.97f, 0f, p)
            // Ankle with a striped sock, and the yellow pompom on top.
            p.color = Color.rgb(60, 90, 200)
            c.drawRect(shoeLen * 0.02f, -side * 0.3f, shoeLen * 0.2f, -side * 0.12f, p)
            p.color = Color.WHITE
            for (k in 0..2) c.drawRect(shoeLen * 0.02f, -side * (0.28f - k * 0.06f), shoeLen * 0.2f, -side * (0.25f - k * 0.06f), p)
            p.color = Color.rgb(255, 214, 40)
            c.drawCircle(shoeLen * 0.42f, -side * 0.17f, side * 0.045f, p)
            c.restore()
        }
        shoe(pairX - shoeLen * 0.25f, rightUp, back = true)
        shoe(pairX + shoeLen * 0.2f, leftUp, back = false)
        // Dust puffs where a foot lands, and a sound effect.
        if (walking) {
            p.color = Color.argb(140, 230, 230, 230)
            val puffX = if (leftUp) pairX - shoeLen * 0.3f else pairX + shoeLen * 0.15f
            for (k in 0..2) c.drawCircle(puffX - k * side * 0.04f, groundY - side * 0.01f - k * side * 0.015f, side * (0.025f - k * 0.005f), p)
        }
        return bmp
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun ellipsize(text: String, paint: Paint, width: Float): String =
        android.text.TextUtils.ellipsize(text, TextPaint(paint), width, android.text.TextUtils.TruncateAt.END).toString()

}

/** Today's listening, for the pet, the receipt and the eye. */
object WidgetStats {
    data class Snapshot(val secondsToday: Long, val tracksToday: Int, val recent: List<String>)

    private var prefs: SharedPreferences? = null
    private var lastTickMs = 0L
    private var lastTitle: String? = null

    private fun store(context: Context) =
        prefs ?: context.getSharedPreferences("freak_widget_stats", Context.MODE_PRIVATE).also { prefs = it }

    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun tick(context: Context, state: FreakState) {
        val p = store(context)
        val now = System.currentTimeMillis()
        val editor = p.edit()
        if (p.getString("day", null) != today()) {
            editor.putString("day", today()).putLong("seconds", 0).putInt("tracks", 0).putString("recent", "")
        }
        if (state.isPlaying && lastTickMs > 0) {
            val delta = ((now - lastTickMs) / 1000).coerceIn(0, 5)
            editor.putLong("seconds", p.getLong("seconds", 0) + delta)
        }
        lastTickMs = if (state.isPlaying) now else 0L
        val label = listOf(state.artist, state.title).filter { it.isNotBlank() }.joinToString(" - ")
        if (state.isPlaying && label.isNotBlank() && label != lastTitle) {
            lastTitle = label
            val recent = (listOf(label) + p.getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() && it != label }).take(12)
            editor.putInt("tracks", p.getInt("tracks", 0) + 1).putString("recent", recent.joinToString("\n"))
        }
        editor.apply()
    }

    fun snapshot(context: Context): Snapshot {
        val p = store(context)
        if (p.getString("day", null) != today()) return Snapshot(0, 0, emptyList())
        return Snapshot(
            p.getLong("seconds", 0),
            p.getInt("tracks", 0),
            p.getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() },
        )
    }
}
