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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resolves a batch of Spotify tracks to a playable source and downloads them, exposing a
 * human-readable, multi-stage progress — SEARCHING (Spotify→source matching) → DOWNLOADING (bytes)
 * → FORMATTING (transcode to the tagged MP3) → DONE — plus how many were skipped (no match) or
 * failed. The byte download + MP3 export are still handled by the existing Media3 [DownloadUtil] /
 * [com.metrolist.music.utils.DownloadExporter] pipeline; this monitors their state.
 *
 * Every batch is keyed by a `sourceId` (album id, playlist id, "liked_songs"): progress and
 * cancellation are per source, so an album screen only shows ITS OWN progress bar, several albums
 * can download at the same time, and cancelling one leaves the others running.
 */
object SpotifyBatchDownload {

    enum class Phase { SEARCHING, DOWNLOADING, FORMATTING, DONE }

    data class Progress(
        val sourceId: String,
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

    private val _progressBySource = MutableStateFlow<Map<String, Progress>>(emptyMap())

    /** Running batches keyed by sourceId. A screen shows `progressBySource[its id]` only. */
    val progressBySource: StateFlow<Map<String, Progress>> = _progressBySource

    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /** Cancels the batch for [sourceId]: stops enqueuing and removes what it already queued. */
    fun cancel(sourceId: String) {
        if (_progressBySource.value.containsKey(sourceId)) cancelled.add(sourceId)
    }

    fun isRunning(sourceId: String): Boolean = _progressBySource.value.containsKey(sourceId)

    /** Application-lifetime scope so a batch keeps resolving/enqueuing after the user navigates away. */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Global cap on concurrent resolves across all batches, so parallel albums don't hammer YouTube. */
    private val resolvePermits = Semaphore(PARALLELISM)

    /**
     * Fire-and-forget batch download that survives screen navigation. Pass an application context
     * so nothing is leaked. A completion callback is posted on the main thread (not called when the
     * batch was cancelled).
     */
    fun start(
        appContext: Context,
        sourceId: String,
        tracks: List<SpotifyTrack>,
        mapper: SpotifyYouTubeMapper,
        label: String,
        downloads: StateFlow<Map<String, Download>>,
        onFinished: ((Progress) -> Unit)? = null,
    ) {
        if (isRunning(sourceId)) return
        bgScope.launch {
            val result = run(appContext, sourceId, tracks, mapper, label, downloads)
            if (result != null) {
                onFinished?.let { cb -> withContext(Dispatchers.Main) { cb(result) } }
            }
        }
    }

    private fun publish(p: Progress) = _progressBySource.update { it + (p.sourceId to p) }

    /**
     * Resolves [tracks], enqueues the matches, and follows them through download + formatting.
     * Returns the final [Progress], or null when the batch was cancelled.
     */
    suspend fun run(
        context: Context,
        sourceId: String,
        tracks: List<SpotifyTrack>,
        mapper: SpotifyYouTubeMapper,
        label: String,
        downloads: StateFlow<Map<String, Download>>,
    ): Progress? {
        val total = tracks.size
        val resolved = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val enqueuedIds = ConcurrentHashMap.newKeySet<String>()
        cancelled.remove(sourceId)
        fun isCancelled() = sourceId in cancelled

        fun publishSearching() =
            publish(Progress(sourceId, label, Phase.SEARCHING, resolved.get(), total, skipped.get(), 0))
        publishSearching()

        try {
            // Stage 1 — SEARCHING: resolve + enqueue, bounded by the global permit pool.
            withContext(Dispatchers.IO) {
                coroutineScope {
                    tracks.map { track ->
                        async {
                            resolvePermits.withPermit {
                                if (isCancelled()) return@withPermit
                                val metadata = runCatching { mapper.mapToYouTube(track) }.getOrNull()
                                if (isCancelled()) return@withPermit
                                if (metadata != null) {
                                    // Tag from Spotify before the download finishes so the exporter
                                    // never falls back to YouTube's channel/label.
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
                        }
                    }.awaitAll()
                }
            }

            if (isCancelled()) {
                removeAll(context, enqueuedIds)
                return null
            }

            // Stage 2/3 — DOWNLOADING / FORMATTING: watch the enqueued ids until they settle.
            val queued = enqueuedIds.size
            if (queued > 0) {
                var ticks = 0
                while (ticks++ < MAX_MONITOR_TICKS) {
                    if (isCancelled()) {
                        removeAll(context, enqueuedIds)
                        return null
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
                    publish(Progress(sourceId, label, phase, completed, queued, skipped.get(), failed))
                    if (completed + failed >= queued && formatting == 0 && downloading == 0) break
                    delay(500)
                }
            }

            val map = downloads.value
            val done = enqueuedIds.count { map[it]?.state == Download.STATE_COMPLETED }
            val failed = enqueuedIds.count { map[it]?.state == Download.STATE_FAILED }
            return Progress(sourceId, label, Phase.DONE, done, enqueuedIds.size, skipped.get(), failed)
        } finally {
            cancelled.remove(sourceId)
            _progressBySource.update { it - sourceId }
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

    /** Removes every download this batch queued (used when the user cancels). */
    private fun removeAll(context: Context, ids: Set<String>) {
        for (id in ids) {
            runCatching {
                DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, id, false)
            }
        }
    }

    /** How many tracks to resolve concurrently (across all batches). */
    private const val PARALLELISM = 6

    /** Safety cap on the download-monitor loop (500ms ticks) so it can never spin forever. */
    private const val MAX_MONITOR_TICKS = 2 * 60 * 60 // ~1h
}
