/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.widget.RemoteViews
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetrolistWidgetManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase
) {
    private val imageLoader by lazy {
        ImageLoader.Builder(context)
            .crossfade(false)
            .build()
    }

    /**
     * Renders are serialized: [updateWidgets] now runs off the main thread and can be
     * invoked from the periodic refresh loop and from player events at the same time,
     * which would otherwise race on the bitmap caches below.
     */
    private val renderMutex = Mutex()

    // Cache for album art to avoid reloading. The *derived* bitmaps are cached too —
    // rounding/circle-cropping allocates two ARGB_8888 bitmaps and runs a Canvas pass,
    // which used to happen on every single refresh tick.
    private var cachedArtworkUri: String? = null
    private var cachedAlbumArt: Bitmap? = null
    private var cachedRoundedAlbumArt: Bitmap? = null
    private var cachedCircularAlbumArt: Bitmap? = null

    // The launcher-icon fallbacks are constant; building them meant a PackageManager
    // lookup plus a 300x300 draw per render.
    private val defaultRoundedIcon: Bitmap by lazy { getRoundedDefaultIcon(DEFAULT_CORNER_RADIUS) }
    private val defaultCircularIcon: Bitmap by lazy { getCircularDefaultIcon() }

    suspend fun updateWidgets(
        title: String,
        artist: String,
        artworkUri: String?,
        isPlaying: Boolean,
        isLiked: Boolean,
        duration: Long = 0,
        currentPosition: Long = 0,
        mediaId: String? = null,
    ) {
        renderMutex.withLock {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return

            val componentName = ComponentName(context, MusicWidgetReceiver::class.java)
            val turntableComponentName = ComponentName(context, TurntableWidgetReceiver::class.java)
            val widgetIds = runCatching { appWidgetManager.getAppWidgetIds(componentName) }
                .getOrNull() ?: IntArray(0)
            val turntableWidgetIds = runCatching { appWidgetManager.getAppWidgetIds(turntableComponentName) }
                .getOrNull() ?: IntArray(0)

            // Nothing on the home screen — skip artwork decoding and all the binder traffic
            // below. The refresh loop runs for the whole playback session, so this is the
            // common case for most users.
            val radioIds = runCatching { appWidgetManager.getAppWidgetIds(ComponentName(context, MeldRadioWidget::class.java)) }
                .getOrNull() ?: IntArray(0)
            val classicIds = runCatching { appWidgetManager.getAppWidgetIds(ComponentName(context, MeldPlayerClassicWidget::class.java)) }
                .getOrNull() ?: IntArray(0)
            val squareIds = runCatching { appWidgetManager.getAppWidgetIds(ComponentName(context, MeldSquareWidget::class.java)) }
                .getOrNull() ?: IntArray(0)
            val miniIds = runCatching { appWidgetManager.getAppWidgetIds(ComponentName(context, MeldMiniWidget::class.java)) }
                .getOrNull() ?: IntArray(0)
            val freaks = FreakWidgets.placed(context)
            if (widgetIds.isEmpty() && turntableWidgetIds.isEmpty() && radioIds.isEmpty() && classicIds.isEmpty() &&
                squareIds.isEmpty() && miniIds.isEmpty() && freaks.isEmpty()
            ) return

            // Reload album art only when the track actually changed.
            if (artworkUri != cachedArtworkUri || (artworkUri != null && cachedAlbumArt == null)) {
                val albumArt = artworkUri?.let { loadAlbumArt(it, ARTWORK_SIZE) }
                cachedArtworkUri = artworkUri
                cachedAlbumArt = albumArt
                cachedRoundedAlbumArt = albumArt?.let { getRoundedCornerBitmap(it, DEFAULT_CORNER_RADIUS) }
                cachedCircularAlbumArt = albumArt?.let { getCircularBitmap(it) }
            }

            val roundedAlbumArt = cachedRoundedAlbumArt ?: defaultRoundedIcon
            val circularAlbumArt = cachedCircularAlbumArt ?: defaultCircularIcon

            // Update main music player widgets
            widgetIds.forEach { widgetId ->
                val options = appWidgetManager.getAppWidgetOptions(widgetId)
                val views = createRemoteViewsForSize(
                    options,
                    title,
                    artist,
                    roundedAlbumArt,
                    isPlaying,
                    isLiked,
                    duration,
                    currentPosition
                )
                runCatching { appWidgetManager.updateAppWidget(widgetId, views) }
            }

            val meld = MeldFace(
                title = title,
                artist = artist,
                cover = roundedAlbumArt,
                isPlaying = isPlaying,
                isLiked = isLiked,
                progress = if (duration > 0) (currentPosition * 1000 / duration).toInt().coerceIn(0, 1000) else 0,
                quality = if (radioIds.isNotEmpty() || classicIds.isNotEmpty() || FreakKind.CYBER in freaks) mediaId?.let { qualityLine(it) } else null,
                rawCover = cachedAlbumArt,
                seed = title.hashCode() * 31 + artist.hashCode(),
            )
            radioIds.forEach { id -> runCatching { appWidgetManager.updateAppWidget(id, responsive(appWidgetManager, id, radioFaces(meld))) } }
            classicIds.forEach { id -> runCatching { appWidgetManager.updateAppWidget(id, responsive(appWidgetManager, id, classicFaces(meld, appWidgetManager.getAppWidgetOptions(id)))) } }
            if (squareIds.isNotEmpty()) {
                val faces = squareFaces(meld, duration, currentPosition)
                squareIds.forEach { id -> runCatching { appWidgetManager.updateAppWidget(id, responsive(appWidgetManager, id, faces)) } }
            }
            miniIds.forEach { id ->
                val signature = "${meld.title}|${meld.artist}|${meld.isPlaying}|${meld.cover.generationId}"
                if (miniRendered[id] == signature) {
                    // Same track and state: only the progress moves. A full update every second
                    // started the scrolling "title · artist" line over before it got anywhere.
                    val progress = RemoteViews(context.packageName, R.layout.widget_mini).apply {
                        setProgressBar(R.id.mini_progress, 1000, meld.progress, false)
                    }
                    runCatching { appWidgetManager.partiallyUpdateAppWidget(id, progress) }
                } else {
                    miniRendered[id] = signature
                    runCatching { appWidgetManager.updateAppWidget(id, miniFaces(meld, appWidgetManager.getAppWidgetOptions(id)).first().views) }
                }
            }
            if (freaks.isNotEmpty()) {
                FreakWidgets.update(
                    context,
                    FreakState(
                        title = title,
                        artist = artist,
                        cover = cachedAlbumArt,
                        isPlaying = isPlaying,
                        isLiked = isLiked,
                        progress = if (duration > 0) (currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        live = true,
                        quality = meld.quality?.toString(),
                    ),
                )
            }

            // Update turntable widgets
            if (turntableWidgetIds.isNotEmpty()) {
                val turntableViews = createTurntableRemoteViews(
                    circularAlbumArt,
                    isPlaying,
                    isLiked
                )
                turntableWidgetIds.forEach { widgetId ->
                    runCatching { appWidgetManager.updateAppWidget(widgetId, turntableViews) }
                }
            }
        }
    }

    private fun createRemoteViewsForSize(
        options: Bundle,
        title: String,
        artist: String,
        albumArt: Bitmap,
        isPlaying: Boolean,
        isLiked: Boolean,
        duration: Long,
        currentPosition: Long
    ): RemoteViews {
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)

        // Determine widget size category
        // 2x2: approximately 110dp x 110dp (compact square)
        // 4x1: approximately 250dp x 40dp (wide single row)
        // Full: approximately 250dp x 110dp (default)
        return when {
            minWidth < 180 && minHeight < 100 -> {
                // 2x2 Compact - Only play button with album art
                createCompactSquareRemoteViews(albumArt, isPlaying)
            }
            minWidth >= 180 && minHeight < 100 -> {
                // 4x1 Wide - Single row with album art, song info, like and play buttons
                createCompactWideRemoteViews(title, artist, albumArt, isPlaying, isLiked)
            }
            else -> {
                // Full layout
                createRemoteViews(title, artist, albumArt, isPlaying, isLiked, duration, currentPosition)
            }
        }
    }

    private fun createRemoteViews(
        title: String,
        artist: String,
        albumArt: Bitmap,
        isPlaying: Boolean,
        isLiked: Boolean,
        duration: Long = 0,
        currentPosition: Long = 0
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_music_player)

        // Set song info
        views.setTextViewText(R.id.widget_song_title, title)
        views.setTextViewText(R.id.widget_artist_name, artist)

        // Album art arrives already rounded and cached by updateWidgets
        views.setImageViewBitmap(R.id.widget_album_art, albumArt)

        // Set play/pause icon
        val playPauseIcon = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        views.setImageViewResource(R.id.widget_play_pause, playPauseIcon)

        // Set like icon - using nav style (purple) for main widget
        val likeIcon = if (isLiked) R.drawable.ic_widget_heart_nav else R.drawable.ic_widget_heart_outline_nav
        views.setImageViewResource(R.id.widget_like_button, likeIcon)

        // Set Progress Level
        if (duration > 0) {
            val level = ((currentPosition.toDouble() / duration.toDouble()) * 10000).toInt()
            views.setInt(R.id.widget_progress_fill, "setImageLevel", level)
        } else {
            views.setInt(R.id.widget_progress_fill, "setImageLevel", 0)
        }

        // Set click intents
        views.setOnClickPendingIntent(R.id.widget_album_art, openAppIntent)
        views.setOnClickPendingIntent(R.id.widget_play_pause_container, playPauseIntent)
        views.setOnClickPendingIntent(R.id.widget_like_button, likeIntent)

        return views
    }

    private suspend fun loadAlbumArt(artworkUri: String, size: Int = 200): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(artworkUri)
                    .size(size, size)
                    .allowHardware(false)
                    .crossfade(300)
                    .build()
                val result = imageLoader.execute(request)
                result.image?.toBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun getRoundedCornerBitmap(bitmap: Bitmap, cornerRadius: Float): Bitmap {
        // Ensure the bitmap is square for thumbnails
        val size = minOf(bitmap.width, bitmap.height)
        val xOffset = (bitmap.width - size) / 2
        val yOffset = (bitmap.height - size) / 2
        val squareBitmap = Bitmap.createBitmap(bitmap, xOffset, yOffset, size, size)

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            shader = BitmapShader(squareBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        
        if (squareBitmap != bitmap) {
            squareBitmap.recycle()
        }
        
        return output
    }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        
        // First crop to square
        val xOffset = (bitmap.width - size) / 2
        val yOffset = (bitmap.height - size) / 2
        val squareBitmap = Bitmap.createBitmap(bitmap, xOffset, yOffset, size, size)
        
        // Create circular output
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            shader = BitmapShader(squareBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, paint)
        
        if (squareBitmap != bitmap) {
            squareBitmap.recycle()
        }
        return output
    }

    private fun createCompactSquareRemoteViews(
        albumArt: Bitmap,
        isPlaying: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_compact_square)

        // Album art arrives already rounded and cached by updateWidgets
        views.setImageViewBitmap(R.id.widget_compact_album_art, albumArt)

        // Set play/pause icon - using low style icons
        val playPauseIcon = if (isPlaying) R.drawable.ic_widget_pause_low else R.drawable.ic_widget_play_low
        views.setImageViewResource(R.id.widget_compact_play_pause, playPauseIcon)

        // Set click intents
        views.setOnClickPendingIntent(R.id.widget_compact_album_art, openAppIntent)
        views.setOnClickPendingIntent(R.id.widget_compact_play_container, playPauseIntent)

        return views
    }

    private fun createCompactWideRemoteViews(
        title: String,
        artist: String,
        albumArt: Bitmap,
        isPlaying: Boolean,
        isLiked: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_compact_wide)

        // Set song info
        views.setTextViewText(R.id.widget_wide_song_title, title)
        views.setTextViewText(R.id.widget_wide_artist_name, artist)

        // Album art arrives already rounded and cached by updateWidgets
        views.setImageViewBitmap(R.id.widget_wide_album_art, albumArt)

        // Set play/pause icon - using low style icons
        val playPauseIcon = if (isPlaying) R.drawable.ic_widget_pause_low else R.drawable.ic_widget_play_low
        views.setImageViewResource(R.id.widget_wide_play_pause, playPauseIcon)

        // Set like icon - using navigation style (purple)
        val likeIcon = if (isLiked) R.drawable.ic_widget_heart_nav else R.drawable.ic_widget_heart_outline_nav
        views.setImageViewResource(R.id.widget_wide_like_button, likeIcon)

        // Set click intents
        views.setOnClickPendingIntent(R.id.widget_wide_album_art, openAppIntent)
        views.setOnClickPendingIntent(R.id.widget_wide_play_container, playPauseIntent)
        views.setOnClickPendingIntent(R.id.widget_wide_like_button, likeIntent)

        return views
    }

    private fun createTurntableRemoteViews(
        circularAlbumArt: Bitmap,
        isPlaying: Boolean,
        isLiked: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_turntable)

        // Album art arrives already circle-cropped and cached by updateWidgets
        views.setImageViewBitmap(R.id.widget_turntable_album_art, circularAlbumArt)

        // Set play/pause icon - using secondary color icons for turntable
        val playPauseIcon = if (isPlaying) R.drawable.ic_widget_pause_secondary else R.drawable.ic_widget_play_secondary
        views.setImageViewResource(R.id.widget_turntable_play_pause, playPauseIcon)

        // Set click intents
        views.setOnClickPendingIntent(R.id.widget_turntable_album_art, openAppIntent)
        views.setOnClickPendingIntent(R.id.widget_turntable_play_container, turntablePlayPauseIntent)
        views.setOnClickPendingIntent(R.id.widget_turntable_prev_button, turntablePreviousIntent)
        views.setOnClickPendingIntent(R.id.widget_turntable_next_button, turntableNextIntent)

        return views
    }
    
    private fun getCircularDefaultIcon(): Bitmap {
        // Get the launcher icon and make it circular
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val size = 300
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return getCircularBitmap(bitmap)
    }
    
    private fun getRoundedDefaultIcon(cornerRadius: Float): Bitmap {
        // Get the launcher icon and make it rounded
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val size = 300
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return getRoundedCornerBitmap(bitmap, cornerRadius)
    }

    // The click intents never change, but building one is a binder round-trip. They used
    // to be rebuilt on every render — up to nine per frame for the turntable layout.
    private val openAppIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val playPauseIntent: PendingIntent by lazy {
        broadcastIntent(1, MusicWidgetReceiver::class.java, MusicWidgetReceiver.ACTION_PLAY_PAUSE)
    }

    private val likeIntent: PendingIntent by lazy {
        broadcastIntent(2, MusicWidgetReceiver::class.java, MusicWidgetReceiver.ACTION_LIKE)
    }

    private val turntablePlayPauseIntent: PendingIntent by lazy {
        broadcastIntent(
            3,
            TurntableWidgetReceiver::class.java,
            TurntableWidgetReceiver.ACTION_TURNTABLE_PLAY_PAUSE
        )
    }

    private val turntableNextIntent: PendingIntent by lazy {
        broadcastIntent(4, TurntableWidgetReceiver::class.java, TurntableWidgetReceiver.ACTION_TURNTABLE_NEXT)
    }

    private fun broadcastIntent(requestCode: Int, receiver: Class<*>, actionName: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, receiver).apply { action = actionName },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private val nextIntent: PendingIntent by lazy {
        broadcastIntent(6, MusicWidgetReceiver::class.java, MusicWidgetReceiver.ACTION_NEXT)
    }

    private val previousIntent: PendingIntent by lazy {
        broadcastIntent(7, MusicWidgetReceiver::class.java, MusicWidgetReceiver.ACTION_PREVIOUS)
    }

    private class MeldFace(
        val title: String,
        val artist: String,
        val cover: Bitmap,
        val isPlaying: Boolean,
        val isLiked: Boolean,
        val progress: Int,
        val quality: CharSequence?,
        val rawCover: Bitmap? = null,
        val seed: Int = 0,
    )

    /** What each 2x1 widget shows in full (track and play state); otherwise it only gets the progress. */
    private val miniRendered = HashMap<Int, String>()

    private var classicCoverFor: Bitmap? = null
    private var classicCover: Bitmap? = null

    /** The cover as the classic widget shows it (framed in the cover's accent), made once per track. */
    private fun classicCoverOf(face: MeldFace): Bitmap {
        val scale = minOf(context.resources.displayMetrics.density, 2.2f)
        val source = face.rawCover ?: face.cover
        if (classicCoverFor !== source) {
            classicCoverFor = source
            classicCover = runCatching {
                ClassicWidgetArt.cover(source, (92 * scale).toInt(), ClassicWidgetArt.accentOf(face.rawCover))
            }.getOrNull()
        }
        return classicCover ?: face.cover
    }

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /**
     * The 2x2 player. Its backdrop (the cover blurred and darkened) is drawn once per track; the
     * progress and times change every refresh.
     */
    private fun squareFaces(face: MeldFace, duration: Long, position: Long): List<Face> {
        val density = context.resources.displayMetrics.density
        val scale = minOf(density, 2.2f)
        fun build(layout: Int, sizeDp: Int) = RemoteViews(context.packageName, layout).apply {
            val px = (sizeDp * scale).toInt()
            runCatching { SquareWidgetArt.backdrop(face.rawCover, px, px, 22f * scale) }.getOrNull()?.let { setImageViewBitmap(R.id.square_bg, it) }
            setImageViewBitmap(R.id.square_cover, face.cover)
            setTextViewText(R.id.square_title, face.title)
            setTextViewText(R.id.square_artist, face.artist)
            setProgressBar(R.id.square_progress, 1000, face.progress, false)
            setTextViewText(R.id.square_position, if (duration > 0) clock(position) else "")
            setTextViewText(R.id.square_duration, if (duration > 0) clock(duration) else "")
            setImageViewResource(R.id.square_play, if (face.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
            setImageViewResource(R.id.square_like, if (face.isLiked) R.drawable.wd_heart_filled else R.drawable.wd_heart)
            setOnClickPendingIntent(R.id.square_cover, openAppIntent)
            setOnClickPendingIntent(R.id.square_title, openAppIntent)
            setOnClickPendingIntent(R.id.square_artist, openAppIntent)
            setOnClickPendingIntent(R.id.square_play, playPauseIntent)
            setOnClickPendingIntent(R.id.square_prev, previousIntent)
            setOnClickPendingIntent(R.id.square_next, nextIntent)
            setOnClickPendingIntent(R.id.square_like, likeIntent)
        }
        return listOf(
            Face(100f, 100f, build(R.layout.widget_square_small, 140)),
            Face(150f, 150f, build(R.layout.widget_square, 180)),
        )
    }

    /**
     * The 2x1 player: the 2x2's blurred cover behind; "title · artist" on one line across the top,
     * the cover with previous / play / next to its right, the progress across the bottom.
     */
    private fun miniFaces(face: MeldFace, options: Bundle): List<Face> {
        val scale = minOf(context.resources.displayMetrics.density, 2.2f)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).takeIf { it > 0 } ?: 170
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 80
        val line = android.text.SpannableStringBuilder(face.title).apply {
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (face.artist.isNotBlank()) {
                val from = length
                append("  ·  ").append(face.artist)
                setSpan(android.text.style.ForegroundColorSpan(0xC8FFFFFF.toInt()), from, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val views = RemoteViews(context.packageName, R.layout.widget_mini).apply {
            runCatching {
                SquareWidgetArt.backdrop(
                    face.rawCover,
                    (widthDp * scale).toInt().coerceIn(160, 600),
                    (heightDp * scale).toInt().coerceIn(80, 300),
                    18f * scale,
                )
            }.getOrNull()?.let { setImageViewBitmap(R.id.mini_bg, it) }
            setImageViewBitmap(R.id.mini_cover, face.cover)
            setTextViewText(R.id.mini_title, line)
            setProgressBar(R.id.mini_progress, 1000, face.progress, false)
            setImageViewResource(R.id.mini_play, if (face.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
            setOnClickPendingIntent(R.id.mini_cover, openAppIntent)
            setOnClickPendingIntent(R.id.mini_title, openAppIntent)
            setOnClickPendingIntent(R.id.mini_prev, previousIntent)
            setOnClickPendingIntent(R.id.mini_play, playPauseIntent)
            setOnClickPendingIntent(R.id.mini_next, nextIntent)
        }
        return listOf(Face(100f, 40f, views))
    }

    /** A layout for each size, smallest first, as (min width dp, min height dp) to views. */
    private class Face(val widthDp: Float, val heightDp: Float, val views: RemoteViews)

    /**
     * Android 12+: every layout goes to the launcher, which picks by size itself (resizing switches
     * the layout at once). Before: the one that fits the widget's current size.
     */
    private fun responsive(manager: AppWidgetManager, widgetId: Int, faces: List<Face>): RemoteViews {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            return RemoteViews(faces.associate { android.util.SizeF(it.widthDp, it.heightDp) to it.views })
        }
        val options = manager.getAppWidgetOptions(widgetId)
        val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return faces.lastOrNull { w >= it.widthDp && h >= it.heightDp }?.views ?: faces.first().views
    }

    private fun playIcon(face: MeldFace, secondary: Boolean = true) = when {
        face.isPlaying && secondary -> R.drawable.ic_widget_pause_secondary
        face.isPlaying -> R.drawable.ic_widget_pause
        secondary -> R.drawable.ic_widget_play_secondary
        else -> R.drawable.ic_widget_play
    }

    private fun classicFaces(face: MeldFace, options: Bundle): List<Face> {
        val density = context.resources.displayMetrics.density
        val scale = minOf(density, 2.2f)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).takeIf { it > 0 } ?: 300
        val fullHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it >= 110 } ?: 160
        val frame = System.currentTimeMillis() / 1000
        val progress = face.progress / 1000f
        val cover = classicCoverOf(face)

        fun build(layout: Int, heightDp: Int) = RemoteViews(context.packageName, layout).apply {
            val bg = runCatching {
                ClassicWidgetArt.draw(
                    (widthDp * scale).toInt().coerceIn(200, 900),
                    (heightDp * scale).toInt().coerceIn(80, 700),
                    face.rawCover, face.isPlaying, progress, face.seed, frame,
                )
            }.getOrNull()
            bg?.let { setImageViewBitmap(R.id.classic_bg, it) }
            setImageViewBitmap(R.id.classic_cover, cover)
            setTextViewText(R.id.classic_title, face.title)
            setTextViewText(R.id.classic_artist, face.artist)
            setImageViewResource(R.id.classic_play, if (face.isPlaying) R.drawable.wd_pause else R.drawable.wd_play)
            setImageViewResource(R.id.classic_like, if (face.isLiked) R.drawable.wd_heart_filled else R.drawable.wd_heart)
            // A liked heart in the cover's colour, like the rest of the widget.
            if (face.isLiked) setInt(R.id.classic_like, "setColorFilter", ClassicWidgetArt.accentOf(face.rawCover))
            else setInt(R.id.classic_like, "setColorFilter", android.graphics.Color.TRANSPARENT)
            setOnClickPendingIntent(R.id.classic_cover, openAppIntent)
            setOnClickPendingIntent(R.id.classic_texts, openAppIntent)
            setOnClickPendingIntent(R.id.classic_play, playPauseIntent)
            setOnClickPendingIntent(R.id.classic_next, nextIntent)
            setOnClickPendingIntent(R.id.classic_like, likeIntent)
            setOnClickPendingIntent(R.id.classic_prev, previousIntent)
            if (layout == R.layout.widget_classic_full) {
                setTextViewText(R.id.classic_quality, face.quality?.let { android.text.SpannableStringBuilder("DRXWNIFY  ·  ").append(it) } ?: "DRXWNIFY")
                setTextColor(R.id.classic_quality, ClassicWidgetArt.accentOf(face.rawCover))
                setOnClickPendingIntent(R.id.classic_open, openAppIntent)
            }
        }
        return listOf(
            Face(180f, 40f, build(R.layout.widget_classic_row, 72)),
            Face(180f, 110f, build(R.layout.widget_classic_full, fullHeightDp)),
        )
    }

    /**
     * Debug helper: draws every widget as it looks with a track playing, on a transparent
     * background, into [dir] — the pictures used as the widget picker previews.
     */
    fun renderPreviews(dir: java.io.File, cover: Bitmap) {
        dir.mkdirs()
        val density = context.resources.displayMetrics.density
        val face = MeldFace(
            title = "Lift Your Head",
            artist = "Holy Fawn",
            cover = getRoundedCornerBitmap(cover, DEFAULT_CORNER_RADIUS),
            isPlaying = true,
            isLiked = true,
            progress = 450,
            quality = "320 kbps · MP3 · VK",
            rawCover = cover,
            seed = 42,
        )
        fun save(name: String, bitmap: Bitmap) {
            java.io.FileOutputStream(java.io.File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun renderViews(views: RemoteViews, wDp: Int, hDp: Int): Bitmap {
            val parent = android.widget.FrameLayout(context)
            val view = views.apply(context, parent)
            val w = (wDp * density).toInt()
            val h = (hDp * density).toInt()
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, w, h)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(out))
            return out
        }
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 160)
        }
        val classic = classicFaces(face, options)
        save("preview_classic", renderViews(classic.last().views, 320, 160))
        save("preview_classic_row", renderViews(classic.first().views, 380, 106))
        save("preview_radio", renderViews(radioFaces(face).last().views, 320, 160))
        val square = squareFaces(face, 225_000L, 84_000L)
        save("preview_square", renderViews(square.last().views, 170, 170))
        save("preview_square_small", renderViews(square.first().views, 140, 140))
        val miniOptions = Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 170) }
        save("preview_mini", renderViews(miniFaces(face, miniOptions).first().views, 170, 80))
        val state = FreakState("Lift Your Head", "Holy Fawn", cover, isPlaying = true, isLiked = true, progress = 0.45f, live = true, quality = "320 kbps · MP3 · VK")
        fun px(dp: Int) = (dp * minOf(density, 2.2f)).toInt()
        save("preview_tv", FreakWidgets.drawFor(context, FreakKind.TV, px(220), px(160), state))
        val receipt = WidgetStats.Snapshot(
            secondsToday = 695L * 60,
            tracksToday = 28,
            recent = listOf(
                "Holy Fawn - Lift Your Head", "Holy Fawn - Death Is a Relief", "Holy Fawn - Hexsewn",
                "Planning for Burial - Somewhere", "Greet Death - Same Thing", "Iress - The Remainder",
                "Nothing - A.C.D.", "Show Me a Dinosaur - Unsaid I", "Life on Venus - Feel",
                "Slow Crush - Thirst", "Lantlôs - Melting Sun", "Holy Fawn - Blood Memory",
            ),
        )
        save("preview_receipt", FreakWidgets.drawFor(context, FreakKind.RECEIPT, px(180), px(300), state, receipt))
        save("preview_cyber", FreakWidgets.drawFor(context, FreakKind.CYBER, px(260), px(180), state))
        save("preview_shoes", FreakWidgets.drawFor(context, FreakKind.SHOES, px(220), px(140), state))
    }

    private fun radioFaces(face: MeldFace): List<Face> {
        val lcdSource = face.quality?.toString()?.uppercase() ?: "DRXWNIFY"
        fun build(layout: Int) = RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.radio_source, lcdSource)
            setTextViewText(R.id.radio_title, face.title)
            if (layout != R.layout.widget_radio_strip) setTextViewText(R.id.radio_artist, face.artist)
            setProgressBar(R.id.radio_progress, 1000, face.progress, false)
            setImageViewResource(R.id.radio_play, playIcon(face, secondary = false))
            setOnClickPendingIntent(R.id.radio_lcd, openAppIntent)
            setOnClickPendingIntent(R.id.radio_play, playPauseIntent)
            setOnClickPendingIntent(R.id.radio_prev, previousIntent)
            setOnClickPendingIntent(R.id.radio_next, nextIntent)
            if (layout == R.layout.widget_radio_full) {
                setImageViewBitmap(R.id.radio_cover, face.cover)
                setImageViewResource(R.id.radio_like, if (face.isLiked) R.drawable.ic_widget_heart_nav else R.drawable.ic_widget_heart_outline_nav)
                setOnClickPendingIntent(R.id.radio_like, likeIntent)
                setOnClickPendingIntent(R.id.radio_open, openAppIntent)
                setOnClickPendingIntent(R.id.radio_cover, openAppIntent)
            }
        }
        return listOf(
            Face(100f, 100f, build(R.layout.widget_radio_small)),
            Face(220f, 40f, build(R.layout.widget_radio_strip)),
            Face(220f, 110f, build(R.layout.widget_radio_full)),
        )
    }

    /** "320 kbps · MP3 · VK", the bitrate in its quality colour (as above the cover in the player). */
    private suspend fun qualityLine(mediaId: String): CharSequence? = withContext(Dispatchers.IO) {
        val format = runCatching { database.format(mediaId).firstOrNull() }.getOrNull() ?: return@withContext null
        val codec = com.metrolist.music.ui.component.AudioQualityLevel.codecName(format.mimeType, format.codecs)
        val source = (com.metrolist.music.resolver.NowServing.of(mediaId)
            ?: com.metrolist.music.resolver.AudioFallbackEngine.sourceOf(mediaId, database.getSongByIdBlocking(mediaId)))
            ?.let { com.metrolist.music.ui.component.providerLabel(it) }
        val kbps = format.bitrate.takeIf { it > 0 }?.let { com.metrolist.music.ui.component.AudioQualityLevel.kbps(it) }
        val out = android.text.SpannableStringBuilder()
        kbps?.let {
            val start = out.length
            out.append("$it kbps")
            val color = com.metrolist.music.ui.dialog.bitrateColor(it)
            out.setSpan(
                android.text.style.ForegroundColorSpan(android.graphics.Color.argb(255, (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())),
                start, out.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        listOfNotNull(codec, source).forEach { if (out.isNotEmpty()) out.append(" · "); out.append(it) }
        out.takeIf { it.isNotEmpty() }
    }

    private val turntablePreviousIntent: PendingIntent by lazy {
        broadcastIntent(
            5,
            TurntableWidgetReceiver::class.java,
            TurntableWidgetReceiver.ACTION_TURNTABLE_PREVIOUS
        )
    }

    private companion object {
        /** Matches 12dp at ~4x density for the 48dp artwork views. */
        const val DEFAULT_CORNER_RADIUS = 48f
        const val ARTWORK_SIZE = 512
    }
}
