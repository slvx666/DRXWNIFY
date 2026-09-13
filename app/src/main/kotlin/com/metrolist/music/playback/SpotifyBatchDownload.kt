/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.metrolist.music.utils.DownloadExportState
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resolves a batch of Spotify tracks to a playable source and downloads them, exposing a
 * human-readable, multi-stage [progress] so the user can see what is actually happening —
 * SEARCHING (Spotify→source matching) → DOWNLOADING (bytes) → FORMATTING (transcode to the tagged
 * MP3) → DONE — plus how many were skipped (no match) or failed. The byte download + MP3 export are
 * still handled by the existing Media3 [DownloadUtil] / [com.metrolist.music.utils.DownloadExporter]
 * pipeline; this monitors their state and does not touch storage itself.
 */
object SpotifyBatchDownload {

    enum class Phase { SEARCHING, DOWNLOADING, FORMATTING, DONE }

    data class Progress(
        val label: String,
        val phase: Phase,
        /** Phase-relative numerator (resolved so far, or fully-downloaded so far). */
        val current: Int,
        /** Phase-relative denominator (total tracks while searching, matched count afterwards). */
        val total: Int,
        /** Tracks with no source match (skipped, not an error). */
        val skipped: Int,
        /** Tracks whose download failed. */
        val failed: Int,
    ) {
        val fraction: Float get() = if (total <= 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f)
    }

    private val _progress = MutableStateFlow<Progress?>(null)

    /** Non-null while a batch is running (and briefly after, until [run] returns). */
    val progress: StateFlow<Progress?> = _progress

    /** Serializes batches so two "download all" taps don't interleave their progress. */
    private val mutex = Mutex()

    /**
     * Set by [cancel] to stop the active batch: no further tracks are enqueued, the monitor loop
     * exits, and everything this batch has already queued is removed from the download pipeline.
     */
    @Volatile
    private var cancelled = false

    /** Requests cancellation of the running batch (P14). No-op if nothing is running. */
    fun cancel() {
        cancelled = true
    }

    /** Application-lifetime scope so a batch keeps resolving/enqueuing after the user navigates away. */
    private val bgScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )

    /**
     * Fire-and-forget batch download that survives screen navigation. Pass an application context
     * so nothing is leaked. Progress is observable via [progress]; a completion toast is posted on
     * the main thread. Use this from screens instead of launching [run] on a composition scope,
     * which gets cancelled the moment the user opens another album (P: background album download).
     */
    fun start(
        appContext: Context,
        tracks: List<SpotifyTrack>,
        mapper: SpotifyYouTubeMapper,
        label: String,
        downloads: StateFlow<Map<String, Download>>,
        onFinished: ((Progress) -> Unit)? = null,
    ) {
        bgScope.launch {
            val result = run(appContext, tracks, mapper, label, downloads)
            onFinished?.let { cb ->
                withContext(Dispatchers.Main) { cb(result) }
            }
        }
    }

    /** How many tracks to resolve concurrently. Bounded so we don't hammer the search backends. */
    private const val PARALLELISM = 6

    /** Safety cap on the download-monitor loop (500ms ticks) so it can never spin forever. */
    private const val MAX_MONITOR_TICKS = 2 * 60 * 60 // ~1h

    /**
     * Resolves [tracks], enqueues the matches, and follows them through download + formatting.
     * Suspends until everything has finished (or failed) and returns the final [Progress]. Safe to
     * call from a UI coroutine; the work runs on [Dispatchers.IO]. [downloads] is the live Media3
     * download map (e.g. `LocalDownloadUtil.current.downloads`) used to track the byte-download stage.
     */
    suspend fun run(
        context: Context,
        tracks: List<SpotifyTrack>,
        mapper: SpotifyYouTubeMapper,
        label: String,
        downloads: StateFlow<Map<String, Download>>,
    ): Progress = mutex.withLock {
        val total = tracks.size
        val resolved = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val enqueuedIds = ConcurrentHashMap.newKeySet<String>()
        cancelled = false

        fun publishSearching() {
            _progress.value = Progress(label, Phase.SEARCHING, resolved.get(), total, skipped.get(), 0)
        }
        publishSearching()

        try {
            // Stage 1 — SEARCHING: resolve + enqueue in bounded parallel.
            withContext(Dispatchers.IO) {
                tracks.chunked(PARALLELISM).forEach { chunk ->
                    if (cancelled) return@forEach
                    coroutineScope {
                        chunk.map { track ->
                            async {
                                if (cancelled) return@async
                                val metadata = runCatching { mapper.mapToYouTube(track) }.getOrNull()
                                if (metadata != null) {
                                    // Tag from Spotify: store the Spotify metadata before the download
                                    // finishes so the exporter never falls back to YouTube's channel/label.
                                    mapper.persistSpotifyMetadata(metadata, track)
                                    enqueue(context, metadata.id, metadata.title)
                                    enqueuedIds.add(metadata.id)
                                } else {
                                    skipped.incrementAndGet()
                                    Timber.w("SpotifyBatchDownload: skip '${track.name}' — no match")
                                }
                                resolved.incrementAndGet()
                                publishSearching()
                            }
                        }.awaitAll()
                    }
                }
            }

            if (cancelled) {
                removeAll(context, enqueuedIds)
                return@withLock Progress(label, Phase.DONE, 0, enqueuedIds.size, skipped.get(), 0)
            }

            // Stage 2/3 — DOWNLOADING / FORMATTING: watch the enqueued ids until they settle.
            val queued = enqueuedIds.size
            if (queued > 0) {
                var ticks = 0
                while (ticks++ < MAX_MONITOR_TICKS) {
                    if (cancelled) {
                        removeAll(context, enqueuedIds)
                        break
                    }
                    val map = downloads.value
                    val exporting = DownloadExportState.exporting.value
                    var downloading = 0
                    var formatting = 0
                    var completed = 0
                    var failed = 0
                    for (id in enqueuedIds) {
                        when {
                            id in exporting -> formatting++
                            map[id]?.state == Download.STATE_COMPLETED -> completed++
                            map[id]?.state == Download.STATE_FAILED -> failed++
                            else -> downloading++
                        }
                    }
                    val phase = when {
                        downloading > 0 -> Phase.DOWNLOADING
                        formatting > 0 -> Phase.FORMATTING
                        else -> Phase.DONE
                    }
                    _progress.value = Progress(label, phase, completed, queued, skipped.get(), failed)
                    if (completed + failed >= queued && formatting == 0 && downloading == 0) break
                    delay(500)
                }
            }

            val map = downloads.value
            val done = enqueuedIds.count { map[it]?.state == Download.STATE_COMPLETED }
            val failed = enqueuedIds.count { map[it]?.state == Download.STATE_FAILED }
            Progress(label, Phase.DONE, done, enqueuedIds.size, skipped.get(), failed)
        } finally {
            _progress.value = null
        }
    }

    private fun enqueue(context: Context, mediaId: String, title: String) {
        val request = DownloadRequest
            .Builder(mediaId, mediaId.toUri())
            .setCustomCacheKey(mediaId)
            .setData(title.toByteArray())
            .build()
        DownloadService.sendAddDownload(context, ExoDownloadService::class.java, request, false)
    }

    /** Removes every download this batch queued (used when the user cancels — P14). */
    private fun removeAll(context: Context, ids: Set<String>) {
        for (id in ids) {
            runCatching {
                DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, id, false)
            }
        }
    }
}
