/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import com.metrolist.music.extensions.toMediaItem
import androidx.media3.common.MediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Shared implementation for Spotify-backed paginated queues (playlist, liked
 * songs). Owns all pagination state, fast-start windowing, progressive
 * batched resolution, and source-level shuffle. Subclasses only supply:
 *  - how one page of tracks is fetched ([fetchPage]), and
 *  - an optional pre-ordered list to play verbatim ([providedTracks]).
 *
 * Optimized for fast playback start: [getInitialStatus] resolves only the
 * selected track plus a couple of neighbors; the rest are resolved in
 * [nextPage] batches as the player approaches the end of the loaded queue.
 */
abstract class SpotifyPagedQueue(
    protected val startIndex: Int,
    protected val mapper: SpotifyYouTubeMapper,
    override val preloadItem: MediaMetadata?,
) : Queue {

    /** Short tag used in log messages (e.g. "SpotifyPlaylistQueue"). */
    protected abstract val logTag: String

    /**
     * Pre-ordered list of tracks to play verbatim (already filtered exactly as
     * the subclass wants, e.g. non-local), matching what the UI displays. When
     * non-null, [startIndex] indexes into THIS list and no Spotify pagination
     * happens. When null, tracks are paged from the API via [fetchPage].
     */
    protected abstract val providedTracks: List<SpotifyTrack>?

    /** Result of fetching one API page. */
    protected data class PageResult(
        /** Filtered (e.g. non-local) tracks from this page. */
        val tracks: List<SpotifyTrack>,
        /** Total number of tracks the API reports for the source. */
        val total: Int,
        /** Raw item count in this page — advances the API offset (not the filtered size). */
        val rawCount: Int,
    )

    /** Fetches a single page of tracks starting at [offset]. */
    protected abstract suspend fun fetchPage(offset: Int, limit: Int): PageResult

    /**
     * True for queues that go on after their own tracks run out (e.g. an artist's popular tracks
     * followed by more of the artist and similar artists) instead of simply stopping.
     */
    protected open val continues: Boolean = false

    /** More tracks to append once everything so far was queued; empty when there is nothing left. */
    protected open suspend fun continueWith(alreadyQueued: List<SpotifyTrack>): List<SpotifyTrack> = emptyList()

    /**
     * Queue tracks at once with what is known about them ([SpotifyYouTubeMapper.quickMetadata])
     * instead of finding each one's audio first; the audio is found when a track is reached.
     *
     * On for every list: looking the audio up ahead made the tapped track wait for its neighbours,
     * a track whose lookup failed dropped out and shifted the queue (the tapped row then played
     * another song), and the parallel lookups competed for a weak connection with the one track
     * actually being played.
     */
    protected open val quickItems: Boolean = true

    private suspend fun toItem(track: SpotifyTrack): MediaItem? =
        if (quickItems) mapper.quickMetadata(track).toMediaItem() else mapper.resolveToMediaItem(track)

    /** How many times [continueWith] may extend the queue; an endless radio lifts the limit. */
    protected open val maxContinuationRounds: Int = MAX_CONTINUATION_ROUNDS

    private var continuationRounds = 0

    // All Spotify tracks fetched so far (may span multiple API pages)
    private val allTracks = mutableListOf<SpotifyTrack>()

    // Index into [allTracks] for the next batch to resolve to YouTube
    private var resolveOffset = 0

    // Spotify API pagination state
    private var apiFetchOffset = 0
    private var apiTotal = 0
    private var apiHasMore = true

    override suspend fun getInitialStatus(): Queue.Status = withContext(Dispatchers.IO) {
        try {
            val provided = providedTracks
            if (provided != null) {
                allTracks.addAll(provided)
                apiTotal = provided.size
                apiFetchOffset = apiTotal
                apiHasMore = false
            } else {
                val page = fetchPage(offset = 0, limit = SPOTIFY_PAGE_SIZE)
                apiTotal = page.total
                allTracks.addAll(page.tracks)
                apiFetchOffset = page.rawCount
                apiHasMore = apiFetchOffset < apiTotal
            }

            while (startIndex >= allTracks.size && apiHasMore) {
                fetchNextApiPage()
            }

            val targetIndex = startIndex.coerceIn(0, (allTracks.size - 1).coerceAtLeast(0))

            // Quick items cost nothing, so the list is queued around the tapped track as the user
            // sees it ("previous" goes to the row above). Otherwise only a tiny window is looked
            // up for a fast start; the rest of the queue is populated via nextPage().
            val before = if (quickItems) QUICK_START_BEFORE else FAST_START_BEFORE
            val after = if (quickItems) QUICK_START_AFTER else FAST_START_AFTER
            val windowStart = (targetIndex - before).coerceAtLeast(0)
            val windowEnd = (targetIndex + after + 1).coerceAtMost(allTracks.size)
            val windowTracks = allTracks.subList(windowStart, windowEnd)

            val resolvedItems = coroutineScope {
                windowTracks.map { track -> async { toItem(track) } }
                    .awaitAll()
                    .filterNotNull()
            }

            if (resolvedItems.isEmpty()) {
                Timber.w("$logTag: Could not resolve any track in initial window")
                return@withContext Queue.Status(title = null, items = emptyList(), mediaItemIndex = 0)
            }

            resolveOffset = windowEnd

            val mediaItemIndex = (targetIndex - windowStart)
                .coerceIn(0, (resolvedItems.size - 1).coerceAtLeast(0))

            Timber.d("$logTag: Fast-start resolved ${resolvedItems.size} tracks " +
                "(window $windowStart..$windowEnd, target=$targetIndex, total=$apiTotal)")

            Queue.Status(
                title = null,
                items = resolvedItems,
                mediaItemIndex = mediaItemIndex,
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "$logTag: Failed initial fetch")
            Queue.Status(title = null, items = emptyList(), mediaItemIndex = 0)
        }
    }

    override suspend fun getFullStatus(): Queue.Status? = withContext(Dispatchers.IO) {
        try {
            // Build the full list from scratch so we always include tracks 0..N
            // (not just from startIndex onwards).
            allTracks.clear()
            val provided = providedTracks
            if (provided != null) {
                allTracks.addAll(provided)
                apiTotal = provided.size
                apiFetchOffset = apiTotal
                apiHasMore = false
            } else {
                apiFetchOffset = 0
                apiHasMore = true
                val page = fetchPage(offset = 0, limit = SPOTIFY_PAGE_SIZE)
                apiTotal = page.total
                allTracks.addAll(page.tracks)
                apiFetchOffset = page.rawCount
                apiHasMore = apiFetchOffset < apiTotal
            }
            while (apiHasMore) {
                fetchNextApiPage()
            }
            if (allTracks.isEmpty()) return@withContext null
            val targetIndex = startIndex.coerceIn(0, allTracks.size - 1)
            // Resolve all tracks in parallel batches (order preserved) instead of
            // one-by-one; on a large source the serial loop blocked shuffle-all for
            // tens of seconds. Batch size matches nextPage() to bound concurrency.
            val resolved = ArrayList<MediaItem?>(allTracks.size)
            for (chunk in allTracks.chunked(RESOLVE_BATCH_SIZE)) {
                resolved += coroutineScope {
                    chunk.map { track -> async { toItem(track) } }.awaitAll()
                }
            }
            val resolvedItems = mutableListOf<MediaItem>()
            var mediaItemIndex = 0
            for (i in allTracks.indices) {
                if (i == targetIndex) mediaItemIndex = resolvedItems.size
                resolved[i]?.let { resolvedItems.add(it) }
            }
            if (resolvedItems.isEmpty()) return@withContext null
            mediaItemIndex = mediaItemIndex.coerceIn(0, resolvedItems.size - 1)
            resolveOffset = allTracks.size
            apiHasMore = false
            Timber.d("$logTag: getFullStatus resolved ${resolvedItems.size} tracks (startIndex=$targetIndex)")
            Queue.Status(title = null, items = resolvedItems, mediaItemIndex = mediaItemIndex)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "$logTag: getFullStatus failed")
            null
        }
    }

    override suspend fun shuffleRemainingTracks() = withContext(Dispatchers.IO) {
        while (apiHasMore) {
            fetchNextApiPage()
        }
        if (resolveOffset < allTracks.size) {
            val remaining = allTracks.subList(resolveOffset, allTracks.size)
            val shuffled = remaining.shuffled()
            for (i in shuffled.indices) {
                remaining[i] = shuffled[i]
            }
            Timber.d("$logTag: Shuffled ${remaining.size} remaining tracks " +
                "(resolveOffset=$resolveOffset, total=${allTracks.size})")
        }
    }

    override fun hasNextPage(): Boolean =
        resolveOffset < allTracks.size || apiHasMore || (continues && continuationRounds < maxContinuationRounds)

    override suspend fun nextPage(): List<MediaItem> = withContext(Dispatchers.IO) {
        // If we've resolved all fetched tracks but the API has more, fetch another page
        if (resolveOffset >= allTracks.size && apiHasMore) {
            fetchNextApiPage()
        }

        if (resolveOffset >= allTracks.size && !apiHasMore && continues) {
            var fruitless = 0
            while (continuationRounds < maxContinuationRounds && resolveOffset >= allTracks.size) {
                continuationRounds++
                val more = runCatching { continueWith(allTracks.toList()) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrDefault(emptyList())
                val known = allTracks.mapTo(HashSet()) { it.id }
                val fresh = more.filter { it.id !in known }
                allTracks.addAll(fresh)
                // An endless queue must not spin on the network when a seed brings nothing new.
                if (fresh.isEmpty() && ++fruitless >= MAX_FRUITLESS_ROUNDS) break
            }
        }

        if (resolveOffset >= allTracks.size) {
            return@withContext emptyList()
        }

        // Resolve the next batch
        val end = (resolveOffset + RESOLVE_BATCH_SIZE).coerceAtMost(allTracks.size)
        val batch = allTracks.subList(resolveOffset, end)
        resolveOffset = end

        Timber.d("$logTag: Resolving batch of ${batch.size} tracks " +
            "(offset=$resolveOffset/${allTracks.size}, apiTotal=$apiTotal)")

        coroutineScope {
            batch.map { track -> async { toItem(track) } }
                .awaitAll()
                .filterNotNull()
        }
    }

    private suspend fun fetchNextApiPage() {
        if (!apiHasMore) return
        try {
            val page = fetchPage(offset = apiFetchOffset, limit = SPOTIFY_PAGE_SIZE)
            allTracks.addAll(page.tracks)
            apiFetchOffset += page.rawCount
            apiHasMore = apiFetchOffset < apiTotal
            Timber.d("$logTag: Fetched API page, now have ${allTracks.size} tracks")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "$logTag: Failed to fetch next API page")
            apiHasMore = false
        }
    }

    companion object {
        private const val SPOTIFY_PAGE_SIZE = 50
        private const val RESOLVE_BATCH_SIZE = 20
        /** How many times a continuing queue may extend itself (more by the artist, then similar artists). */
        private const val MAX_CONTINUATION_ROUNDS = 4
        /** Resolve only the target + a few neighbors for instant playback start. */
        private const val FAST_START_BEFORE = 0
        private const val FAST_START_AFTER = 2
        private const val MAX_FRUITLESS_ROUNDS = 3
        private const val QUICK_START_BEFORE = 100
        private const val QUICK_START_AFTER = 200
    }
}
