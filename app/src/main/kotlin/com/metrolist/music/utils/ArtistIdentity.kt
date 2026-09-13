/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

/**
 * One place that decides whether an artist id refers to a Spotify artist, across the id shapes the
 * app uses: "spotify:<id>" (player metadata), "SP_<id>" (DB rows from Spotify sync / subscribe) and
 * a raw 22-char base62 Spotify id (the spotify_artist/{id} route).
 */
object ArtistIdentity {
    private val RAW_SPOTIFY_ID = Regex("^[0-9A-Za-z]{22}$")

    fun spotifyIdOf(id: String?): String? {
        if (id.isNullOrBlank()) return null
        return when {
            id.startsWith(SPOTIFY_ID_PREFIX) -> id.removePrefix(SPOTIFY_ID_PREFIX).takeIf { it.isNotBlank() }
            id.startsWith("SP_") -> id.removePrefix("SP_").takeIf { it.isNotBlank() }
            RAW_SPOTIFY_ID.matches(id) -> id
            else -> null
        }
    }
}
