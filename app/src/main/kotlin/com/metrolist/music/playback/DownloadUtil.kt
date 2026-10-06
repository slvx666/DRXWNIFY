/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import com.metrolist.innertube.YouTube
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.constants.AudioQualityKey
import androidx.media3.datasource.DataSpec
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.di.DownloadCache
import com.metrolist.music.di.PlayerCache
import com.metrolist.music.playback.datasource.HlsConcatDataSource
import com.metrolist.music.resolver.AudioDiagnostics
import com.metrolist.music.resolver.AudioFallbackEngine
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.FallbackIds
import com.metrolist.music.resolver.ResolverPreferences
import com.metrolist.music.utils.DownloadExportState
import com.metrolist.music.utils.DownloadExporter
import com.metrolist.music.utils.YTPlayerUtils
import com.metrolist.music.utils.enumPreference
import com.metrolist.spotify.SpotifyMapper
import kotlinx.coroutines.withTimeout
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import timber.log.Timber
import java.time.LocalDateTime
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
@Inject
constructor(
    @ApplicationContext context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
    @PlayerCache val playerCache: SimpleCache,
    val downloadExporter: DownloadExporter,
) {
    private val TAG = "DownloadUtil"
    private val appContext = context
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.HIGH)
    private val songUrlCache = HashMap<String, Pair<String, Long>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Raw Media3 download states: "completed" here only means the bytes are in the cache. */
    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    /**
     * What every screen shows. A track counts as downloaded only once its file is in the user's
     * folder: bytes that are cached but still waiting for (or going through) the export read as
     * "downloading", a failed export as "failed", and a download being removed is gone already.
     * The header counts, the rows, the mini and fullscreen players and the menus all read this, so
     * they can no longer disagree.
     */
    val visibleDownloads: StateFlow<Map<String, Download>> =
        combine(
            downloads,
            DownloadExportState.exporting,
            DownloadExportState.exported,
            DownloadExportState.failed,
        ) { raw, exporting, exported, failed ->
            buildMap {
                for ((id, download) in raw) {
                    val state = when {
                        download.state == Download.STATE_REMOVING -> null
                        download.state != Download.STATE_COMPLETED -> download.state
                        id in exporting -> Download.STATE_DOWNLOADING
                        id in exported -> Download.STATE_COMPLETED
                        id in failed -> Download.STATE_FAILED
                        else -> Download.STATE_DOWNLOADING
                    }
                    if (state != null) put(id, download.withState(state))
                }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** A copy in another state; Download's constructor enforces which reasons fit which state. */
    private fun Download.withState(state: Int): Download =
        if (state == this.state) {
            this
        } else {
            Download(
                request,
                state,
                startTimeMs,
                updateTimeMs,
                contentLength,
                if (state == Download.STATE_DOWNLOADING || state == Download.STATE_QUEUED) Download.STOP_REASON_NONE else stopReason,
                if (state == Download.STATE_FAILED) Download.FAILURE_REASON_UNKNOWN else Download.FAILURE_REASON_NONE,
            )
        }

    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    OkHttpClient.Builder()
                        // Bound stalled connections so a hung download eventually FAILS instead
                        // of running forever — a never-ending download kept the dataSync
                        // foreground service alive past Android 14's limit and crashed the app
                        // (ForegroundServiceDidNotStopInTimeException). readTimeout is per-read
                        // (between bytes), so it doesn't cut off legitimately long downloads.
                        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                        .proxy(YouTube.proxy)
                        .proxyAuthenticator { _, response ->
                            YouTube.proxyAuth?.let { auth ->
                                response.request.newBuilder()
                                    .header("Proxy-Authorization", auth)
                                    .build()
                            } ?: response.request
                        }
                        .build()
                        .let { client ->
                            // meldhls:// (HLS-only providers such as SoundCloud) → one progressive stream.
                            HlsConcatDataSource.Factory(OkHttpDataSource.Factory(client), client)
                        },
                ),
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            val length = if (dataSpec.length >= 0) dataSpec.length else 1

            // Fallback track (audio from Qobuz / VK / SoundCloud, or a YouTube alternate): resolve the
            // stream through the audio fallback engine and record FormatEntity/SongEntity rows so the
            // exporter can transcode it to MP3 afterwards. Ordinary YouTube downloads are untouched.
            if (FallbackIds.isFallbackId(mediaId)) {
                if (playerCache.isCached(mediaId, dataSpec.position, length)) return@Factory dataSpec
                songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                    return@Factory dataSpec.withUri(it.first.toUri())
                }
                return@Factory resolveFallbackDownload(dataSpec, mediaId)
            }

            if (playerCache.isCached(mediaId, dataSpec.position, length)) {
                return@Factory dataSpec
            }

            songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                return@Factory dataSpec.withUri(it.first.toUri())
            }

            // YouTube switched off: download a catalog track from another audio provider directly.
            if (!ResolverPreferences.youtubeEnabled) {
                rescueYouTubeDownload(dataSpec, mediaId, youtubeError = null)?.let { return@Factory it }
            }

            val playbackData = runBlocking(Dispatchers.IO) {
                YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    // The best stream there is (Opus ~160k beats AAC 128k); the exporter turns it
                    // into an AAC .m4a at a bitrate that keeps what the Opus stream carries.
                    preferAac = false,
                )
            }.getOrElse { error ->
                // YouTube unreachable / video gone: take the catalog track from another provider.
                rescueYouTubeDownload(dataSpec, mediaId, youtubeError = error)?.let { return@Factory it }
                throw error
            }
            val format = playbackData.format

            database.query {
                upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = format.itag,
                        mimeType = format.mimeType.split(";")[0],
                        codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                        bitrate = format.bitrate,
                        sampleRate = format.audioSampleRate,
                        contentLength = format.contentLength!!,
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                        perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                        playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    ),
                )

                val now = LocalDateTime.now()
                val existing = getSongByIdBlocking(mediaId)?.song

                val updatedSong = if (existing != null) {
                    if (existing.dateDownload == null) {
                        existing.copy(dateDownload = now)
                    } else {
                        existing
                    }
                } else {
                    SongEntity(
                        id = mediaId,
                        title = playbackData.videoDetails?.title ?: "Unknown",
                        duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                        thumbnailUrl = playbackData.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url,
                        dateDownload = now,
                        isDownloaded = false
                    )
                }

                upsert(updatedSong)
            }

            val streamUrl = playbackData.streamUrl.let {
                "${it}&range=0-${format.contentLength ?: 10000000}"
            }

            // Safety margin: treat the URL as expired 60s before its real TTL so we
            // refresh in-flight rather than handing ExoPlayer a URL that 403s mid-open.
            val expiresAt = System.currentTimeMillis() +
                ((playbackData.streamExpiresInSeconds - 60).coerceAtLeast(0)) * 1000L
            songUrlCache[mediaId] = streamUrl to expiresAt
            dataSpec.withUri(streamUrl.toUri())
        }

    val downloadNotificationHelper =
        DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    @OptIn(DelicateCoroutinesApi::class)
    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            Executor(Runnable::run)
        ).apply {
            maxParallelDownloads = 3
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        downloads.update { map ->
                            map.toMutableMap().apply {
                                set(download.request.id, download)
                            }
                        }

                        scope.launch {
                            when (download.state) {
                                Download.STATE_COMPLETED -> {
                                    database.updateDownloadedInfo(download.request.id, true, LocalDateTime.now())

                                    // Export the assembled media file to the user's folder. Only
                                    // when the cached resource is complete; otherwise the export
                                    // would read a truncated stream.
                                    if (download.contentLength <= 0 ||
                                        download.bytesDownloaded == download.contentLength
                                    ) {
                                        scope.launch {
                                            downloadExporter.export(download.request.id)
                                                .onFailure {
                                                    if (it !is DownloadExporter.AlreadyExportedException) {
                                                        Timber.tag(TAG).e(it, "Export failed for ${download.request.id}")
                                                    }
                                                }
                                        }
                                    } else {
                                        // No file will come of it: say so instead of spinning forever.
                                        DownloadExportState.markFailed(download.request.id)
                                        Timber.tag(TAG).w(
                                            "Skipping export for ${download.request.id}: incomplete " +
                                                "(${download.bytesDownloaded}/${download.contentLength})"
                                        )
                                    }
                                }
                                Download.STATE_FAILED,
                                Download.STATE_STOPPED,
                                Download.STATE_REMOVING -> {
                                    database.updateDownloadedInfo(download.request.id, false, null)
                                }
                                else -> {
                                }
                            }
                        }
                    }

                    override fun onDownloadRemoved(
                        downloadManager: DownloadManager,
                        download: Download,
                    ) {
                        val downloadId = download.request.id
                        // The map first, on this (main) thread: the UI must not keep a removed
                        // track as downloaded while the database write is still pending.
                        downloads.update { map -> map - downloadId }
                        // Room refuses the main thread; this used to throw and leave the track
                        // "downloaded" with its file still offered for sharing.
                        scope.launch {
                            runCatching { database.updateDownloadedInfo(downloadId, false, null) }
                                .onFailure { Timber.tag(TAG).e(it, "Failed to clear the download flag of $downloadId") }
                            downloadExporter.deleteExported(downloadId)
                        }
                    }
                }
            )
        }

    init {
        val result = mutableMapOf<String, Download>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result
        reconcileWithDownloads()
        exportMissingFiles()
    }

    /**
     * Brings the files in the music folder in line with the downloads:
     *  - downloads made before files were written there, or whose export never ran, would read as
     *    "downloading" forever: their files are written now;
     *  - files of downloads that were removed (removing used to leave them behind, still offered
     *    for sharing) are deleted.
     */
    private fun exportMissingFiles() {
        scope.launch {
            kotlinx.coroutines.delay(EXPORT_CATCH_UP_DELAY_MS)
            val exported = DownloadExportState.exported.value
            val known = downloads.value
            val orphaned = exported.filter { it !in known && it !in DownloadExportState.exporting.value }
            if (orphaned.isNotEmpty()) {
                Timber.tag(TAG).i("Deleting %d file(s) left behind by removed downloads", orphaned.size)
                orphaned.forEach { downloadExporter.deleteExported(it) }
            }
            val missing = known.values
                .filter { it.state == Download.STATE_COMPLETED && it.request.id !in exported }
                .map { it.request.id }
            if (missing.isEmpty()) return@launch
            Timber.tag(TAG).i("Writing the files of %d download(s) that have none", missing.size)
            missing.forEach { downloadExporter.export(it) }
        }
    }

    /**
     * The "downloaded" flag in the database and the actual downloads can drift apart — the files can
     * be deleted from outside the app, or the download index can be cleared while the app isn't
     * running — and then the library keeps counting tracks that are no longer there. Anything the
     * download manager doesn't know about (or hasn't finished) loses the flag.
     */
    fun removeDownload(songId: String) {
        scope.launch(Dispatchers.IO) {
            database.updateDownloadedInfo(songId, false, null)
            downloadExporter.deleteExported(songId)
            downloads.update { map ->
                map.toMutableMap().apply { remove(songId) }
            }
            DownloadService.sendRemoveDownload(
                appContext,
                ExoDownloadService::class.java,
                songId,
                false,
            )
        }
    }

    /**
     * Another version of a downloaded track was picked: its old audio and file go, and [newId] (the
     * same id when only the source behind it changed) is downloaded from the new source. The
     * download manager restarts a download that is still being removed, so the order is safe.
     */
    fun redownload(oldId: String, newId: String, title: String) {
        scope.launch(Dispatchers.IO) {
            downloadExporter.deleteExported(oldId)
            if (oldId != newId) database.updateDownloadedInfo(oldId, false, null)
            DownloadService.sendRemoveDownload(appContext, ExoDownloadService::class.java, oldId, false)
            val request = androidx.media3.exoplayer.offline.DownloadRequest.Builder(newId, newId.toUri())
                .setCustomCacheKey(newId)
                .setData(title.toByteArray())
                .build()
            DownloadService.sendAddDownload(appContext, ExoDownloadService::class.java, request, false)
        }
    }

    fun removeDownloads(songIds: Collection<String>) {
        if (songIds.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            database.query {
                songIds.forEach { updateDownloadedInfo(it, false, null) }
            }
            songIds.forEach { id ->
                downloadExporter.deleteExported(id)
                DownloadService.sendRemoveDownload(
                    appContext,
                    ExoDownloadService::class.java,
                    id,
                    false,
                )
            }
            downloads.update { map ->
                map.toMutableMap().apply {
                    songIds.forEach { remove(it) }
                }
            }
        }
    }

    fun clearAllDownloads() {
        scope.launch(Dispatchers.IO) {
            val allDownloaded = database.downloadedSongIdsBlocking()
            removeDownloads(allDownloaded)
        }
    }

    /**
     * The "downloaded" flag in the database and the actual downloads can drift apart — the files can
     * be deleted from outside the app, or the download index can be cleared while the app isn't
     * running — and then the library keeps counting tracks that are no longer there. Anything the
     * download manager doesn't know about (or hasn't finished) loses the flag.
     */
    fun reconcileWithDownloads() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val completed = downloads.value
                    .filterValues { it.state == Download.STATE_COMPLETED }
                    .keys
                val claimed = database.downloadedSongIdsBlocking()
                val stale = claimed.filterNot { it in completed }.toMutableSet()

                // Check completed downloads: if neither cache nor exported file exists, it's stale.
                for (id in claimed) {
                    if (id in stale) continue
                    val hasCachedBytes = playerCache.isCached(id, 0, 1) || downloadCache.isCached(id, 0, 1)
                    if (!hasCachedBytes) {
                        val exportedUri = downloadExporter.exportedUri(id)
                        val exportedExists = exportedUri?.let { uri ->
                            runCatching {
                                if (uri.scheme == "file") {
                                    java.io.File(uri.path ?: "").exists()
                                } else {
                                    appContext.contentResolver.openInputStream(uri)?.use { true } ?: false
                                }
                            }.getOrDefault(false)
                        } ?: false

                        if (!exportedExists) {
                            stale.add(id)
                        }
                    }
                }

                if (stale.isEmpty()) return@runCatching
                Timber.tag(TAG).i("Clearing the download flag of %d song(s) with no download left", stale.size)
                database.query {
                    stale.forEach { updateDownloadedInfo(it, false, null) }
                }
                stale.forEach { downloadExporter.forgetExported(it) }
                downloads.update { map ->
                    map.toMutableMap().apply {
                        stale.forEach { remove(it) }
                    }
                }
            }.onFailure { Timber.tag(TAG).e(it, "Reconciling downloads failed") }
        }
    }

    /** Network-level failures mean "YouTube unreachable"; anything else is about this particular video. */
    private fun isNetworkFailure(error: Throwable?): Boolean {
        var e = error
        while (e != null) {
            if (e is java.net.UnknownHostException || e is java.net.ConnectException ||
                e is java.io.InterruptedIOException || e is javax.net.ssl.SSLException
            ) {
                return true
            }
            e = e.cause
        }
        return false
    }

    /**
     * For a regular YouTube id whose YouTube stream is unavailable: when the song is a catalog track
     * (metadata known), fetch it from another upload or another enabled audio provider.
     * Returns null when not possible.
     */
    private fun rescueYouTubeDownload(dataSpec: DataSpec, mediaId: String, youtubeError: Throwable?): DataSpec? {
        val dbSong = database.getSongByIdBlocking(mediaId)
        val query = AudioFallbackEngine.queryFor(mediaId, dbSong)
        if (query == null) {
            AudioDiagnostics.warn("download: YouTube failed for $mediaId and it has no catalog metadata")
            return null
        }
        val failedProviders = mutableSetOf<AudioProviderId>()
        val badTracks = mutableSetOf<String>()
        if (youtubeError == null || isNetworkFailure(youtubeError)) {
            failedProviders += AudioProviderId.YOUTUBE
        } else {
            AudioDiagnostics.warn("download: YouTube can't serve $mediaId (${youtubeError.message?.take(160)})")
            badTracks += mediaId
            AudioFallbackEngine.markYouTubeVideoUnplayable(mediaId, query.catalogId)
        }
        // Never append another source's bytes to partial YouTube bytes of the same id.
        runCatching { playerCache.removeResource(mediaId) }
        return downloadViaEngine(dataSpec, mediaId, dbSong, failedProviders, badTracks)
    }

    /**
     * Resolves the stream of a fallback ("mfb:" / legacy "qbzfb:") download through the audio
     * fallback engine — remembered provider first, other uploads / providers when one fails — and
     * records FormatEntity/SongEntity rows for the exporter. Tags come from the catalog metadata, never
     * from the provider's upload. Fails fast (no hang of the dataSync service) when nothing can serve it.
     */
    private fun resolveFallbackDownload(dataSpec: DataSpec, mediaId: String): DataSpec {
        val dbSong = database.getSongByIdBlocking(mediaId)
        return downloadViaEngine(dataSpec, mediaId, dbSong, mutableSetOf(), mutableSetOf())
            ?: error("No audio source for $mediaId")
    }

    private fun downloadViaEngine(
        dataSpec: DataSpec,
        mediaId: String,
        dbSong: com.metrolist.music.db.entities.Song?,
        failedProviders: MutableSet<AudioProviderId>,
        badTracks: MutableSet<String>,
    ): DataSpec? {
        val query = AudioFallbackEngine.queryFor(mediaId, dbSong)
        repeat(5) {
            val plan = runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeout(210_000L) { AudioFallbackEngine.streamPlan(mediaId, dbSong, failedProviders, badTracks) }
                }
            }.onFailure { AudioDiagnostics.warn("download: resolve for $mediaId failed: ${it.message}") }
                .getOrNull() ?: return null

            val streamUri: String
            val format: FormatEntity
            val expiresAt: Long
            when (plan) {
                is AudioFallbackEngine.StreamPlan.Direct -> {
                    val s = plan.stream
                    streamUri = s.uri
                    expiresAt = s.expiresAtMs - 60_000L
                    format = FormatEntity(
                        id = mediaId,
                        itag = -1,
                        mimeType = s.mimeType,
                        codecs = s.codecs,
                        bitrate = s.bitrate,
                        sampleRate = s.sampleRate,
                        // The exporter's preflight needs only the first byte; Media3 learns the real length.
                        contentLength = s.contentLength ?: 0L,
                        loudnessDb = null,
                        perceptualLoudnessDb = null,
                        playbackUrl = null,
                    )
                }
                is AudioFallbackEngine.StreamPlan.YouTube -> {
                    val playback = runBlocking(Dispatchers.IO) {
                        YTPlayerUtils.playerResponseForPlayback(
                            plan.videoId,
                            audioQuality = audioQuality,
                            connectivityManager = connectivityManager,
                            preferAac = false,
                        )
                    }.getOrElse { error ->
                        if (isNetworkFailure(error)) {
                            failedProviders += AudioProviderId.YOUTUBE
                        } else {
                            badTracks += plan.videoId
                            AudioFallbackEngine.markYouTubeVideoUnplayable(plan.videoId, query?.catalogId)
                        }
                        AudioDiagnostics.warn("download: YouTube ${plan.videoId} failed: ${error.message?.take(160)}")
                        return@repeat
                    }
                    val f = playback.format
                    streamUri = "${playback.streamUrl}&range=0-${f.contentLength ?: 10000000}"
                    expiresAt = System.currentTimeMillis() +
                        ((playback.streamExpiresInSeconds - 60).coerceAtLeast(0)) * 1000L
                    format = FormatEntity(
                        id = mediaId,
                        itag = f.itag,
                        mimeType = f.mimeType.split(";")[0],
                        codecs = f.mimeType.substringAfter("codecs=", "").removeSurrounding("\""),
                        bitrate = f.bitrate,
                        sampleRate = f.audioSampleRate,
                        contentLength = f.contentLength ?: 0L,
                        loudnessDb = playback.audioConfig?.loudnessDb,
                        perceptualLoudnessDb = playback.audioConfig?.perceptualLoudnessDb,
                        playbackUrl = null,
                    )
                }
            }
            AudioDiagnostics.info("download: $mediaId via ${plan.provider}")
            // A stream whose service doesn't state its bitrate: the real one, once measured.
            com.metrolist.music.playback.datasource.HlsConcatDataSource.playlistUrlOf(streamUri)?.let { key ->
                com.metrolist.music.playback.datasource.MeasuredAudio.whenMeasured(key) { measured ->
                    database.query {
                        upsert(format.copy(bitrate = measured.bitrate, sampleRate = measured.sampleRate ?: format.sampleRate))
                    }
                }
            }

            val track = SpotifyMetadataRegistry.get(mediaId)
            database.query {
                upsert(format)
                val now = LocalDateTime.now()
                val existing = getSongByIdBlocking(mediaId)?.song
                val updatedSong = if (existing != null) {
                    if (existing.dateDownload == null) existing.copy(dateDownload = now) else existing
                } else {
                    SongEntity(
                        id = mediaId,
                        title = track?.name ?: query?.title ?: "Unknown",
                        duration = ((track?.durationMs?.toLong() ?: query?.durationMs ?: 0L) / 1000).toInt(),
                        thumbnailUrl = track?.let { SpotifyMapper.getTrackThumbnail(it) },
                        dateDownload = now,
                        isDownloaded = false,
                    )
                }
                upsert(updatedSong)
            }
            songUrlCache[mediaId] = streamUri to expiresAt.coerceAtLeast(System.currentTimeMillis())
            return dataSpec.withUri(streamUri.toUri())
        }
        AudioDiagnostics.warn("download: gave up on $mediaId after several sources failed")
        return null
    }

    fun getDownload(songId: String): Flow<Download?> = visibleDownloads.map { it[songId] }.distinctUntilChanged()

    fun release() {
        scope.cancel()
    }

    private companion object {
        /** After the exporter has loaded what it already wrote (and retried its own pending work). */
        const val EXPORT_CATCH_UP_DELAY_MS = 20_000L
    }
}
