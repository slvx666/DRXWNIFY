/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import com.metrolist.music.db.entities.SongKeyRow
import java.text.Normalizer

/**
 * Which songs a playlist already has. Not only the same id: one song reaches the app under several
 * ("mfb:<catalog id>", its YouTube video, a Yandex track), so a track put into a playlist from the
 * player and again from an album went in twice without a word. The same title (without "(feat. …)",
 * "- Remastered" and the like) by the same first artist counts as the same song; when an artist is
 * unknown, the same title and nearly the same length.
 */
object PlaylistDuplicates {
    private const val CHUNK = 500
    private const val LENGTH_SLACK_S = 3

    /** For each of [playlistIds]: those of [songIds] it already has (missing when none). */
    fun find(database: MusicDatabase, playlistIds: List<String>, songIds: List<String>): Map<String, List<String>> {
        if (playlistIds.isEmpty() || songIds.isEmpty()) return emptyMap()
        val wanted = songIds.distinct().chunked(CHUNK).flatMap { runCatching { database.songKeys(it) }.getOrDefault(emptyList()) }
        val existing = playlistIds.distinct().chunked(CHUNK).flatMap { runCatching { database.playlistSongKeys(it) }.getOrDefault(emptyList()) }
        val out = HashMap<String, List<String>>()
        existing.groupBy { it.playlistId.orEmpty() }.forEach { (playlistId, rows) ->
            val ids = rows.mapTo(HashSet()) { it.songId }
            val byTitle = rows.groupBy { title(it.title) }
            val found = songIds.distinct().filter { id ->
                if (id in ids) return@filter true
                val song = wanted.firstOrNull { it.songId == id } ?: return@filter false
                val t = title(song.title).takeIf { it.isNotEmpty() } ?: return@filter false
                byTitle[t].orEmpty().any { same(song, it) }
            }
            if (found.isNotEmpty()) out[playlistId] = found
        }
        return out
    }

    private fun same(a: SongKeyRow, b: SongKeyRow): Boolean {
        val artistA = artist(a.artist)
        val artistB = artist(b.artist)
        if (artistA.isNotEmpty() && artistB.isNotEmpty()) return artistA == artistB
        return a.duration > 0 && b.duration > 0 && kotlin.math.abs(a.duration - b.duration) <= LENGTH_SLACK_S
    }

    private val BRACKETS = Regex("""[(\[].*?[)\]]""")
    private val SUFFIX = Regex("""\s[-–—]\s.*(remaster|version|edit|mix|live|mono|stereo|original|ремастер|версия).*$""", RegexOption.IGNORE_CASE)
    private val FEAT = Regex("""\s(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)

    private fun title(raw: String): String =
        letters(raw.replace(BRACKETS, " ").replace(SUFFIX, "").replace(FEAT, ""))

    private fun artist(raw: String?): String = letters(raw.orEmpty())

    /** Lower case, no accents ("ё" as "е"), letters and digits only. */
    private fun letters(s: String): String =
        Normalizer.normalize(s.lowercase().replace('ё', 'е'), Normalizer.Form.NFD)
            .filter { it.isLetterOrDigit() }
}
