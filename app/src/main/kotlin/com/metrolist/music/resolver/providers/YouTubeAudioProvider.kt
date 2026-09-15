/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver.providers

import com.metrolist.music.resolver.AudioProvider
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.AudioQuery
import com.metrolist.music.resolver.AudioSource
import com.metrolist.music.resolver.AudioStream
import com.metrolist.music.resolver.ProviderMatch
import com.metrolist.music.resolver.ResolveResult
import com.metrolist.music.resolver.TrackResolver
import com.metrolist.music.resolver.YouTubeTrackResolver
import com.metrolist.spotify.SpotifyMapper

/**
 * Largest catalog; the whole player pipeline (lyrics, loudness, related tracks, pre-cache) is keyed on
 * its video ids. Limitation: in some networks YouTube is reachable only through a VPN — which is why it
 * races in parallel with the other providers instead of blocking them.
 */
class YouTubeAudioProvider(
    private val resolver: YouTubeTrackResolver = YouTubeTrackResolver(),
) : AudioProvider {
    override val id = AudioProviderId.YOUTUBE
    override val searchTimeoutMs = 8_000L

    override suspend fun search(query: AudioQuery): ProviderMatch? =
        when (val r = resolver.resolve(query.toSpotifyTrack(), query.excludedTrackIds)) {
            is ResolveResult.Matched -> ProviderMatch(
                provider = id,
                trackId = (r.source as AudioSource.YouTube).videoId,
                title = r.title,
                artist = r.artist,
                durationMs = null,
                confidence = r.confidence,
                thumbnailUrl = r.thumbnailUrl,
            )
            ResolveResult.NoMatch -> null
        }

    /** YouTube streams go through the player's native YouTube pipeline (see MusicService/DownloadUtil). */
    override suspend fun stream(query: AudioQuery, match: ProviderMatch): AudioStream? = null
}

/** Strict gate shared by the non-YouTube providers: wrong audio is worse than no audio. */
internal object ProviderGate {
    /**
     * Max length gap for user-upload services (SoundCloud/VK). Re-uploads there are often edits,
     * sped-up or DJ versions, so they get a much tighter bound than the general 30 s gate.
     */
    const val UPLOAD_DURATION_TOLERANCE_S = 8

    fun select(query: AudioQuery, candidates: List<SpotifyMapper.Candidate>): SpotifyMapper.MatchResult.Matched? =
        SpotifyMapper.selectBestMatch(
            spotifyTitle = query.title,
            spotifyPrimaryArtist = query.primaryArtist,
            spotifyArtistsAll = query.artists.joinToString(", "),
            spotifyDurationMs = query.durationMs.toInt(),
            candidates = candidates,
            loose = false,
        ) as? SpotifyMapper.MatchResult.Matched

    /**
     * Every candidate that clears the strict gates, best first (up to [limit]), dropping uploads whose
     * length differs by more than [maxDurationGapS] when both lengths are known. Lets a provider fall
     * through to its next-best upload when the best one turns out not to be streamable.
     */
    fun ranked(
        query: AudioQuery,
        candidates: List<SpotifyMapper.Candidate>,
        maxDurationGapS: Int = UPLOAD_DURATION_TOLERANCE_S,
        limit: Int = 3,
    ): List<SpotifyMapper.MatchResult.Matched> {
        val wantedS = (query.durationMs / 1000).toInt()
        var pool = candidates.filter { c ->
            val d = c.durationSec
            c.id !in query.excludedTrackIds &&
                (wantedS <= 0 || d == null || kotlin.math.abs(d - wantedS) <= maxDurationGapS)
        }
        val result = mutableListOf<SpotifyMapper.MatchResult.Matched>()
        while (result.size < limit && pool.isNotEmpty()) {
            val chosen = select(query, pool) ?: break
            result += chosen
            pool = pool.filter { it.id != chosen.id }
        }
        return result
    }

    /** "Artist Title" search text, the form every provider's search ranks best. */
    fun searchText(query: AudioQuery): String =
        listOf(query.primaryArtist, SpotifyMapper.spotifyTitleCore(query.title))
            .filter { it.isNotBlank() }
            .joinToString(" ")
}
