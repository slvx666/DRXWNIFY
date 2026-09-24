/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.ForYouRecommender
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.models.SpotifyTrack

/**
 * The "For you" radio: it starts with [startTracks] (the first one plays at once, as [preload])
 * and never runs dry — whenever the player nears the end, the next chunk comes from the session,
 * which is created on first need and never repeats a track it handed out.
 */
class ForYouQueue(
    startTracks: List<SpotifyTrack>,
    private val sessionFactory: suspend () -> ForYouRecommender.Session,
    mapper: SpotifyYouTubeMapper,
    preload: MediaMetadata?,
) : SpotifyPagedQueue(startIndex = 0, mapper = mapper, preloadItem = preload) {

    override val logTag: String = "ForYouQueue"

    override val providedTracks: List<SpotifyTrack> = startTracks

    override val continues: Boolean = true

    override val maxContinuationRounds: Int = Int.MAX_VALUE

    private var session: ForYouRecommender.Session? = null

    override suspend fun continueWith(alreadyQueued: List<SpotifyTrack>): List<SpotifyTrack> {
        val s = session ?: sessionFactory().also { session = it }
        return s.next(CHUNK)
    }

    override suspend fun fetchPage(offset: Int, limit: Int): PageResult =
        PageResult(tracks = emptyList(), total = 0, rawCount = 0)

    private companion object {
        const val CHUNK = 10
    }
}
