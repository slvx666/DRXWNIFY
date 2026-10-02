/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.music.catalog.Catalog
import com.metrolist.spotify.models.SpotifyTrack

/**
 * Queue implementation that loads tracks from a Spotify playlist. All the
 * pagination / fast-start / resolution logic lives in [SpotifyPagedQueue];
 * this class only supplies the playlist fetch and the optional pre-provided list.
 */
class SpotifyPlaylistQueue(
    private val playlistId: String,
    private val initialTracks: List<SpotifyTrack> = emptyList(),
    startIndex: Int = 0,
    mapper: SpotifyYouTubeMapper,
    preloadItem: MediaMetadata? = null,
) : SpotifyPagedQueue(startIndex, mapper, preloadItem) {

    override val logTag: String = "SpotifyPlaylistQueue"

    override val providedTracks: List<SpotifyTrack>? = initialTracks.takeIf { it.isNotEmpty() }

    override val continues: Boolean
        get() = playlistId.startsWith("album_")

    /** After an album the similar-artist radio goes on for as long as the user listens. */
    override val maxContinuationRounds: Int = Int.MAX_VALUE

    private var continuationSeeds = 0

    override suspend fun continueWith(alreadyQueued: List<SpotifyTrack>): List<SpotifyTrack> {
        // Seeds alternate between the album and where the radio has got to: close to the album,
        // without circling the same few artists.
        val albumTracks = initialTracks.ifEmpty { alreadyQueued }
        val seed = (if (continuationSeeds++ % 2 == 0) albumTracks else alreadyQueued.takeLast(RADIO_SEED_WINDOW))
            .randomOrNull() ?: return emptyList()
        val seenIds = alreadyQueued.mapTo(HashSet()) { it.id }
        return runCatching {
            com.metrolist.music.playback.SimilarArtistsRadio.build(seed).filter { it.id !in seenIds }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val RADIO_SEED_WINDOW = 10
    }

    override suspend fun fetchPage(offset: Int, limit: Int): PageResult {
        val result = Catalog.playlistTracks(playlistId, limit = limit, offset = offset).getOrThrow()
        return PageResult(
            tracks = result.items.mapNotNull { it.track?.takeIf { t -> !t.isLocal } },
            total = result.total,
            rawCount = result.items.size,
        )
    }
}
