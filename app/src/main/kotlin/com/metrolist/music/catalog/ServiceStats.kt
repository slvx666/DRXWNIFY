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
import kotlinx.coroutines.async
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
    data class ArtistCount(
        val name: String,
        val count: Int,
        /** A liked track of the artist: it plays in the story. */
        val track: SpotifyTrack? = null,
        /** The artist's picture from Yandex. */
        val image: String? = null,
    )

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
        // "yandex2": the copies before it had no artist pictures.
        val saved = read<YandexStats>("yandex2")
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
            val byArtist = tracks.groupBy { it.track.artists.firstOrNull()?.name }.filterKeys { it != null }
            val artists = kotlinx.coroutines.coroutineScope {
                byArtist.entries.sortedByDescending { it.value.size }.take(15).map { (name, liked) ->
                    val track = liked.first().track
                    async {
                        // The artist's own picture (the liked track names the artist by id).
                        val picture = track.artists.firstOrNull()?.id?.takeIf { it.isNotBlank() }
                            ?.let { YandexMusic.artist(it).getOrNull()?.images?.maxByOrNull { img -> img.width ?: 0 }?.url }
                        ArtistCount(name!!, liked.size, track, picture ?: track.album?.images?.firstOrNull()?.url)
                    }
                }.map { it.await() }
            }
            YandexStats(refs.size, byMonth, artists, System.currentTimeMillis()).also { write("yandex2", it) }
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
    /** A track or an artist of the month, with how many listens and its picture. */
    data class Item(
        val title: String,
        val subtitle: String?,
        val count: Int,
        val image: String?,
        val minutes: Long = 0,
        /** The song to play on its page: the track itself, or the artist's most played one. */
        val songId: String? = null,
        /** A track: the day it was first played here, and its listens since then (all time). */
        val since: java.time.LocalDate? = null,
        val sinceCount: Int = 0,
        /** An artist: the most days in a row it was played this month. */
        val streakDays: Int = 0,
    )

    data class Review(
        val month: YearMonth,
        val minutes: Long,
        val listens: Int,
        val topTracks: List<Item>,
        val topArtists: List<Item>,
        val newArtists: Int,
        val distinctArtists: Int,
        val distinctTracks: Int,
        /** The day with the most listening, and how many minutes it had. */
        val busiestDay: java.time.LocalDate?,
        val busiestDayMinutes: Long,
        /** The hour of the day most listened at (0–23). */
        val topHour: Int?,
        /** The month's genres by listens (from the artists' Last.fm tags), the biggest first. */
        val genres: List<Pair<String, Int>> = emptyList(),
        /** The genre leading the first half of the month, and the one leading the second. */
        val genreEarly: String? = null,
        val genreLate: String? = null,
        /** Songs of the top genre for its page: liked ones, else ones played this month. */
        val genreSongs: List<String> = emptyList(),
        /**
         * A service's own tops instead of the app's listening (its name, e.g. "Spotify"): no minutes,
         * days or genres, and the counts only where the service gives them.
         */
        val service: String? = null,
        /** What the service's tops cover, said on the first page and the card ("the last 4 weeks"). */
        val period: String? = null,
        /** Yandex: all the likes, and the month with the most of them. */
        val likesTotal: Int = 0,
        val likesBestMonth: YearMonth? = null,
        val likesBestCount: Int = 0,
        /** A song not in the app's database (a service's track) by its key: something to play. */
        val media: (suspend (String) -> androidx.media3.common.MediaItem?)? = null,
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
        val topTracks = inMonth.groupBy { it.song.id }.entries.sortedByDescending { it.value.size }.take(21).map { (_, events) ->
            val e = events.first()
            Item(
                title = e.song.song.title,
                subtitle = e.song.artists.joinToString { it.name }.ifBlank { null },
                count = events.size,
                image = e.song.song.thumbnailUrl,
                minutes = events.sumOf { it.event.playTime } / 60_000L,
                songId = e.song.id,
                since = all.filter { it.song.id == e.song.id }.minOfOrNull { it.event.timestamp }?.toLocalDate(),
                sinceCount = all.count { it.song.id == e.song.id },
            )
        }
        val artistOf = { e: com.metrolist.music.db.entities.EventWithSong -> e.song.artists.firstOrNull()?.name }
        val topArtists = inMonth.filter { artistOf(it) != null }.groupBy { artistOf(it)!! }.entries
            .sortedByDescending { it.value.size }.take(11).map { (name, events) ->
                Item(
                    title = name,
                    subtitle = null,
                    count = events.size,
                    // The artist's photo when the app has one, else the cover of their most played track.
                    image = events.firstNotNullOfOrNull { it.song.artists.firstOrNull()?.thumbnailUrl }
                        ?: events.groupingBy { it.song.id }.eachCount().maxByOrNull { it.value }
                            ?.let { top -> events.first { it.song.id == top.key }.song.song.thumbnailUrl },
                    minutes = events.sumOf { it.event.playTime } / 60_000L,
                    songId = events.groupingBy { it.song.id }.eachCount().maxByOrNull { it.value }?.key,
                    streakDays = longestRun(events.map { it.event.timestamp.toLocalDate() }.toSet()),
                )
            }
        val before = all.filter { it.event.timestamp < start }.mapNotNull(artistOf).toSet()
        val newArtists = inMonth.mapNotNull(artistOf).toSet().count { it !in before }
        val byDay = inMonth.groupBy { it.event.timestamp.toLocalDate() }.mapValues { (_, v) -> v.sumOf { it.event.playTime } / 60_000L }
        val busiest = byDay.maxByOrNull { it.value }
        val topHour = inMonth.groupingBy { it.event.timestamp.hour }.eachCount().maxByOrNull { it.value }?.key
        // Genres: each listen counts for its artist's genres (the 30 most played artists are looked up).
        val listensByArtist = inMonth.mapNotNull(artistOf).groupingBy { it }.eachCount()
        val topNames = listensByArtist.entries.sortedByDescending { it.value }.take(30).map { it.key }
        runCatching { com.metrolist.music.portrait.Genres.ensure(topNames) }
        val genreCounts = HashMap<String, Int>()
        topNames.forEach { name ->
            com.metrolist.music.portrait.Genres.cached(name).take(2).forEach { g -> genreCounts.merge(g, listensByArtist[name] ?: 0, Int::plus) }
        }
        val genres = genreCounts.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        // How the taste moved: the leading genre of each half of the month.
        val half = month.atDay(minOf(16, month.lengthOfMonth())).atStartOfDay()
        fun leading(events: List<com.metrolist.music.db.entities.EventWithSong>): String? {
            val counts = HashMap<String, Int>()
            events.mapNotNull(artistOf).forEach { name ->
                com.metrolist.music.portrait.Genres.cached(name).take(2).forEach { g -> counts.merge(g, 1, Int::plus) }
            }
            return counts.maxByOrNull { it.value }?.key
        }
        // The top genre's songs: liked ones by its artists first, else this month's plays of them.
        val topGenre = genres.firstOrNull()?.first
        val genreSongs = if (topGenre == null) emptyList() else {
            fun ofGenre(artist: String?) = artist != null && topGenre in com.metrolist.music.portrait.Genres.cached(artist)
            val liked = runCatching {
                database.likedSongs(com.metrolist.music.constants.SongSortType.CREATE_DATE, true).first()
            }.getOrDefault(emptyList()).filter { ofGenre(it.artists.firstOrNull()?.name) }.map { it.id }
            liked.ifEmpty { inMonth.filter { ofGenre(artistOf(it)) }.map { it.song.id }.distinct() }
        }
        val genreEarly = leading(inMonth.filter { it.event.timestamp < half })
        val genreLate = leading(inMonth.filter { it.event.timestamp >= half })
        return Review(
            month = month,
            minutes = minutes,
            listens = inMonth.size,
            topTracks = topTracks,
            topArtists = topArtists,
            newArtists = newArtists,
            distinctArtists = inMonth.mapNotNull(artistOf).toSet().size,
            distinctTracks = inMonth.map { it.song.id }.toSet().size,
            busiestDay = busiest?.key,
            busiestDayMinutes = busiest?.value ?: 0,
            topHour = topHour,
            genres = genres,
            genreEarly = genreEarly,
            genreLate = genreLate,
            genreSongs = genreSongs.take(200),
        )
    }

    private const val MIN_LISTENS = 10

    /** The longest run of consecutive days in [days]. */
    private fun longestRun(days: Set<java.time.LocalDate>): Int {
        var best = 0
        for (d in days) {
            if (d.minusDays(1) in days) continue
            var n = 1
            while (d.plusDays(n.toLong()) in days) n++
            best = maxOf(best, n)
        }
        return best
    }
}
