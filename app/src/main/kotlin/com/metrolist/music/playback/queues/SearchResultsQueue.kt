/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.models.SpotifyTrack

/**
 * The tracks a search found, played from the one tapped. With repeat on the list goes round (the
 * player loops what is queued, nothing is added); without it, once the list is over the radio goes
 * on from the last track played, then from where the radio has got to.
 */
class SearchResultsQueue(
    private val tracks: List<SpotifyTrack>,
    startIndex: Int,
    mapper: SpotifyYouTubeMapper,
) : SpotifyPagedQueue(startIndex, mapper, null) {

    override val logTag: String = "SearchResultsQueue"

    override val providedTracks: List<SpotifyTrack> = tracks

    override val continues: Boolean = true

    override val maxContinuationRounds: Int = Int.MAX_VALUE

    private var rounds = 0

    override suspend fun continueWith(alreadyQueued: List<SpotifyTrack>): List<SpotifyTrack> {
        val seed = (if (rounds++ == 0) alreadyQueued.lastOrNull() else alreadyQueued.takeLast(RADIO_SEED_WINDOW).randomOrNull())
            ?: return emptyList()
        val seen = alreadyQueued.mapTo(HashSet()) { it.id }
        return runCatching {
            com.metrolist.music.playback.SimilarArtistsRadio.build(seed).filter { it.id !in seen }
        }.getOrDefault(emptyList())
    }

    override suspend fun fetchPage(offset: Int, limit: Int): PageResult = PageResult(emptyList(), tracks.size, 0)

    private companion object {
        const val RADIO_SEED_WINDOW = 10
    }
}
