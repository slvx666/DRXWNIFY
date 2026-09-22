package com.metrolist.spotify.models

import kotlinx.serialization.Serializable

/**
 * One row of the user's Spotify "Your Library", in Spotify's own order (pinned first, then
 * "Recents"). Mirrors what the official client shows: playlists, albums, artists, the Liked Songs
 * pseudo-playlist and folders.
 */
@Serializable
data class SpotifyLibraryEntry(
    val kind: Kind,
    val id: String,
    val uri: String,
    val name: String,
    /** Playlist owner name, or the album's primary artist(s). */
    val creator: String? = null,
    val imageUrl: String? = null,
    /** Lower-case Spotify release type for albums: "album", "single", "ep", "compilation". */
    val albumType: String? = null,
    val pinned: Boolean = false,
    /** ISO-8601 "added at" timestamp, when Spotify provides it. */
    val addedAt: String? = null,
    val totalCount: Int = 0,
    /** Cover collage for a playlist that has no cover of its own (its first tracks' artwork). */
    val thumbnails: List<String> = emptyList(),
) {
    @Serializable
    enum class Kind { PLAYLIST, ALBUM, ARTIST, LIKED_SONGS, FOLDER }
}
