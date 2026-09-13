/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyTrack
import timber.log.Timber

/**
 * Resolves a Spotify track to a YouTube video by trying an ordered cascade of search queries and
 * running each candidate set through [SpotifyMapper.selectBestMatch]'s strict title/artist/duration
 * gates. The core principle is Sunnify's: a WRONG track is worse than NO track, so when nothing
 * clears the gates this returns [ResolveResult.NoMatch] rather than the closest-but-wrong hit.
 *
 * All matching/scoring lives in the pure-Kotlin [SpotifyMapper] (unit-tested without Android); this
 * class only orchestrates the (anonymous) YouTube searches and adapts [SongItem]s onto candidates.
 *
 * @param looseProvider supplies the opt-in "use closest result if no confident match" flag, read
 *   per resolve so it always reflects the current user setting. Off by default — it trades the
 *   never-ship-wrong-audio guarantee for coverage (recovers cross-script titles the ascii gate
 *   cannot match). Backed by [ResolverPreferences], which the Spotify settings toggle updates.
 */
class YouTubeTrackResolver(
    private val looseProvider: () -> Boolean = { ResolverPreferences.looseMatch },
) : TrackResolver {

    override suspend fun resolve(track: SpotifyTrack): ResolveResult {
        val loose = looseProvider()
        val primaryArtist = track.artists.firstOrNull()?.name.orEmpty()
        val allArtists = track.artists.joinToString(", ") { it.name }
        val queries = SpotifyMapper.buildSearchQueries(track)

        // Accumulate every candidate seen across queries so a final loose pass (when enabled) can
        // consider the whole union, not just the last query's results.
        val seen = LinkedHashMap<String, SpotifyMapper.Candidate>()

        for ((index, query) in queries.withIndex()) {
            val batch = fetchCandidates(query, includeVideos = index == 0)
            for (c in batch) seen.putIfAbsent(c.id, c)

            when (val result = SpotifyMapper.selectBestMatch(
                spotifyTitle = track.name,
                spotifyPrimaryArtist = primaryArtist,
                spotifyArtistsAll = allArtists,
                spotifyDurationMs = track.durationMs,
                candidates = batch,
                loose = false,
            )) {
                is SpotifyMapper.MatchResult.Matched -> {
                    Timber.d(
                        "Resolver matched '${track.name}' -> ${result.id} (score=%.2f, query=%d/%d)",
                        result.score, index + 1, queries.size,
                    )
                    return toMatched(result, batch)
                }
                SpotifyMapper.MatchResult.NoMatch -> Unit // try the next, wider query
            }
        }

        // Strict cascade exhausted. In loose mode, take the duration-closest of everything seen.
        if (loose && seen.isNotEmpty()) {
            val result = SpotifyMapper.selectBestMatch(
                spotifyTitle = track.name,
                spotifyPrimaryArtist = primaryArtist,
                spotifyArtistsAll = allArtists,
                spotifyDurationMs = track.durationMs,
                candidates = seen.values.toList(),
                loose = true,
            )
            if (result is SpotifyMapper.MatchResult.Matched) {
                Timber.w("Resolver loose-matched '${track.name}' -> ${result.id}")
                return toMatched(result, seen.values.toList())
            }
        }

        Timber.w("Resolver NO_MATCH for '${track.name}' by $primaryArtist (${queries.size} queries tried)")
        return ResolveResult.NoMatch
    }

    /** Fetches song (and optionally video) candidates for one query, anonymously, de-duplicated. */
    private suspend fun fetchCandidates(query: String, includeVideos: Boolean): List<SpotifyMapper.Candidate> {
        val songs = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG, incognito = true)
            .getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
        val videos = if (includeVideos) {
            YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO, incognito = true)
                .getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
        } else {
            emptyList()
        }
        return (songs + videos)
            .distinctBy { it.id }
            .map { it.toCandidate() }
    }

    private fun toMatched(
        result: SpotifyMapper.MatchResult.Matched,
        candidates: List<SpotifyMapper.Candidate>,
    ): ResolveResult.Matched = ResolveResult.Matched(
        source = AudioSource.YouTube(result.id),
        confidence = result.score,
        title = result.title,
        artist = result.artist,
        thumbnailUrl = candidates.firstOrNull { it.id == result.id }?.thumbnailUrl,
    )

    private fun SongItem.toCandidate() = SpotifyMapper.Candidate(
        id = id,
        title = title,
        artist = artists.firstOrNull()?.name ?: "",
        durationSec = duration,
        isVideo = isVideoSong,
        thumbnailUrl = thumbnail,
    )
}
