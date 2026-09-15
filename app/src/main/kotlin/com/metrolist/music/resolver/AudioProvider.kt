/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver

import com.metrolist.spotify.models.SpotifySimpleAlbum
import com.metrolist.spotify.models.SpotifySimpleArtist
import com.metrolist.spotify.models.SpotifyTrack
import com.metrolist.spotify.models.SpotifyExternalIds

/**
 * Where the AUDIO of a catalog track can come from. Metadata and library always belong to the catalog
 * (Spotify / Yandex Music); a provider only ever supplies a stream for a track the catalog described.
 *
 * The selection priority is the user's order ([ResolverPreferences.order], editable in Settings →
 * Music sources); [DEFAULT_ORDER] puts SoundCloud first.
 */
enum class AudioProviderId {
    YOUTUBE,
    QOBUZ,
    VK,
    SOUNDCLOUD,
    BANDCAMP,
    AUDIUS,
    ;

    companion object {
        val DEFAULT_ORDER = listOf(SOUNDCLOUD, YOUTUBE, VK, BANDCAMP, AUDIUS, QOBUZ)

        /** Parses a stored "A,B,C" order; unknown names are dropped, missing providers appended. */
        fun parseOrder(value: String?): List<AudioProviderId> {
            val parsed = value.orEmpty().split(',')
                .mapNotNull { name -> entries.firstOrNull { it.name == name.trim() } }
                .distinct()
            return parsed + DEFAULT_ORDER.filter { it !in parsed }
        }
    }
}

/**
 * Synthetic media ids for tracks whose audio comes from a non-YouTube provider. The id is keyed on
 * the CATALOG track (not on the provider), so the player/download caches stay stable even if the
 * serving provider changes later: `mfb:<catalogTrackId>`.
 *
 * `qbzfb:` is the older Qobuz-only form; it is still understood so existing queues and downloads keep
 * working.
 */
object FallbackIds {
    const val PREFIX = "mfb:"
    const val LEGACY_QOBUZ_PREFIX = "qbzfb:"

    fun of(catalogTrackId: String): String = PREFIX + catalogTrackId

    fun isFallbackId(mediaId: String?): Boolean =
        mediaId != null && (mediaId.startsWith(PREFIX) || mediaId.startsWith(LEGACY_QOBUZ_PREFIX))

    fun isLegacyQobuz(mediaId: String): Boolean = mediaId.startsWith(LEGACY_QOBUZ_PREFIX)

    /** The catalog track id behind a fallback media id, or null for a regular id. */
    fun catalogIdOf(mediaId: String): String? = when {
        mediaId.startsWith(PREFIX) -> mediaId.removePrefix(PREFIX)
        mediaId.startsWith(LEGACY_QOBUZ_PREFIX) -> mediaId.removePrefix(LEGACY_QOBUZ_PREFIX)
        else -> null
    }
}

/** What providers search for: the catalog's own description of the track. */
data class AudioQuery(
    val catalogId: String?,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long,
    val isrc: String?,
    /** Provider track ids known to be unplayable for this track (e.g. an age-restricted YouTube video). */
    val excludedTrackIds: Set<String> = emptySet(),
) {
    val primaryArtist: String get() = artists.firstOrNull().orEmpty()

    /** Stable key for caches that should not depend on the media id form. */
    val cacheKey: String
        get() = catalogId ?: "${title.lowercase()}|${primaryArtist.lowercase()}|${durationMs / 1000}"

    fun toSpotifyTrack(): SpotifyTrack = SpotifyTrack(
        id = catalogId.orEmpty(),
        name = title,
        artists = artists.map { SpotifySimpleArtist(name = it) },
        album = album?.let { SpotifySimpleAlbum(name = it) },
        durationMs = durationMs.toInt(),
        externalIds = isrc?.let { SpotifyExternalIds(isrc = it) },
    )

    companion object {
        fun from(track: SpotifyTrack) = AudioQuery(
            catalogId = track.id.takeIf { it.isNotBlank() },
            title = track.name,
            artists = track.artists.map { it.name }.filter { it.isNotBlank() },
            album = track.album?.name?.takeIf { it.isNotBlank() },
            durationMs = track.durationMs.toLong(),
            isrc = track.isrc?.takeIf { it.isNotBlank() },
        )
    }
}

/** A provider-side track that cleared the strict title/artist/duration gates. */
data class ProviderMatch(
    val provider: AudioProviderId,
    /** Provider-specific id that is enough to fetch a fresh stream later (survives restarts). */
    val trackId: String,
    val title: String,
    val artist: String,
    val durationMs: Long?,
    val confidence: Double,
    val thumbnailUrl: String? = null,
)

/** A playable stream. [uri] may be http(s) or an [HlsConcatDataSource] `meldhls://` uri. */
data class AudioStream(
    val uri: String,
    val mimeType: String,
    val codecs: String,
    val bitrate: Int,
    val sampleRate: Int?,
    val expiresAtMs: Long,
    val contentLength: Long? = null,
)

interface AudioProvider {
    val id: AudioProviderId

    /** Upper bound for one search, so a dead/blocked service can never stall resolution. */
    val searchTimeoutMs: Long

    /** False when the provider is enabled but cannot work yet (e.g. VK without a login). */
    fun isReady(): Boolean = true

    suspend fun search(query: AudioQuery): ProviderMatch?

    /**
     * A fresh stream for a previously found match, or null if it can no longer be played. The YouTube
     * provider returns null: YouTube streams are resolved by the player's native pipeline.
     */
    suspend fun stream(query: AudioQuery, match: ProviderMatch): AudioStream?
}
