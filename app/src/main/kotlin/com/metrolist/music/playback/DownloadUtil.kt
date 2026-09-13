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
import com.metrolist.innertube.YouTube
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.constants.QobuzAudioQuality
import com.metrolist.music.constants.QobuzAudioQualityKey
import com.metrolist.music.constants.QobuzBackend
import com.metrolist.music.constants.QobuzBackendKey
import com.metrolist.music.constants.QobuzCountryKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.di.DownloadCache
import com.metrolist.music.di.PlayerCache
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.qobuz.QobuzAudioProvider
import com.metrolist.music.utils.DownloadExporter
import com.metrolist.music.utils.YTPlayerUtils
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.enumPreference
import com.metrolist.music.utils.get
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
import kotlinx.coroutines.flow.map
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
    private val downloadExporter: DownloadExporter,
) {
    private val TAG = "DownloadUtil"
    private val appContext = context
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val songUrlCache = HashMap<String, Pair<String, Long>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    OkHttpDataSource.Factory(
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
                            .build(),
                    ),
                ),
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            val length = if (dataSpec.length >= 0) dataSpec.length else 1

            // Qobuz-fallback track (no YouTube match): resolve the Qobuz stream and record a
            // FormatEntity/SongEntity so the exporter can transcode it to MP3 afterwards. Keyed on
            // the synthetic "qbzfb:" id, so ordinary YouTube downloads are untouched.
            if (SpotifyMetadataRegistry.isQobuzFallbackId(mediaId)) {
                if (playerCache.isCached(mediaId, dataSpec.position, length)) return@Factory dataSpec
                songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                    return@Factory dataSpec.withUri(it.first.toUri())
                }
                val resolved = resolveQobuzFallback(mediaId)
                    ?: error("Qobuz fallback: cannot resolve $mediaId for download")
                val track = SpotifyMetadataRegistry.get(mediaId)
                database.query {
                    upsert(
                        FormatEntity(
                            id = mediaId,
                            itag = -1,
                            mimeType = resolved.mimeType.substringBefore(';').trim(),
                            codecs = resolved.codecs,
                            bitrate = resolved.bitrate,
                            sampleRate = resolved.sampleRate,
                            // Unknown up front for Qobuz; the exporter's preflight only needs the
                            // first byte, and Media3 discovers the real length from HTTP headers.
                            contentLength = 0L,
                            loudnessDb = null,
                            perceptualLoudnessDb = null,
                            playbackUrl = null,
                        ),
                    )
                    val now = LocalDateTime.now()
                    val existing = getSongByIdBlocking(mediaId)?.song
                    val updatedSong = if (existing != null) {
                        if (existing.dateDownload == null) existing.copy(dateDownload = now) else existing
                    } else {
                        SongEntity(
                            id = mediaId,
                            title = track?.name ?: "Unknown",
                            duration = ((track?.durationMs ?: 0) / 1000),
                            thumbnailUrl = track?.let { SpotifyMapper.getTrackThumbnail(it) },
                            dateDownload = now,
                            isDownloaded = false,
                        )
                    }
                    upsert(updatedSong)
                }
                val expiresAt = (resolved.expiresAtMs - 60_000L).coerceAtLeast(System.currentTimeMillis())
                songUrlCache[mediaId] = resolved.mediaUri to expiresAt
                return@Factory dataSpec.withUri(resolved.mediaUri.toUri())
            }

            if (playerCache.isCached(mediaId, dataSpec.position, length)) {
                return@Factory dataSpec
            }

            songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                return@Factory dataSpec.withUri(it.first.toUri())
            }

            val playbackData = runBlocking(Dispatchers.IO) {
                YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    // Prefer AAC for downloads so the exporter can copy the stream without transcoding.
                    preferAac = true,
                )
            }.getOrThrow()
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

                        runCatching {
                            database.updateDownloadedInfo(downloadId, false, null)
                        }.onSuccess {
                            downloads.update { map ->
                                map.toMutableMap().apply {
                                    remove(downloadId)
                                }
                            }
                            // Allow a future re-download of this id to export again.
                            scope.launch { downloadExporter.forgetExported(downloadId) }
                            Timber.tag(TAG).d("Successfully removed download $downloadId from in-memory map")
                        }.onFailure { error ->
                            Timber.tag(TAG).e(error, "Failed to update database for removed download $downloadId, keeping in-memory entry")
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
    }

    /**
     * Resolves the Qobuz stream for a "qbzfb:" fallback download, building the query from the
     * Spotify metadata registered by [SpotifyYouTubeMapper] plus the user's Qobuz backend/country/
     * quality settings. Returns null when metadata is missing or Qobuz has no match.
     */
    private fun resolveQobuzFallback(mediaId: String): QobuzAudioProvider.Resolved? {
        val track = SpotifyMetadataRegistry.get(mediaId) ?: return null
        val artists = track.artists.map { it.name }.filter { it.isNotBlank() }
        if (artists.isEmpty()) return null

        val backendPref = appContext.dataStore.get(QobuzBackendKey).toEnum(QobuzBackend.MONOKENNY)
        val resolverBackend = when (backendPref) {
            QobuzBackend.MONOKENNY -> QobuzAudioProvider.ResolverBackend.MONOKENNY
            QobuzBackend.JUMO -> QobuzAudioProvider.ResolverBackend.JUMO
            QobuzBackend.SQUID -> QobuzAudioProvider.ResolverBackend.SQUID
            QobuzBackend.TRYPT -> QobuzAudioProvider.ResolverBackend.TRYPT
        }
        val country = appContext.dataStore.get(QobuzCountryKey, "US")
            .trim().uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US"
        val quality = appContext.dataStore.get(QobuzAudioQualityKey).toEnum(QobuzAudioQuality.CD_QUALITY)

        val query = QobuzAudioProvider.Query(
            mediaId = mediaId,
            title = track.name,
            artists = artists,
            album = track.album?.name,
            isrc = track.isrc?.takeIf { it.isNotBlank() },
            durationMs = track.durationMs.toLong().takeIf { it > 0 },
            countryCode = country,
            backend = resolverBackend,
            qualityCode = QobuzAudioProvider.qualityCodeFor(quality),
        )
        return runCatching {
            runBlocking(Dispatchers.IO) { withTimeout(20_000L) { QobuzAudioProvider.resolve(query) } }
        }.onFailure { e ->
            Timber.tag(TAG).w("Qobuz fallback download resolve failed for %s: %s", mediaId, e.message)
        }.getOrNull()
    }

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    fun release() {
        scope.cancel()
    }
}
