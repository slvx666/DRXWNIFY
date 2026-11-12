/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.catalog

import android.content.Context
import com.metrolist.music.db.MusicDatabase
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyTrack
import com.metrolist.yandex.YandexMusic
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.YearMonth
import java.time.ZoneId

/**
 * What the music services tell about the user's listening, for the account pages in Integrations
 * (never sent to friends — "Together" has the app's own statistics):
 *  - Spotify: top tracks and artists over ~4 weeks, ~6 months and all time (Spotify's own ranges);
 *  - Yandex Music: no tops in its API — the likes by month and the most liked artists.
 * Kept a day on disk: Spotify's top endpoints are quick to answer "too many requests".
 *
 * Also the month in review ([MonthReview]): from the listening history in the app, by calendar month.
 */
object ServiceStats {
    enum class Range(val api: String) { SHORT("short_term"), MEDIUM("medium_term"), LONG("long_term") }

    @Serializable
    data class SpotifyTops(val tracks: List<SpotifyTrack>, val artists: List<SpotifyArtist>, val at: Long)

    @Serializable
    data class MonthCount(val month: String, val count: Int)

    @Serializable
    data class ArtistCount(val name: String, val count: Int)

    @Serializable
    data class YandexStats(val total: Int, val byMonth: List<MonthCount>, val artists: List<ArtistCount>, val at: Long)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Volatile
    private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "service_stats").also { it.mkdirs() }
    }

    private fun file(name: String) = dir?.let { File(it, "$name.json") }

    private inline fun <reified T> read(name: String): T? = runCatching {
        file(name)?.takeIf { it.exists() }?.readText()?.let { json.decodeFromString<T>(it) }
    }.getOrNull()

    private inline fun <reified T> write(name: String, value: T) {
        runCatching { file(name)?.writeText(json.encodeToString(value)) }
    }

    /** Spotify's top tracks and artists for [range]; a day-old copy is used as is, a failure falls back to any copy. */
    suspend fun spotifyTops(range: Range, force: Boolean = false): Result<SpotifyTops> {
        val name = "spotify_${range.name}"
        val saved = read<SpotifyTops>(name)
        if (!force && saved != null && System.currentTimeMillis() - saved.at < DAY_MS) return Result.success(saved)
        return runCatching {
            com.metrolist.music.utils.SpotifyTokenManager.ensureAuthenticated()
            val tracks = Spotify.topTracks(range.api, 50).getOrThrow().items
            val artists = Spotify.topArtists(range.api, 50).getOrThrow().items
            SpotifyTops(tracks, artists, System.currentTimeMillis()).also { write(name, it) }
        }.recoverCatching { e -> saved ?: throw e }
    }

    /** Yandex Music: likes by month (the last 12) and the most liked artists. */
    suspend fun yandexStats(force: Boolean = false): Result<YandexStats> {
        val saved = read<YandexStats>("yandex")
        if (!force && saved != null && System.currentTimeMillis() - saved.at < DAY_MS) return Result.success(saved)
        return runCatching {
            val refs = YandexMusic.likedTrackRefs().getOrThrow()
            val zone = ZoneId.systemDefault()
            val months = refs.mapNotNull { r ->
                r.timestamp?.let { ts -> runCatching { java.time.OffsetDateTime.parse(ts).atZoneSameInstant(zone).let { YearMonth.from(it) } }.getOrNull() }
            }.groupingBy { it }.eachCount()
            val now = YearMonth.now()
            val byMonth = (11 downTo 0).map { back ->
                val m = now.minusMonths(back.toLong())
                MonthCount(m.toString(), months[m] ?: 0)
            }
            val tracks = YandexMusic.allLikedSongs().getOrDefault(emptyList())
            val artists = tracks.mapNotNull { it.track.artists.firstOrNull()?.name }
                .groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(15)
                .map { ArtistCount(it.key, it.value) }
            YandexStats(refs.size, byMonth, artists, System.currentTimeMillis()).also { write("yandex", it) }
        }.recoverCatching { e -> saved ?: throw e }
    }

    private const val DAY_MS = 24 * 60 * 60_000L
}

/**
 * The month in review: minutes, listens, top tracks and artists of the last calendar month, from
 * the listening history in the app. Shown once when the app is first opened in a new month (when
 * the month had any listening).
 */
object MonthReview {
    data class Review(
        val month: YearMonth,
        val minutes: Long,
        val listens: Int,
        val topTracks: List<Pair<String, Int>>,
        val topArtists: List<Pair<String, Int>>,
        val newArtists: Int,
    )

    private const val PREFS = "month_review"
    private const val KEY_SHOWN = "shown"

    /** The review of the month before this one, when it wasn't shown yet and has listening; null otherwise. */
    suspend fun pending(context: Context, database: MusicDatabase): Review? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = YearMonth.now().minusMonths(1)
        if (prefs.getString(KEY_SHOWN, null) == last.toString()) return null
        val review = build(database, last)
        if (review == null) {
            // Nothing listened that month (a new install): nothing to show, and no asking again.
            prefs.edit().putString(KEY_SHOWN, last.toString()).apply()
        }
        return review
    }

    fun markShown(context: Context, month: YearMonth) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SHOWN, month.toString()).apply()
    }

    suspend fun build(database: MusicDatabase, month: YearMonth): Review? {
        val start = month.atDay(1).atStartOfDay()
        val end = month.plusMonths(1).atDay(1).atStartOfDay()
        val all = database.events().first()
        val inMonth = all.filter { it.event.timestamp >= start && it.event.timestamp < end }
            .filter { com.metrolist.music.portrait.Listening.counts(it.event.playTime, it.song.song.duration * 1000L) }
        if (inMonth.size < MIN_LISTENS) return null
        val minutes = inMonth.sumOf { it.event.playTime } / 60_000L
        val topTracks = inMonth.groupingBy { it.song.id }.eachCount().entries.sortedByDescending { it.value }.take(5).mapNotNull { (id, n) ->
            inMonth.firstOrNull { it.song.id == id }?.let { e ->
                listOfNotNull(e.song.artists.firstOrNull()?.name, e.song.song.title).joinToString(" – ") to n
            }
        }
        val artistOf = { e: com.metrolist.music.db.entities.EventWithSong -> e.song.artists.firstOrNull()?.name }
        val topArtists = inMonth.mapNotNull(artistOf).groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        val before = all.filter { it.event.timestamp < start }.mapNotNull(artistOf).toSet()
        val newArtists = inMonth.mapNotNull(artistOf).toSet().count { it !in before }
        return Review(month, minutes, inMonth.size, topTracks, topArtists, newArtists)
    }

    private const val MIN_LISTENS = 10
}
