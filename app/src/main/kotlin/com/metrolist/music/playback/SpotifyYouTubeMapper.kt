/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.util.LruCache
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.SpotifyMatchEntity
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.resolver.AudioFallbackEngine
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.AudioQuery
import com.metrolist.music.resolver.FallbackIds
import com.metrolist.music.resolver.ResolverPreferences
import com.metrolist.music.utils.SPOTIFY_ID_PREFIX
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Turns a catalog track (Spotify or Yandex Music metadata) into a playable queue item.
 *
 * Resolution order: in-memory cache → Room match cache → parallel search across every enabled audio
 * provider ([AudioFallbackEngine]). A YouTube result keeps the plain video id; any other provider
 * yields a `mfb:<catalogId>` id whose stream is fetched at playback/download time. The catalog's
 * metadata is always what the user sees.
 */
class SpotifyYouTubeMapper(
    private val database: MusicDatabase,
) {

    private data class CachedMatch(
        val youtubeId: String,
        val title: String,
        val artist: String,
        val isManualOverride: Boolean = false,
    )

    /**
     * Maps a Spotify track to a YouTube MediaMetadata by searching YouTube Music.
     * Returns null if no suitable match is found.
     *
     * Resolution order: in-memory cache → Room DB → YouTube search.
     */
    suspend fun mapToYouTube(track: SpotifyTrack): MediaMetadata? = withContext(Dispatchers.IO) {
        // With YouTube switched off (e.g. unreachable without a VPN) cached YouTube matches are useless:
        // go straight to the other audio providers.
        if (!ResolverPreferences.youtubeEnabled) {
            return@withContext resolveViaProviders(track, exclude = setOf(AudioProviderId.YOUTUBE))
        }

        // 1. In-memory LRU cache (zero I/O)
        memoryCache[track.id]?.let { mem ->
            if (mem.isManualOverride || isCachedMatchPlausible(track, mem.title, mem.artist)) {
                Timber.d("Spotify match memory hit: ${track.name} -> ${mem.youtubeId}")
                return@withContext buildMediaMetadata(mem.youtubeId, track, mem.title, mem.artist)
            }
            memoryCache.remove(track.id)
        }

        // 2. Room DB cache. Matches written by the older, non-strict matcher can be plain wrong
        // (e.g. ".m0lly /bin" — not on YouTube at all — was cached to unrelated videos and then
        // served forever). Re-check every automatic cached match against the same strict gates the
        // resolver uses; a match that fails them is dropped and resolved again from scratch.
        val cached = database.getSpotifyMatch(track.id)
        if (cached != null) {
            if (cached.isManualOverride || isCachedMatchPlausible(track, cached.title, cached.artist)) {
                Timber.d("Spotify match cache hit: ${track.name} -> ${cached.youtubeId} (manual=${cached.isManualOverride})")
                memoryCache.put(track.id, CachedMatch(
                    cached.youtubeId, cached.title, cached.artist, cached.isManualOverride,
                ))
                return@withContext buildMediaMetadata(cached.youtubeId, track, cached.title, cached.artist)
            }
            Timber.w("Spotify match cache REJECTED (stale/wrong): ${track.name} -> ${cached.youtubeId} ('${cached.title}' by '${cached.artist}')")
            database.deleteSpotifyMatch(track.id)
        }

        // 3. Nothing cached: all enabled audio providers (YouTube, Qobuz, VK, SoundCloud) search in
        // parallel; see ParallelAudioResolver for the selection policy. Every provider applies the
        // same strict title/artist/duration gates, so this never trades correctness for speed.
        resolveViaProviders(track, exclude = emptySet())
    }

    /**
     * Resolves [track] through the audio fallback engine. A YouTube winner keeps the classic plain
     * video-id item (and is cached in spotify_match); any other provider yields a `mfb:` item whose
     * stream is resolved at playback/download time. Metadata always stays the catalog's.
     */
    private suspend fun resolveViaProviders(track: SpotifyTrack, exclude: Set<AudioProviderId>): MediaMetadata? {
        val match = AudioFallbackEngine.resolve(AudioQuery.from(track), exclude)
        if (match == null) {
            Timber.w("No audio source for '${track.name}' by ${track.artists.firstOrNull()?.name}")
            return null
        }
        if (match.provider == AudioProviderId.YOUTUBE) {
            val youtubeId = match.trackId
            database.upsertSpotifyMatch(
                SpotifyMatchEntity(
                    spotifyId = track.id,
                    youtubeId = youtubeId,
                    title = match.title,
                    artist = match.artist,
                    matchScore = match.confidence,
                )
            )
            memoryCache.put(track.id, CachedMatch(youtubeId, match.title, match.artist))
            Timber.d("Audio for '${track.name}' -> YouTube $youtubeId (score: ${match.confidence})")
            return buildMediaMetadata(
                youtubeId = youtubeId,
                spotifyTrack = track,
                ytTitle = match.title,
                ytArtist = match.artist,
                ytThumbnailUrl = match.thumbnailUrl,
            )
        }
        val fallbackId = FallbackIds.of(track.id)
        SpotifyMetadataRegistry.register(fallbackId, track)
        Timber.d("Audio for '${track.name}' -> ${match.provider} (${match.trackId}) as $fallbackId")
        return buildMediaMetadata(
            youtubeId = fallbackId,
            spotifyTrack = track,
            ytTitle = track.name,
            ytArtist = track.artists.firstOrNull()?.name ?: "",
        )
    }

    /**
     * Writes the Spotify-derived metadata (title, artists, album, cover) for a resolved track into the
     * song table and registers the Spotify track, so downloads/exports are tagged from Spotify — not
     * from whatever the YouTube upload was called.
     */
    suspend fun persistSpotifyMetadata(metadata: MediaMetadata, track: SpotifyTrack) = withContext(Dispatchers.IO) {
        SpotifyMetadataRegistry.register(metadata.id, track)
        runCatching { database.transaction { upsertMetadata(metadata) } }
            .onFailure { Timber.w(it, "persistSpotifyMetadata failed for ${metadata.id}") }
    }

    /**
     * Persists a user-chosen YouTube match for a Spotify track.
     * Manual overrides are never replaced by the automatic fuzzy matcher.
     */
    suspend fun overrideMatch(
        spotifyId: String,
        youtubeId: String,
        title: String,
        artist: String,
    ) = withContext(Dispatchers.IO) {
        database.upsertSpotifyMatch(
            SpotifyMatchEntity(
                spotifyId = spotifyId,
                youtubeId = youtubeId,
                title = title,
                artist = artist,
                matchScore = 1.0,
                isManualOverride = true,
            )
        )
        memoryCache.put(spotifyId, CachedMatch(
            youtubeId, title, artist, isManualOverride = true,
        ))
        Timber.d("Manual override saved: $spotifyId -> $youtubeId ($title by $artist)")
    }

    /**
     * Resolves a Spotify track to a MediaItem suitable for the player queue.
     * The MediaItem's id is the YouTube video ID, allowing the existing
     * ResolvingDataSource to resolve the actual stream URL.
     * Returns null if no match was found (track will be skipped).
     */
    suspend fun resolveToMediaItem(track: SpotifyTrack): androidx.media3.common.MediaItem? {
        Timber.d("SpotifyMapper: resolving '${track.name}' by ${track.artists.firstOrNull()?.name}")
        val metadata = mapToYouTube(track)
        if (metadata == null) {
            Timber.w("SpotifyMapper: FAILED to resolve '${track.name}' - no YouTube match")
            return null
        }
        Timber.d("SpotifyMapper: resolved '${track.name}' -> YouTube ID: ${metadata.id}")
        // Registry (in-memory) + song row (persistent): the player menu / title tap read the Spotify
        // album & artist ids from the DB, so they work after restarts without a (rate-limited) REST
        // track lookup.
        persistSpotifyMetadata(metadata, track)
        return metadata.toMediaItem()
    }

    /**
     * True when a cached (title, artist) pair still clears the resolver's strict gates for [track].
     * Duration is unknown for cached rows, so only the title/artist gates + score floor apply.
     */
    private fun isCachedMatchPlausible(track: SpotifyTrack, title: String, artist: String): Boolean {
        if (title.isBlank()) return false
        val result = SpotifyMapper.selectBestMatch(
            spotifyTitle = track.name,
            spotifyPrimaryArtist = track.artists.firstOrNull()?.name.orEmpty(),
            spotifyArtistsAll = track.artists.joinToString(", ") { it.name },
            spotifyDurationMs = 0,
            candidates = listOf(
                SpotifyMapper.Candidate(
                    id = "cached", title = title, artist = artist,
                    durationSec = null, isVideo = false, thumbnailUrl = null,
                ),
            ),
            loose = false,
        )
        return result is SpotifyMapper.MatchResult.Matched
    }

    /**
     * A song row for [track] WITHOUT looking for its audio first.
     *
     * Adding a track to a playlist must not depend on the network: a track whose audio has not been
     * resolved yet ("not loaded") used to have no song row, so there was nothing to put in the
     * playlist. The row is keyed on the catalog track — its already-known YouTube video when there
     * is one, else the `mfb:` id whose stream is looked up the moment it is played.
     */
    suspend fun persistWithoutResolving(track: SpotifyTrack): MediaMetadata = withContext(Dispatchers.IO) {
        val knownYouTubeId = runCatching { database.getSpotifyMatch(track.id)?.youtubeId }.getOrNull()
        val id = knownYouTubeId ?: FallbackIds.of(track.id)
        val metadata = buildMediaMetadata(
            youtubeId = id,
            spotifyTrack = track,
            ytTitle = track.name,
            ytArtist = track.artists.firstOrNull()?.name.orEmpty(),
        )
        persistSpotifyMetadata(metadata, track)
        metadata
    }

    private fun buildMediaMetadata(
        youtubeId: String,
        spotifyTrack: SpotifyTrack,
        @Suppress("UNUSED_PARAMETER") ytTitle: String,
        @Suppress("UNUSED_PARAMETER") ytArtist: String,
        ytThumbnailUrl: String? = null,
    ): MediaMetadata {
        val thumbnail = SpotifyMapper.getTrackThumbnail(spotifyTrack)
            ?: ytThumbnailUrl
            ?: "https://i.ytimg.com/vi/$youtubeId/maxresdefault.jpg"

        // Metadata ALWAYS comes from Spotify: the YouTube upload's title/channel is often a label
        // ("Records", "Topic"), a re-upload name or missing entirely — that is what produced label-
        // as-artist / missing-artist downloads. Artist and album ids carry the "spotify:" prefix so
        // "View artist" / "View album" / tapping the title always have a real, routable id.
        val spotifyArtists = spotifyTrack.artists.filter { it.name.isNotBlank() }
        return MediaMetadata(
            id = youtubeId,
            title = spotifyTrack.name.ifEmpty { ytTitle },
            artists = if (spotifyArtists.isNotEmpty()) {
                spotifyArtists.map {
                    MediaMetadata.Artist(
                        id = it.id?.takeIf { id -> id.isNotBlank() }?.let { id -> "$SPOTIFY_ID_PREFIX$id" },
                        name = it.name,
                    )
                }
            } else {
                listOf(MediaMetadata.Artist(id = null, name = ytArtist))
            },
            duration = spotifyTrack.durationMs / 1000,
            thumbnailUrl = thumbnail,
            album = spotifyTrack.album?.takeIf { it.id.isNotBlank() }?.let {
                MediaMetadata.Album(id = "$SPOTIFY_ID_PREFIX${it.id}", title = it.name)
            },
            explicit = spotifyTrack.explicit,
            isrc = spotifyTrack.isrc,
        )
    }

    /**
     * Reverse lookup: given a YouTube track's metadata, finds the corresponding
     * Spotify URI. Uses a two-tier strategy:
     * 1. Fast path — checks the local cache for an existing Spotify↔YouTube match.
     * 2. Slow path — searches Spotify by title+artist and picks the best match.
     *
     * @return A full Spotify URI (e.g. "spotify:track:abc123") or null if not found.
     */
    suspend fun resolveToSpotifyUri(
        youtubeId: String,
        title: String,
        artist: String,
        durationSec: Int = -1,
    ): String? = withContext(Dispatchers.IO) {
        database.getSpotifyMatchByYouTubeId(youtubeId)?.let { cached ->
            Timber.d("Reverse lookup cache hit: $youtubeId -> spotify:track:${cached.spotifyId}")
            return@withContext "spotify:track:${cached.spotifyId}"
        }

        val query = if (artist.isBlank()) title else "$artist $title"
        Timber.d("Reverse lookup: searching Spotify for '$query'")

        val results = Spotify.search(query, types = listOf("track"), limit = 5)
            .getOrNull()?.tracks?.items
        if (results.isNullOrEmpty()) {
            Timber.w("Reverse lookup: no Spotify results for '$query'")
            return@withContext null
        }

        // Pre-compute for the YouTube side (the "reference" in reverse lookup)
        val ytPrecomputed = SpotifyMapper.precompute(
            title = title,
            artist = artist,
            durationMs = if (durationSec > 0) durationSec * 1000 else 0,
        )

        var best: SpotifyTrack? = null
        var bestScore = 0.0
        for (candidate in results) {
            val score = SpotifyMapper.matchScorePrecomputed(
                precomputed = ytPrecomputed,
                candidateTitle = candidate.name,
                candidateArtist = candidate.artists.firstOrNull()?.name ?: "",
                candidateDurationSec = candidate.durationMs / 1000,
            )
            if (score > bestScore) {
                best = candidate
                bestScore = score
            }
        }

        if (best != null) {
            val score = bestScore
            if (score >= SpotifyMapper.MIN_MATCH_THRESHOLD) {
                val uri = best.uri ?: "spotify:track:${best.id}"
                Timber.d("Reverse lookup found: $youtubeId -> $uri (score=$score)")
                database.upsertSpotifyMatch(
                    SpotifyMatchEntity(
                        spotifyId = best.id,
                        youtubeId = youtubeId,
                        title = title,
                        artist = artist,
                        matchScore = score,
                    ),
                )
                return@withContext uri
            }
        }

        Timber.w("Reverse lookup: no match above threshold for '$query'")
        null
    }

    companion object {
        private const val MEM_CACHE_MAX_SIZE = 512

        /**
         * Process-wide, thread-safe LRU cache of recently resolved Spotify→YouTube
         * matches. Shared across all mapper instances (each screen/queue builds its
         * own mapper) so a match resolved on one screen is reused everywhere without
         * DB I/O. [android.util.LruCache] serializes get/put internally, which is
         * required because queues resolve batches in parallel on Dispatchers.IO.
         * Bounded to 512 entries (~30 KB).
         */
        private val memoryCache = LruCache<String, CachedMatch>(MEM_CACHE_MAX_SIZE)

        /**
         * Drops every cached catalog→YouTube match pointing at [videoId] (memory + DB, manual overrides
         * included) — used when that video turned out unplayable (age restriction, removed, region lock),
         * so the next resolution picks another upload or provider instead of failing again.
         */
        fun forgetYouTubeVideo(database: MusicDatabase, videoId: String) {
            memoryCache.snapshot().filterValues { it.youtubeId == videoId }.keys.forEach { memoryCache.remove(it) }
            runCatching {
                database.getSpotifyMatchByYouTubeId(videoId)?.let { database.deleteSpotifyMatch(it.spotifyId) }
            }
        }
    }
}
