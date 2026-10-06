/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.R
import com.metrolist.music.utils.YTPlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * "Download the music video": finds the track's clip on YouTube and saves it as a video file in
 * Movies/Drxwnify. YouTube keeps picture and sound apart, so both streams are downloaded and then
 * joined without re-encoding (FFmpeg stream copy) — the file is exactly what YouTube serves.
 */
object VideoClipDownloader {
    data class Candidate(
        val videoId: String,
        val title: String,
        val channel: String,
        val durationSec: Int?,
        val thumbnailUrl: String,
    )

    /** Heights offered in the dialog; [DEFAULT_HEIGHT] is preselected. */
    val HEIGHTS = listOf(2160, 1440, 1080, 720, 480, 360)
    const val DEFAULT_HEIGHT = 1080

    /** Running downloads by video id: 0..1, or -1 while the streams are being looked up / joined. */
    private val _running = MutableStateFlow<Map<String, Float>>(emptyMap())
    val running: StateFlow<Map<String, Float>> = _running

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val OFFICIAL = Regex("official (music )?video|music video|\\bmv\\b|клип|official clip", RegexOption.IGNORE_CASE)
    private val NOT_A_CLIP = Regex(
        "lyric|lyrics|текст|visuali[sz]er|audio\\)|\\(audio|official audio|reaction|cover|karaoke|live at|live in|\\blive\\b|" +
            "remix|sped up|slowed|nightcore|8d|tutorial|lesson",
        RegexOption.IGNORE_CASE,
    )

    /** Clips of [title] by [artist], the likeliest official video first. */
    suspend fun search(title: String, artist: String, durationSec: Int?): List<Candidate> {
        val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
        val found = YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO, incognito = true)
            .getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
        val titleKey = normalize(com.metrolist.spotify.SpotifyMapper.spotifyTitleCore(title))
        val artistKey = normalize(artist)
        fun score(item: SongItem): Int {
            val name = normalize(item.title)
            val channel = normalize(item.artists.joinToString(" ") { it.name })
            var s = 0
            if (titleKey.isNotEmpty() && titleKey in name) s += 40
            if (artistKey.isNotEmpty() && (artistKey in channel || artistKey in name)) s += 30
            if (OFFICIAL.containsMatchIn(item.title)) s += 15
            if (NOT_A_CLIP.containsMatchIn(item.title) && !NOT_A_CLIP.containsMatchIn(title)) s -= 35
            val d = item.duration
            if (d != null && durationSec != null && durationSec > 0) {
                val off = kotlin.math.abs(d - durationSec)
                s += when {
                    off <= 5 -> 15
                    off <= 30 -> 8 // clips often carry an intro or an outro
                    off <= 90 -> 0
                    else -> -20
                }
            }
            return s
        }
        return found.distinctBy { it.id }
            .sortedByDescending(::score)
            .take(MAX_CANDIDATES)
            .map {
                Candidate(
                    videoId = it.id,
                    title = it.title,
                    channel = it.artists.joinToString(", ") { a -> a.name },
                    durationSec = it.duration,
                    // 16:9 frame (the search thumbnail of a video can be a square crop).
                    thumbnailUrl = "https://i.ytimg.com/vi/${it.id}/hqdefault.jpg",
                )
            }
    }

    private fun normalize(s: String): String =
        s.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").replace(Regex("\\s+"), " ").trim()

    /** Starts downloading [candidate] at up to [maxHeight]; progress shows in a notification. */
    fun start(context: Context, candidate: Candidate, maxHeight: Int, fileTitle: String) {
        if (candidate.videoId in _running.value) return
        val appContext = context.applicationContext
        _running.value = _running.value + (candidate.videoId to -1f)
        scope.launch {
            val notifications = appContext.getSystemService(NotificationManager::class.java)
            ensureChannel(appContext, notifications)
            val notificationId = NOTIFICATION_BASE + (candidate.videoId.hashCode() and 0xFFFF)
            fun notifyProgress(text: String, progress: Float?) {
                val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(R.drawable.small_icon)
                    .setContentTitle(fileTitle)
                    .setContentText(text)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                if (progress == null) builder.setProgress(0, 0, true) else builder.setProgress(1000, (progress * 1000).toInt(), false)
                runCatching { notifications.notify(notificationId, builder.build()) }
            }
            val work = File(appContext.cacheDir, "clip_${candidate.videoId}").also { it.mkdirs() }
            try {
                notifyProgress(appContext.getString(R.string.video_clip_preparing), null)
                val streams = YTPlayerUtils.videoDownloadStreams(candidate.videoId, maxHeight).getOrThrow()
                val videoExt = if (streams.video.mimeType.startsWith("video/mp4")) "mp4" else "webm"
                val audioExt = if (streams.audio.mimeType.startsWith("audio/mp4")) "m4a" else "webm"
                val videoFile = File(work, "v.$videoExt")
                val audioFile = File(work, "a.$audioExt")
                val videoSize = streams.video.contentLength ?: 0L
                val audioSize = streams.audio.contentLength ?: 0L
                val total = (videoSize + audioSize).coerceAtLeast(1L)
                val label = "${streams.video.height}p"
                fetch(streams.videoUrl, videoFile, videoSize) { done ->
                    val p = done.toFloat() / total
                    _running.value = _running.value + (candidate.videoId to p)
                    notifyProgress(appContext.getString(R.string.video_clip_downloading, label), p)
                }
                fetch(streams.audioUrl, audioFile, audioSize) { done ->
                    val p = (videoSize + done).toFloat() / total
                    _running.value = _running.value + (candidate.videoId to p)
                    notifyProgress(appContext.getString(R.string.video_clip_downloading, label), p)
                }
                notifyProgress(appContext.getString(R.string.video_clip_joining), null)
                val mp4 = videoExt == "mp4" && audioExt == "m4a"
                val outExt = if (mp4) "mp4" else "mkv"
                val joined = File(work, "out.$outExt")
                val args = buildList {
                    addAll(listOf("-y", "-i", videoFile.absolutePath, "-i", audioFile.absolutePath))
                    addAll(listOf("-map", "0:v:0", "-map", "1:a:0", "-c", "copy"))
                    addAll(listOf("-metadata", "title=$fileTitle"))
                    if (mp4) addAll(listOf("-movflags", "+faststart"))
                    add(joined.absolutePath)
                }.toTypedArray()
                val session = FFmpegKit.executeWithArguments(args)
                if (!ReturnCode.isSuccess(session.returnCode) || !joined.exists() || joined.length() == 0L) {
                    error("ffmpeg ${session.returnCode}: ${session.allLogsAsString?.takeLast(300)}")
                }
                val uri = save(appContext, joined, "${sanitize(fileTitle)} [$label].$outExt", if (mp4) "video/mp4" else "video/x-matroska")
                notifications.cancel(notificationId)
                val open = PendingIntent.getActivity(
                    appContext, notificationId,
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (mp4) "video/mp4" else "video/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                notifications.notify(
                    notificationId,
                    NotificationCompat.Builder(appContext, CHANNEL_ID)
                        .setSmallIcon(R.drawable.small_icon)
                        .setContentTitle(fileTitle)
                        .setContentText(appContext.getString(R.string.video_clip_saved, label))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build(),
                )
            } catch (t: Throwable) {
                Timber.w(t, "VideoClipDownloader: %s failed", candidate.videoId)
                notifications.notify(
                    notificationId,
                    NotificationCompat.Builder(appContext, CHANNEL_ID)
                        .setSmallIcon(R.drawable.small_icon)
                        .setContentTitle(fileTitle)
                        .setContentText(appContext.getString(R.string.video_clip_failed, t.message?.take(120).orEmpty()))
                        .setAutoCancel(true)
                        .build(),
                )
            } finally {
                work.deleteRecursively()
                _running.value = _running.value - candidate.videoId
            }
        }
    }

    /** Downloads [url] in ranged pieces (googlevideo throttles one long request). */
    private suspend fun fetch(url: String, dest: File, size: Long, onProgress: (Long) -> Unit) {
        var done = 0L
        dest.outputStream().use { out ->
            while (size <= 0L || done < size) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val end = if (size > 0) minOf(size - 1, done + CHUNK - 1) else done + CHUNK - 1
                val request = Request.Builder().url(url).header("Range", "bytes=$done-$end").build()
                val read = http.newCall(request).execute().use { response ->
                    if (response.code == 416 && size <= 0) return@use -1L
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("empty response")
                    var n = 0L
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val r = input.read(buffer)
                            if (r < 0) break
                            out.write(buffer, 0, r)
                            n += r
                        }
                    }
                    n
                }
                if (read <= 0L) break
                done += read
                onProgress(done)
                if (size <= 0L && read < CHUNK) break
            }
        }
    }

    private fun save(context: Context, file: File, name: String, mime: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Drxwnify")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused the file")
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("cannot write")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Drxwnify").also { it.mkdirs() }
        val target = File(dir, name)
        file.copyTo(target, overwrite = true)
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", target)
    }

    private fun sanitize(name: String): String =
        name.map { if (it in "/\\:*?\"<>|" || it.code < 0x20) '_' else it }.joinToString("").trim().trimStart('.').ifEmpty { "clip" }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.video_clip_download), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private const val CHANNEL_ID = "video_clips"
    private const val NOTIFICATION_BASE = 0x5C1_0000
    private const val CHUNK = 8L * 1024 * 1024
    private const val MAX_CANDIDATES = 8
}
