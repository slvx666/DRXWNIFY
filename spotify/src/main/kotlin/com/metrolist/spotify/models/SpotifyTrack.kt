package com.metrolist.spotify.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SpotifyTrack(
    val id: String = "",
    val name: String = "",
    val artists: List<SpotifySimpleArtist> = emptyList(),
    val album: SpotifySimpleAlbum? = null,
    @SerialName("duration_ms") val durationMs: Int = 0,
    val explicit: Boolean = false,
    @SerialName("is_local") val isLocal: Boolean = false,
    @SerialName("preview_url") val previewUrl: String? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
    val uri: String? = null,
    val popularity: Int? = null,
    @SerialName("external_ids") val externalIds: SpotifyExternalIds? = null,
    /** Spotify's stream count, when the response carried it (GQL album / playlist / top tracks). */
    val playCount: Long? = null,
) {
    val isrc: String? get() = externalIds?.isrc
}

@Serializable
data class SpotifyExternalIds(
    val isrc: String? = null,
    val ean: String? = null,
    val upc: String? = null,
)

@Serializable
data class SpotifySimpleArtist(
    val id: String? = null,
    val name: String = "",
    val uri: String? = null,
)

@Serializable
data class SpotifySimpleAlbum(
    val id: String = "",
    val name: String = "",
    val images: List<SpotifyImage> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("album_type") val albumType: String? = null,
    val artists: List<SpotifySimpleArtist> = emptyList(),
    val uri: String? = null,
)

/**
 * Wrapper for the /me/tracks endpoint which returns SavedTrack objects
 */
@Serializable
data class SpotifySavedTrack(
    @SerialName("added_at") val addedAt: String? = null,
    val track: SpotifyTrack,
)

/** Stream counts seen in Spotify responses, by track id; [onNew] lets the app keep them between launches. */
object SpotifyPlayCounts {
    private val counts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Volatile
    var onNew: (() -> Unit)? = null

    operator fun get(trackId: String?): Long? = trackId?.let { counts[it] }

    fun put(trackId: String, count: Long) {
        if (counts.put(trackId, count) != count) onNew?.invoke()
    }

    fun load(saved: Map<String, Long>) { counts.putAll(saved) }

    fun snapshot(): Map<String, Long> = HashMap(counts)
}
