/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver

import com.metrolist.spotify.models.SpotifyTrack

/**
 * Resolves a Spotify track (the source of truth for title/artists/album/duration/ISRC) to a
 * concrete, playable audio source with a confidence score — or reports [ResolveResult.NoMatch]
 * when no candidate clears the matching gates.
 *
 * The layer deliberately hides the internal search mechanics from the rest of Meld: callers get a
 * source + confidence, never YouTube search internals. It is designed as a cascade so additional
 * sources (a future SoundCloud fallback, a manual override, …) can be composed without touching the
 * player, cache, or download pipeline — all of which remain keyed on the resolved audio source's id.
 */
interface TrackResolver {
    suspend fun resolve(track: SpotifyTrack): ResolveResult
}

/**
 * Process-wide resolver settings, kept in sync with the user's preferences. A tiny mutable holder
 * (like [com.metrolist.innertube.YouTube]'s global auth state) so resolvers created deep in the
 * queue/UI layers — which have no Context — can read the current setting without threading it
 * through every call site. Updated by the Spotify settings toggle and initialised at app start.
 */
object ResolverPreferences {
    /**
     * "Approximate matches": when strict title/artist gating finds no confident match, fall back to
     * the duration-closest candidate instead of skipping the track. Off by default — trades the
     * never-play-the-wrong-track guarantee for coverage. See the Spotify settings toggle.
     */
    @Volatile
    var looseMatch: Boolean = false

    /**
     * When a Spotify track has no confident YouTube match, resolve it from Qobuz instead (by
     * ISRC/metadata) so underground / YouTube-missing tracks still play and download. On by default.
     */
    @Volatile
    var qobuzFallback: Boolean = true
}

/**
 * A concrete, playable audio source for a resolved track. Kept extensible for multiple backends,
 * but note: Meld's player/cache/download pipeline is keyed on the YouTube video id, so [YouTube] is
 * the id that flows downstream. Lossless (Qobuz) remains a playback-time substitution layered on top
 * of that same id — it is not a distinct source at resolution time.
 */
sealed interface AudioSource {
    data class YouTube(val videoId: String) : AudioSource
}

/** Outcome of a resolution attempt. */
sealed interface ResolveResult {
    /**
     * A confident match. [confidence] is the raw match score in 0.0..1.0. [title]/[artist] carry the
     * chosen source's own metadata (used for the match cache and reverse lookup); the Spotify track
     * stays authoritative for everything shown to the user.
     */
    data class Matched(
        val source: AudioSource,
        val confidence: Double,
        val title: String,
        val artist: String,
        val thumbnailUrl: String?,
    ) : ResolveResult

    /** No candidate cleared the gates. The caller skips the track rather than play the wrong audio. */
    data object NoMatch : ResolveResult
}
