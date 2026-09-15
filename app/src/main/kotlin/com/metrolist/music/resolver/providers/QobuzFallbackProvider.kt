/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver.providers

import android.util.LruCache
import com.metrolist.music.qobuz.QobuzAudioProvider
import com.metrolist.music.resolver.AudioProvider
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.AudioQuery
import com.metrolist.music.resolver.AudioStream
import com.metrolist.music.resolver.ProviderMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import timber.log.Timber

/**
 * Qobuz: the high-quality source — lossless / Hi-Res when available, exact ISRC matching for Spotify
 * tracks. Limitation: reached through community backends whose availability varies.
 *
 * Qobuz resolution already yields the stream together with the match, so the stream is kept for the
 * immediate playback that follows a successful search.
 */
class QobuzFallbackProvider(
    private val settings: () -> Settings,
) : AudioProvider {
    data class Settings(
        val backend: QobuzAudioProvider.ResolverBackend,
        val countryCode: String,
        val qualityCode: Int,
    )

    override val id = AudioProviderId.QOBUZ
    override val searchTimeoutMs = 9_000L

    private val recentStreams = LruCache<String, QobuzAudioProvider.Resolved>(64)

    private fun buildQuery(query: AudioQuery, mediaId: String): QobuzAudioProvider.Query? {
        if (query.title.isBlank() || query.artists.isEmpty()) return null
        val s = settings()
        return QobuzAudioProvider.Query(
            mediaId = mediaId,
            title = query.title,
            artists = query.artists,
            album = query.album,
            isrc = query.isrc,
            durationMs = query.durationMs.takeIf { it > 0 },
            countryCode = s.countryCode,
            backend = s.backend,
            qualityCode = s.qualityCode,
        )
    }

    override suspend fun search(query: AudioQuery): ProviderMatch? {
        val q = buildQuery(query, mediaId = "mfb:${query.cacheKey}") ?: return null
        val resolved = runCatching { runInterruptible(Dispatchers.IO) { QobuzAudioProvider.resolve(q) } }
            .getOrElse { e ->
                // "not found" is a real miss; anything else (backend down, captcha) is a failure the
                // audio search log should show.
                if (e.message?.contains("not found", ignoreCase = true) == true) return null
                throw e
            }
        recentStreams.put(resolved.trackId, resolved)
        return ProviderMatch(
            provider = id,
            trackId = resolved.trackId,
            title = query.title,
            artist = query.primaryArtist,
            durationMs = query.durationMs.takeIf { it > 0 },
            // QobuzAudioProvider only returns candidates that cleared its own strict scoring
            // (ISRC / token recall+precision / artist / duration gates).
            confidence = if (query.isrc != null && resolved.isrc.equals(query.isrc, ignoreCase = true)) 1.0 else 0.9,
        )
    }

    override suspend fun stream(query: AudioQuery, match: ProviderMatch): AudioStream? {
        val now = System.currentTimeMillis()
        val resolved = recentStreams.get(match.trackId)?.takeIf { it.expiresAtMs > now + 30_000L }
            ?: run {
                // Direct track id → Qobuz skips the fuzzy search and only fetches a stream.
                val q = buildQuery(query, mediaId = "qobuz:track:${match.trackId}") ?: return null
                runCatching { runInterruptible(Dispatchers.IO) { QobuzAudioProvider.resolve(q) } }
                    .onFailure { Timber.tag("AudioRace").d("qobuz stream ✘ %s: %s", match.trackId, it.message) }
                    .getOrNull()
                    ?.also { recentStreams.put(it.trackId, it) }
            }
            ?: return null
        return AudioStream(
            uri = resolved.mediaUri,
            mimeType = resolved.mimeType.substringBefore(';').trim(),
            codecs = resolved.codecs,
            bitrate = resolved.bitrate,
            sampleRate = resolved.sampleRate,
            expiresAtMs = resolved.expiresAtMs,
        )
    }
}
