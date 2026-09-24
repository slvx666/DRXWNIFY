/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import com.metrolist.music.playback.ForYouRecommender
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.models.SpotifyTrack

/**
 * The "For you" radio: it starts with [startTracks] and never runs dry — whenever the player nears
 * the end, the next chunk is picked from [session], which never repeats a track it handed out.
 */
class ForYouQueue(
    startTracks: List<SpotifyTrack>,
    private val session: ForYouRecommender.Session,
    mapper: SpotifyYouTubeMapper,
) : SpotifyPagedQueue(startIndex = 0, mapper = mapper, preloadItem = null) {

    override val logTag: String = "ForYouQueue"

    override val providedTracks: List<SpotifyTrack> = startTracks

    override val continues: Boolean = true

    override val maxContinuationRounds: Int = Int.MAX_VALUE

    override suspend fun continueWith(alreadyQueued: List<SpotifyTrack>): List<SpotifyTrack> =
        session.next(CHUNK)

    override suspend fun fetchPage(offset: Int, limit: Int): PageResult =
        PageResult(tracks = emptyList(), total = 0, rawCount = 0)

    private companion object {
        const val CHUNK = 10
    }
}
