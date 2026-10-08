/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The second, slower import pass: compares a line from an imported list with catalog tracks by
 * similarity instead of the strict gate the first pass uses. It knows what imported lines get wrong
 * — guests written into the title ("(feat. A & B)"), a year or "2025 version" glued to the end, track
 * numbers ("III.", "07 -"), typos ("Dispair") — and still refuses another song by the same artist or
 * someone else's cover. Pure logic, unit-tested.
 */
object FuzzyTrackMatch {
    data class Candidate(val id: String, val title: String, val artists: List<String>, val durationSec: Int?)

    data class Scored(val id: String, val score: Double, val titleScore: Double, val artistScore: Double)

    private val FEAT_IN_TITLE = Regex("[(\\[]\\s*(?:feat\\.?|ft\\.?|featuring|with)\\s+([^)\\]]+)[)\\]]", RegexOption.IGNORE_CASE)
    private val FEAT_TAIL = Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val BRACKETS = Regex("[(\\[{][^)\\]}]*[)\\]}]")
    private val YEAR_TAIL = Regex("\\s*[-–—(]?\\s*(?:19|20)\\d{2}(?:\\s*(?:version|remaster(?:ed)?|mix|edition))?\\s*\\)?\\s*$", RegexOption.IGNORE_CASE)
    private val NUMBER_HEAD = Regex("^\\s*(?:[ivxlc]{1,6}\\.|\\d{1,3}\\s*[.)\\-–]\\s*)\\s*", RegexOption.IGNORE_CASE)
    private val DASH_SUFFIX = Regex("\\s+[-–—]\\s+.*$")
    private val ARTIST_SPLIT = Regex("\\s*(?:,|&|/|\\band\\b|\\bx\\b|\\bvs\\.?|\\bfeat\\.?|\\bft\\.?|\\bwith\\b)\\s*", RegexOption.IGNORE_CASE)
    private val FOREIGN = Regex(
        "\\b(cover|karaoke|remix|rmx|nightcore|sped up|slowed|reverb|8d|instrumental|acoustic|live|" +
            "кавер|ремикс|минус|караоке)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Guests named in the title: "Song (feat. A & B)" → [A, B]. */
    fun featuredArtists(title: String): List<String> {
        val raw = FEAT_IN_TITLE.find(title)?.groupValues?.get(1) ?: FEAT_TAIL.find(title)?.groupValues?.get(1) ?: return emptyList()
        return raw.split(ARTIST_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The ways the same title tends to be written, most literal first (for searching and comparing). */
    fun titleVariants(title: String): List<String> {
        val noFeat = FEAT_TAIL.replace(FEAT_IN_TITLE.replace(title, " "), " ")
        val bracketless = BRACKETS.replace(noFeat, " ")
        val noYear = YEAR_TAIL.replace(bracketless.trim(), "")
        val noNumber = NUMBER_HEAD.replace(noYear, "")
        val beforeDash = DASH_SUFFIX.replace(noNumber, "")
        return listOf(title, noFeat, bracketless, noYear, noNumber, beforeDash)
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun normalize(s: String): String {
        val folded = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).replace("'", "").replace("’", "")
        val sb = StringBuilder(folded.length)
        for (ch in folded) {
            when {
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> Unit
                Character.isLetterOrDigit(ch) -> sb.append(if (ch == 'ё') 'е' else ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim().removePrefix("the ")
    }

    /** 0..1: the best of bigram overlap and edit distance, so both reordered words and typos score high. */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val shorter = if (x.length <= y.length) x else y
        val longer = if (x.length <= y.length) y else x
        // "the world we saved" inside "the world we saved 2025 version": the same song, a longer name.
        val contained = if (shorter.length >= 4 && (" $longer ").contains(" $shorter ")) 0.9 else 0.0
        return max(contained, max(dice(x, y), 1.0 - levenshtein(x, y).toDouble() / longer.length))
    }

    private fun dice(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return 0.0
        val ba = a.windowed(2).groupingBy { it }.eachCount()
        val bb = b.windowed(2).groupingBy { it }.eachCount()
        val common = ba.entries.sumOf { (k, n) -> min(n, bb[k] ?: 0) }
        return 2.0 * common / (a.length - 1 + b.length - 1)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /**
     * The best candidate for "[artist] – [title]" that is close enough, or null. [durationSec] is the
     * line's length when the list had it (an exported playlist usually does).
     */
    fun best(artist: String?, title: String, durationSec: Int?, candidates: List<Candidate>): Scored? {
        val wantedTitles = titleVariants(title)
        val wantedArtists = (artist?.split(ARTIST_SPLIT).orEmpty() + featuredArtists(title))
            .map { it.trim() }.filter { it.isNotEmpty() }
        val ownForeign = FOREIGN.findAll(title.lowercase()).map { it.value }.toSet()
        return candidates.mapNotNull { c ->
            val foreign = FOREIGN.findAll(c.title.lowercase()).map { it.value }.toSet() - ownForeign
            if (foreign.isNotEmpty()) return@mapNotNull null
            val off = if (durationSec != null && durationSec > 0 && c.durationSec != null && c.durationSec > 0) abs(durationSec - c.durationSec) else null
            if (off != null && off > MAX_DURATION_OFF_S) return@mapNotNull null
            val candTitles = titleVariants(c.title)
            val titleScore = wantedTitles.maxOf { w -> candTitles.maxOf { similarity(w, it) } }
            val artistScore = if (wantedArtists.isEmpty()) 0.0 else wantedArtists.maxOf { w -> c.artists.maxOfOrNull { similarity(w, it) } ?: 0.0 }
            val closeLength = off != null && off <= 3
            val accepted = when {
                wantedArtists.isEmpty() -> titleScore >= 0.97 && closeLength
                artistScore >= 0.85 -> titleScore >= 0.8 || (titleScore >= 0.7 && closeLength)
                artistScore >= 0.7 -> titleScore >= 0.95 && (off == null || off <= 5)
                else -> false
            }
            if (!accepted) return@mapNotNull null
            val score = titleScore * 0.6 + artistScore * 0.4 + (if (closeLength) 0.05 else 0.0)
            Scored(c.id, score, titleScore, artistScore)
        }.maxByOrNull { it.score }
    }

    private const val MAX_DURATION_OFF_S = 30
}
