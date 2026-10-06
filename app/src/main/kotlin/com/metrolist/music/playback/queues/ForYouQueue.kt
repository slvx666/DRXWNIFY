/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.ForYouRecommender
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.sync.withLock

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

    override val allRecommended: Boolean = true

    override val maxContinuationRounds: Int = Int.MAX_VALUE

    // Skipping fast through the radio must never reach an end that is still loading.
    override val quickItems: Boolean = true

    private var session: ForYouRecommender.Session? = null

    /**
     * One continuation at a time: two overlapping requests (the player asking again before the
     * first answer arrived) used to share the session's pool — or even create two sessions — and
     * queue the same handful of tracks twice.
     */
    private val continuation = kotlinx.coroutines.sync.Mutex()

    override suspend fun continueWith(alreadyQueued: List<SpotifyTrack>): List<SpotifyTrack> =
        continuation.withLock {
            val s = session ?: sessionFactory().also { session = it }
            val queued = alreadyQueued.mapTo(HashSet()) { it.id }
            s.next(CHUNK).filter { it.id !in queued }
        }

    override suspend fun fetchPage(offset: Int, limit: Int): PageResult =
        PageResult(tracks = emptyList(), total = 0, rawCount = 0)

    private companion object {
        const val CHUNK = 20
    }
}
