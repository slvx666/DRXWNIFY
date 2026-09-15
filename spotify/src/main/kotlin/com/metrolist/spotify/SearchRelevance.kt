package com.metrolist.spotify

import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyTrack
import java.text.Normalizer

/**
 * Orders catalog search results by how literally they match what the user typed. The catalogs' own
 * ranking mixes in popularity and "similar" items, so typing ".m0lly" could list other artists first.
 * Here an exact name beats a prefix, a prefix beats a whole word, a word beats a substring, and items
 * matching only through fuzzy catalog logic go last (and are dropped when real matches exist).
 */
object SearchRelevance {
    private val SPACES = Regex("\\s+")

    /** Lower-case, diacritics folded, punctuation → space; letters of every script are kept. */
    fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val folded = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFKD)
        val sb = StringBuilder(folded.length)
        for (ch in folded) {
            when {
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> Unit
                ch == 'ё' -> sb.append('е')
                Character.isLetterOrDigit(ch) -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return SPACES.replace(sb.toString(), " ").trim()
    }

    /** 0 = unrelated … 100 = exactly what was typed. */
    fun score(query: String, text: String?): Int {
        val q = normalize(query)
        val t = normalize(text)
        if (q.isEmpty()) return 1
        if (t.isEmpty()) return 0
        return when {
            t == q -> 100
            t.startsWith("$q ") || t.startsWith(q) -> 80
            " $t ".contains(" $q ") -> 65
            t.contains(q) -> 50
            q.split(' ').all { it.isNotEmpty() && t.contains(it) } -> 30
            else -> 0
        }
    }

    private fun best(query: String, primary: String?, secondary: List<String>): Int {
        val main = score(query, primary)
        val other = secondary.maxOfOrNull { score(query, it) } ?: 0
        // A match on the main name counts fully; a match only on the artist counts a bit less, so
        // "skeler" lists Skeler's songs right after a song literally named "Skeler".
        val combined = score(query, listOfNotNull(primary).plus(secondary).joinToString(" "))
        return maxOf(main, other - 5, if (combined >= 30) 30 else 0)
    }

    private fun <T> order(items: List<T>, dropUnrelated: Boolean, scoreOf: (T) -> Int): List<T> {
        val scored = items.withIndex().map { (i, item) -> Triple(item, scoreOf(item), i) }
        val anyRelevant = scored.any { it.second > 0 }
        return scored
            .filter { !dropUnrelated || !anyRelevant || it.second > 0 }
            .sortedWith(compareByDescending<Triple<T, Int, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
    }

    fun rankTracks(query: String, tracks: List<SpotifyTrack>): List<SpotifyTrack> =
        order(tracks, dropUnrelated = true) { t -> best(query, t.name, t.artists.map { it.name }) }

    fun rankAlbums(query: String, albums: List<SpotifyAlbum>): List<SpotifyAlbum> =
        order(albums, dropUnrelated = true) { a -> best(query, a.name, a.artists.map { it.name }) }

    fun rankArtists(query: String, artists: List<SpotifyArtist>): List<SpotifyArtist> =
        order(artists, dropUnrelated = true) { a -> score(query, a.name) }

    fun rankPlaylists(query: String, playlists: List<SpotifyPlaylist>): List<SpotifyPlaylist> =
        order(playlists, dropUnrelated = false) { p -> best(query, p.name, listOfNotNull(p.owner?.displayName)) }

    /** True when the best artist result is literally the query (show artists before songs). */
    fun isArtistQuery(query: String, artists: List<SpotifyArtist>): Boolean =
        artists.any { score(query, it.name) == 100 }
}
