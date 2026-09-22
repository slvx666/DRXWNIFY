/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

/** One playlist's current cover: the artwork of the track added to it last. */
data class PlaylistThumbnail(
    val playlistId: String,
    val thumbnailUrl: String?,
)
