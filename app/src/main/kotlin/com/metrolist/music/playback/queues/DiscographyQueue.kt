/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import com.metrolist.music.catalog.Catalog
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyTrack

/**
 * An artist's discography as one list, in the order the screen shows it: each release's tracks,
 * then the next release's. [startIndex] counts tracks from the first release. Releases are looked
 * up as the queue reaches them. Shuffle mixes the whole discography, repeat plays it round, and
 * without repeat the radio goes on from the last track played once everything was heard.
 */
class DiscographyQueue(
    private val releases: List<SpotifyAlbum>,
    startIndex: Int,
    mapper: SpotifyYouTubeMapper,
) : SpotifyPagedQueue(startIndex, mapper, null) {

    override val logTag: String = "DiscographyQueue"

    override val providedTracks: List<SpotifyTrack>? = null

    override val continues: Boolean = true

    override val maxContinuationRounds: Int = Int.MAX_VALUE

    /** The next release to look up. */
    private var nextRelease = 0
    private var fetched = 0
    private var rounds = 0

    /** An upper bound for the pager (the real count is known only once every release was read). */
    private val declaredTotal = releases.sumOf { it.totalTracks.coerceAtLeast(1) }.coerceAtLeast(1) + releases.size * 50

    override suspend fun fetchPage(offset: Int, limit: Int): PageResult {
        val out = mutableListOf<SpotifyTrack>()
        while (nextRelease < releases.size && out.size < limit) {
            out += tracksOf(releases[nextRelease++])
        }
        fetched += out.size
        // Every release read: the pager is told it has everything (it stops asking).
        val raw = if (nextRelease >= releases.size) declaredTotal - offset else out.size
        return PageResult(out, declaredTotal, raw.coerceAtLeast(out.size))
    }

    override suspend fun continueWith(alreadyQueued: List<SpotifyTrack>): List<SpotifyTrack> {
        val seed = (if (rounds++ == 0) alreadyQueued.lastOrNull() else alreadyQueued.takeLast(RADIO_SEED_WINDOW).randomOrNull())
            ?: return emptyList()
        val seen = alreadyQueued.mapTo(HashSet()) { it.id }
        return runCatching {
            com.metrolist.music.playback.SimilarArtistsRadio.build(seed).filter { it.id !in seen }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val RADIO_SEED_WINDOW = 10

        /** A release's tracks as the queue reads them (the catalog keeps them cached). */
        suspend fun tracksOf(release: SpotifyAlbum): List<SpotifyTrack> =
            (release.tracks?.items?.takeIf { it.isNotEmpty() } ?: Catalog.album(release.id).getOrNull()?.tracks?.items.orEmpty())
                .filter { !it.isLocal }
    }
}
