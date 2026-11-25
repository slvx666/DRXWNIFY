/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.SimilarArtistsRadio
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.sync.withLock

/**
 * A radio from one track (a "For you" cover): [start] plays at once, then songs of artists similar
 * to its artist, and on without end — each next round grows from a song of the round before, every
 * other round from [start] again, so a grindcore track stays among grindcore and its neighbours
 * instead of drifting off to whatever the listener likes in general.
 */
class TrackRadioQueue(
    private val start: SpotifyTrack,
    mapper: SpotifyYouTubeMapper,
    preload: MediaMetadata?,
) : SpotifyPagedQueue(startIndex = 0, mapper = mapper, preloadItem = preload) {

    override val logTag: String = "TrackRadioQueue"

    override val providedTracks: List<SpotifyTrack> = listOf(start)

    override val continues: Boolean = true

    override val allRecommended: Boolean = true

    override val maxContinuationRounds: Int = Int.MAX_VALUE

    override val quickItems: Boolean = true

    // Two empty rounds (the catalog failing for a moment): the player's own radio takes over then.
    override val maxFruitlessRounds: Int = 2

    private val continuation = kotlinx.coroutines.sync.Mutex()
    private var lastRound: List<SpotifyTrack> = emptyList()
    private var rounds = 0

    override suspend fun continueWith(alreadyQueued: List<SpotifyTrack>): List<SpotifyTrack> =
        continuation.withLock {
            val queued = alreadyQueued.mapTo(HashSet()) { it.id }
            val seed = if (rounds % 2 == 0 || lastRound.isEmpty()) start else lastRound.random()
            rounds++
            val more = kotlinx.coroutines.withTimeoutOrNull(ROUND_TIMEOUT_MS) {
                runCatching { SimilarArtistsRadio.build(seed, limit = ROUND) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrNull()
            }.orEmpty().filter { it.id !in queued && it.id != start.id }
            if (more.isNotEmpty()) lastRound = more
            more
        }

    override suspend fun fetchPage(offset: Int, limit: Int): PageResult =
        PageResult(tracks = emptyList(), total = 0, rawCount = 0)

    private companion object {
        const val ROUND = 30
        const val ROUND_TIMEOUT_MS = 9_000L
    }
}
