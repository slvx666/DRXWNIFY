/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

/** A song as the duplicate check sees it: where it is, its title, length and first artist. */
data class SongKeyRow(
    val playlistId: String?,
    val songId: String,
    val title: String,
    val duration: Int,
    val artist: String?,
)
