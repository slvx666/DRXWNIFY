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
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.resolver.FallbackIds
import com.metrolist.music.utils.DownloadExportState
import com.metrolist.music.utils.DownloadExporter
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
        /** Tracks whose file is already in the user's folder (this run plus earlier ones). */
        val current: Int,
        /** Every track of the source - the bar always describes the whole list. */
        val total: Int,
        /** Tracks with no source match (skipped, not an error). */
        val skipped: Int,
        /** Tracks whose download or export failed. */
        val failed: Int,
        /** Of [current], how many were already downloaded before this run started. */
        val alreadyDownloaded: Int = 0,
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
        database: MusicDatabase,
        /** Non-null for a playlist: every track of this batch is exported into ONE folder with this name. */
        folderName: String? = null,
        onFinished: ((Progress) -> Unit)? = null,
    ) {
        if (isRunning(sourceId)) return
        bgScope.launch {
            val result = run(appContext, sourceId, tracks, mapper, label, downloads, database, folderName)
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
        database: MusicDatabase,
        folderName: String? = null,
    ): Progress? {
        val total = tracks.size
        val resolved = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val enqueuedIds = ConcurrentHashMap.newKeySet<String>()
        cancelled.remove(sourceId)
        fun isCancelled() = sourceId in cancelled

        // Tracks already sitting in the user's folder are not searched or downloaded again: pressing
        // "download all" a second time now only picks up what is actually missing.
        val alreadyDone = downloadedTrackIds(database, tracks)
        DownloadIssues.clear(context, alreadyDone)
        // media id -> catalog track id, to remember per track why it didn't download.
        val trackOfMedia = ConcurrentHashMap<String, String>()
        val todo = tracks.filterNot { it.id in alreadyDone }
        val already = alreadyDone.size

        fun publishSearching() =
            publish(
                Progress(
                    sourceId, label, Phase.SEARCHING,
                    // While searching the bar moves with every track looked up.
                    current = already + resolved.get(), total = total, skipped = skipped.get(), failed = 0,
                    alreadyDownloaded = already,
                ),
            )
        publishSearching()
        if (todo.isEmpty()) {
            _progressBySource.update { it - sourceId }
            return Progress(sourceId, label, Phase.DONE, already, total, 0, 0, already)
        }

        try {
            // Stage 1 — SEARCHING: resolve + enqueue, bounded by the global permit pool.
            withContext(Dispatchers.IO) {
                coroutineScope {
                    todo.map { track ->
                        async {
                            resolvePermits.withPermit {
                                if (isCancelled()) return@withPermit
                                val metadata = runCatching { mapper.mapToYouTube(track) }.getOrNull()
                                if (isCancelled()) return@withPermit
                                if (metadata != null) {
                                    // Tag from Spotify before the download finishes so the exporter
                                    // never falls back to YouTube's channel/label.
                                    mapper.persistSpotifyMetadata(metadata, track)
                                    if (folderName != null) {
                                        // Recorded before enqueuing: the exporter reads it when the
                                        // track's bytes are done, which can be immediate.
                                        DownloadExporter.rememberPlaylistFolder(context, listOf(metadata.id), folderName)
                                    }
                                    enqueue(context, metadata.id, metadata.title)
                                    enqueuedIds.add(metadata.id)
                                    trackOfMedia[metadata.id] = track.id
                                } else {
                                    skipped.incrementAndGet()
                                    DownloadIssues.set(context, track.id, DownloadIssues.Reason.NOT_FOUND)
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
                    val counts = countStates(enqueuedIds, downloads.value)
                    val (downloading, formatting) = counts.downloading to counts.formatting
                    val (completed, failed) = counts.completed to counts.failed
                    val phase = when {
                        downloading > 0 -> Phase.DOWNLOADING
                        formatting > 0 -> Phase.FORMATTING
                        else -> Phase.DONE
                    }
                    publish(
                        Progress(
                            sourceId, label, phase,
                            current = already + completed, total = total,
                            skipped = skipped.get(), failed = failed, alreadyDownloaded = already,
                        ),
                    )
                    if (completed + failed >= queued && formatting == 0 && downloading == 0) break
                    delay(500)
                }
            }

            val counts = countStates(enqueuedIds, downloads.value)
            recordOutcomes(context, enqueuedIds, trackOfMedia, downloads.value)
            return Progress(
                sourceId, label, Phase.DONE,
                current = already + counts.completed, total = total,
                skipped = skipped.get(), failed = counts.failed, alreadyDownloaded = already,
            )
        } finally {
            cancelled.remove(sourceId)
            _progressBySource.update { it - sourceId }
        }
    }

    /**
     * Ids of [tracks] whose file is already in the user's folder - checked without any network
     * request: a track downloaded through one of the audio sources always has the media id
     * "mfb:<track id>", and a YouTube-matched one is in the local match cache.
     */
    suspend fun downloadedTrackIds(database: MusicDatabase, tracks: List<SpotifyTrack>): Set<String> =
        withContext(Dispatchers.IO) {
            val exported = DownloadExportState.exported.value
            if (exported.isEmpty() || tracks.isEmpty()) return@withContext emptySet()
            val done = HashSet<String>()
            for (track in tracks) {
                if (FallbackIds.of(track.id) in exported) done.add(track.id)
            }
            val rest = tracks.filterNot { it.id in done }
            for (chunk in rest.chunked(400)) {
                val matches = runCatching { database.getSpotifyMatchesBySpotifyIds(chunk.map { it.id }) }
                    .getOrElse { emptyList() }
                for (match in matches) {
                    if (match.youtubeId in exported) done.add(match.spotifyId)
                }
            }
            done
        }

    /** How many of [tracks] are already downloaded (for the "N of M" label on a screen). */
    suspend fun downloadedCount(database: MusicDatabase, tracks: List<SpotifyTrack>): Int =
        downloadedTrackIds(database, tracks).size

    private data class Counts(val downloading: Int, val formatting: Int, val completed: Int, val failed: Int)

    /**
     * A track counts as done only once its FILE is in the user's folder, not when the bytes are
     * cached: the transcode to the tagged MP3 happens afterwards and takes far longer, so counting
     * cached bytes made the progress claim many more tracks than the folder actually had.
     */
    /** Remembers, per catalog track, whether the batch got it into the folder or why not. */
    private fun recordOutcomes(
        context: Context,
        mediaIds: Set<String>,
        trackOfMedia: Map<String, String>,
        map: Map<String, Download>,
    ) {
        val exported = DownloadExportState.exported.value
        val exportFailed = DownloadExportState.failed.value
        val done = mutableListOf<String>()
        for (mediaId in mediaIds) {
            val trackId = trackOfMedia[mediaId] ?: continue
            when {
                mediaId in exported -> done += trackId
                mediaId in exportFailed -> DownloadIssues.set(context, trackId, DownloadIssues.Reason.SAVE_FAILED)
                map[mediaId]?.state == Download.STATE_FAILED ->
                    DownloadIssues.set(context, trackId, DownloadIssues.Reason.DOWNLOAD_FAILED)
            }
        }
        DownloadIssues.clear(context, done)
    }

    private fun countStates(ids: Set<String>, map: Map<String, Download>): Counts {
        val exported = DownloadExportState.exported.value
        val exportFailed = DownloadExportState.failed.value
        val exporting = DownloadExportState.exporting.value
        var downloading = 0
        var formatting = 0
        var completed = 0
        var failed = 0
        for (id in ids) {
            when {
                id in exported -> completed++
                id in exportFailed -> failed++
                id in exporting -> formatting++
                map[id]?.state == Download.STATE_FAILED -> failed++
                // Bytes are cached; the file is still being assembled/tagged.
                map[id]?.state == Download.STATE_COMPLETED -> formatting++
                else -> downloading++
            }
        }
        return Counts(downloading, formatting, completed, failed)
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
