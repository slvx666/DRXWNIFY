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
            val freaks = FreakWidgets.placed(context)
            if (widgetIds.isEmpty() && turntableWidgetIds.isEmpty() && radioIds.isEmpty() && classicIds.isEmpty() && freaks.isEmpty()) return

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
                quality = if (radioIds.isNotEmpty() || FreakKind.CYBER in freaks) mediaId?.let { qualityLine(it) } else null,
            )
            radioIds.forEach { id -> runCatching { appWidgetManager.updateAppWidget(id, responsive(appWidgetManager, id, radioFaces(meld))) } }
            classicIds.forEach { id -> runCatching { appWidgetManager.updateAppWidget(id, responsive(appWidgetManager, id, classicFaces(meld))) } }
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
    )

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

    private fun classicFaces(face: MeldFace): List<Face> {
        fun build(layout: Int) = RemoteViews(context.packageName, layout).apply {
            setImageViewBitmap(R.id.classic_cover, face.cover)
            setTextViewText(R.id.classic_title, face.title)
            setTextViewText(R.id.classic_artist, face.artist)
            setImageViewResource(R.id.classic_play, playIcon(face, secondary = false))
            setOnClickPendingIntent(R.id.classic_cover, openAppIntent)
            setOnClickPendingIntent(R.id.classic_title, openAppIntent)
            setOnClickPendingIntent(R.id.classic_play, playPauseIntent)
            setOnClickPendingIntent(R.id.classic_prev, previousIntent)
            setOnClickPendingIntent(R.id.classic_next, nextIntent)
            if (layout == R.layout.widget_classic_full) {
                setProgressBar(R.id.classic_progress, 1000, face.progress, false)
                setImageViewResource(R.id.classic_like, if (face.isLiked) R.drawable.ic_widget_heart_nav else R.drawable.ic_widget_heart_outline_nav)
                setOnClickPendingIntent(R.id.classic_like, likeIntent)
            }
        }
        return listOf(
            Face(180f, 40f, build(R.layout.widget_classic_row)),
            Face(180f, 110f, build(R.layout.widget_classic_full)),
        )
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
