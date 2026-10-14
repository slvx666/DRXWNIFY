/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.portrait

import android.content.Context
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.catalog.LikedSongsCache
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.friends.FriendsHub
import com.metrolist.music.playback.SpotifyMetadataRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/** One artist of a portrait: listening time, how many of their tracks are liked, a cover. */
data class PortraitArtist(val name: String, val minutes: Int, val likes: Int, val cover: String?)

/** One track of a portrait: how many times it was played (by choice), whether it is liked. */
data class PortraitTrack(val track: FriendsHub.TrackRef, val plays: Int, val liked: Boolean)

/**
 * What someone listened to over a period: [byLikes] when there was too little listening in the app
 * and the picture comes from the likes made in that period alone.
 */
data class Portrait(
    val minutes: Int,
    val tracks: Int,
    val newLikes: Int,
    val byLikes: Boolean,
    val artists: List<PortraitArtist>,
    /** Genre to percent, strongest first; "" = everything else. */
    val genres: List<Pair<String, Int>>,
    val topTracks: List<PortraitTrack>,
)

/** A profile's whole musical portrait: three rolling periods, the last 12 calendar months and 3 tags. */
data class ProfilePortrait(
    val at: Long,
    val tags: List<String>,
    val week: Portrait?,
    val month: Portrait?,
    val year: Portrait?,
    /** "2026-10" → that month. */
    val months: Map<String, Portrait>,
)

/**
 * Builds the portrait from this phone's listening history and the account's likes.
 *
 * Listening and liking are kept apart on purpose: a played track isn't necessarily liked (radio in
 * the background), and many people don't like everything they love. So a play counts fully only
 * when the track was chosen (not the endless radio, which counts a third), a track chosen three
 * times or more gets a bonus, and likes add a little on top — they never make the top alone.
 */
object PortraitBuilder {
    private const val RADIO_WEIGHT = 0.33
    private const val REPEAT_BONUS = 2.0
    private const val LIKED_BONUS = 1.0
    private const val LIKE_ONLY_WEIGHT = 0.5

    private class Play(val songId: String, val artist: String, val at: LocalDateTime, val ms: Long, val radio: Boolean, val track: FriendsHub.TrackRef)

    private class Like(val catalogId: String?, val songId: String?, val artist: String, val at: Long, val track: FriendsHub.TrackRef)

    suspend fun build(context: Context, database: MusicDatabase): ProfilePortrait = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now()
        val since = now.minusMonths(12).withDayOfMonth(1).toLocalDate().atStartOfDay()
        val catalogIdOf = HashMap<String, String?>()
        fun catalogId(songId: String) = catalogIdOf[songId]

        val plays = database.events().first()
            .asSequence()
            .filter { it.event.timestamp.isAfter(since) }
            // History from before the rule (any 30 s counted): flicked-past tracks left out here too.
            .filter { com.metrolist.music.portrait.Listening.counts(it.event.playTime, it.song.song.duration * 1000L) }
            .mapNotNull { e ->
                val song = e.song
                val artist = song.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Play(
                    songId = song.id,
                    artist = artist,
                    at = e.event.timestamp,
                    ms = e.event.playTime,
                    radio = RadioPlays.isRadio(song.id, e.event.timestamp.toLocalDate()),
                    track = FriendsHub.TrackRef(
                        title = song.song.title.take(120),
                        artist = song.artists.joinToString { it.name }.take(120),
                        catalogId = null,
                        mediaId = song.id.takeUnless { it.startsWith("local:") },
                        cover = song.song.thumbnailUrl?.takeIf { it.startsWith("http") },
                        durationMs = song.song.duration * 1000L,
                    ),
                )
            }.toList()

        // Catalog ids of the played tracks, looked up once (to tell which plays are of liked tracks).
        plays.map { it.songId }.distinct().forEach { songId ->
            catalogIdOf[songId] = runCatching { SpotifyMetadataRegistry.catalogIdOf(database, songId) }.getOrNull()
        }
        val likes = loadLikes(database, since.atZone(zone).toInstant().toEpochMilli())
        val likedCatalogIds = likes.mapNotNullTo(HashSet()) { it.catalogId }
        val likedSongIds = likes.mapNotNullTo(HashSet()) { it.songId }
        fun isLiked(songId: String) = songId in likedSongIds || catalogId(songId)?.let { it in likedCatalogIds } == true
        val likesByArtist = likes.groupingBy { it.artist.lowercase() }.eachCount()

        // Genres for everyone who could make a top: the most played and the most liked artists.
        val candidates = (plays.groupingBy { it.artist }.eachCount().entries.sortedByDescending { it.value }.take(40).map { it.key } +
            likes.groupingBy { it.artist }.eachCount().entries.sortedByDescending { it.value }.take(20).map { it.key }).distinct()
        runCatching { Genres.ensure(candidates) }

        fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

        fun portrait(from: LocalDateTime, to: LocalDateTime, minHours: Double, minPlays: Int, minLikes: Int): Portrait? {
            val inRange = plays.filter { !it.at.isBefore(from) && it.at.isBefore(to) }
            val fromMs = ms(from)
            val toMs = ms(to)
            val newLikes = likes.filter { it.at in fromMs until toMs }
            val chosen = inRange.count { !it.radio }
            val hours = inRange.sumOf { it.ms } / 3_600_000.0
            val enoughListening = hours >= minHours && chosen >= minPlays
            if (!enoughListening && newLikes.size < minLikes) return null

            val artistScore = HashMap<String, Double>()
            val artistMs = HashMap<String, Long>()
            val artistCover = HashMap<String, String?>()
            val songScore = HashMap<String, Double>()
            val songChosen = HashMap<String, Int>()
            val songTrack = HashMap<String, FriendsHub.TrackRef>()
            val songArtist = HashMap<String, String>()

            if (enoughListening) {
                inRange.forEach { p ->
                    val w = if (p.radio) RADIO_WEIGHT else 1.0
                    artistScore.merge(p.artist, w) { a, b -> a + b }
                    artistMs.merge(p.artist, p.ms) { a, b -> a + b }
                    songScore.merge(p.songId, w) { a, b -> a + b }
                    if (!p.radio) songChosen.merge(p.songId, 1) { a, b -> a + b }
                    songTrack.putIfAbsent(p.songId, p.track)
                    songArtist[p.songId] = p.artist
                }
                songChosen.forEach { (songId, n) ->
                    if (n >= 3) {
                        songScore.merge(songId, REPEAT_BONUS) { a, b -> a + b }
                        songArtist[songId]?.let { artistScore.merge(it, REPEAT_BONUS) { a, b -> a + b } }
                    }
                }
                songScore.keys.toList().forEach { songId ->
                    if (isLiked(songId)) {
                        songScore.merge(songId, LIKED_BONUS) { a, b -> a + b }
                        songArtist[songId]?.let { artistScore.merge(it, LIKED_BONUS) { a, b -> a + b } }
                    }
                }
                newLikes.forEach { artistScore.merge(it.artist, LIKE_ONLY_WEIGHT) { a, b -> a + b } }
                // The cover of the artist's most played track stands in for their photo.
                artistScore.keys.forEach { a ->
                    artistCover[a] = songScore.entries.filter { songArtist[it.key] == a }.maxByOrNull { it.value }
                        ?.let { songTrack[it.key]?.cover }
                }
            } else {
                newLikes.forEach { l ->
                    artistScore.merge(l.artist, 1.0) { a, b -> a + b }
                    artistCover.putIfAbsent(l.artist, l.track.cover)
                }
            }

            val topArtists = artistScore.entries.sortedByDescending { it.value }.take(5).map { (name, _) ->
                PortraitArtist(
                    name = name,
                    minutes = ((artistMs[name] ?: 0L) / 60_000L).toInt(),
                    likes = likesByArtist[name.lowercase()] ?: 0,
                    cover = artistCover[name],
                )
            }
            val topTracks = if (enoughListening) {
                songScore.entries.sortedByDescending { it.value }.take(5).mapNotNull { (songId, _) ->
                    val track = songTrack[songId] ?: return@mapNotNull null
                    PortraitTrack(track.copy(catalogId = catalogId(songId)), songChosen[songId] ?: 0, isLiked(songId))
                }
            } else {
                newLikes.sortedByDescending { it.at }.take(5).map { PortraitTrack(it.track, 0, true) }
            }
            return Portrait(
                minutes = (inRange.sumOf { it.ms } / 60_000L).toInt(),
                tracks = inRange.map { it.songId }.distinct().size,
                newLikes = newLikes.size,
                byLikes = !enoughListening,
                artists = topArtists,
                genres = genreShares(artistScore),
                topTracks = topTracks,
            )
        }

        val week = portrait(now.minusDays(7), now.plusMinutes(1), 1.0, 8, 10)
        val month = portrait(now.minusDays(30), now.plusMinutes(1), 3.0, 20, 20)
        val year = portrait(now.minusDays(365), now.plusMinutes(1), 10.0, 60, 40)
        val months = (0 until 12).mapNotNull { back ->
            val ym = YearMonth.now().minusMonths(back.toLong())
            val from = ym.atDay(1).atStartOfDay()
            portrait(from, from.plusMonths(1), 3.0, 20, 10)?.let { ym.toString() to it }
        }.toMap()
        // The profile's tags: the strongest genres of the last three months.
        val tagScore = HashMap<String, Int>()
        (0 until 3).forEach { back ->
            months[YearMonth.now().minusMonths(back.toLong()).toString()]?.genres?.forEach { (g, pct) ->
                if (g.isNotBlank()) tagScore.merge(g, pct) { a, b -> a + b }
            }
        }
        val tags = tagScore.entries.sortedByDescending { it.value }.take(3).map { it.key }
            .ifEmpty { (month ?: year)?.genres?.map { it.first }?.filter { it.isNotBlank() }?.take(3).orEmpty() }
        ProfilePortrait(System.currentTimeMillis(), tags, week, month, year, months)
    }

    /**
     * Each artist's weight over their genres, the main one counting most (1, 0.4, 0.2), as
     * percentages: the top ten and the rest. Subgenres fold into their genre here (Symphonic /
     * Technical / Brutal Deathcore → Deathcore): split five ways, a genre listened to a lot ended
     * up as five small ones and the "Other" slice took most of the bar.
     */
    private fun genreShares(artistScore: Map<String, Double>): List<Pair<String, Int>> {
        val score = HashMap<String, Double>()
        artistScore.entries.sortedByDescending { it.value }.take(30).forEach { (artist, w) ->
            val genres = Genres.cached(artist).map(Genres::family).distinct().take(3)
            if (genres.isEmpty()) return@forEach
            val parts = listOf(1.0, 0.4, 0.2).take(genres.size)
            val sum = parts.sum()
            genres.forEachIndexed { i, g -> score.merge(g, w * parts[i] / sum) { a, b -> a + b } }
        }
        val total = score.values.sum().takeIf { it > 0 } ?: return emptyList()
        val sorted = score.entries.sortedByDescending { it.value }
        val top = sorted.take(MAX_GENRES).map { it.key to Math.round(it.value * 100 / total).toInt() }.filter { it.second > 0 }
        val rest = 100 - top.sumOf { it.second }
        return if (rest > 0 && sorted.size > MAX_GENRES) top + ("" to rest) else top
    }

    /** Bumped when the way a portrait is counted changes: it is rebuilt at once, not the next day. */
    const val VERSION = 4

    /** Genres shown in a portrait's bar and legend before the rest is "Other". */
    private const val MAX_GENRES = 10

    /** The likes with their dates: the account's (from the kept Liked Songs, else fetched) and the app's own hearts. */
    private suspend fun loadLikes(database: MusicDatabase, sinceMs: Long): List<Like> {
        val out = mutableListOf<Like>()
        val source = Catalog.source
        if (source != null) {
            val cached = LikedSongsCache.get(source.name)
            val added = LikedSongsCache.addedAt(source.name)
            if (cached != null && added.isNotEmpty()) {
                cached.tracks.forEach { t ->
                    val at = added[t.id] ?: return@forEach
                    out += Like(t.id, null, t.artists.firstOrNull()?.name.orEmpty(), at, refOf(t))
                }
            } else {
                // Newest first: stop at the first page older than the year asked for.
                var offset = 0
                while (offset < 2_000) {
                    val page = Catalog.likedSongs(limit = 50, offset = offset).getOrNull() ?: break
                    var older = false
                    page.items.forEach { saved ->
                        val at = parseTime(saved.addedAt) ?: return@forEach
                        if (at < sinceMs) older = true
                        val t = saved.track
                        out += Like(t.id, null, t.artists.firstOrNull()?.name.orEmpty(), at, refOf(t))
                    }
                    offset += 50
                    if (older || page.items.isEmpty() || offset >= page.total) break
                }
            }
        }
        val zone = ZoneId.systemDefault()
        database.likedSongs(com.metrolist.music.constants.SongSortType.CREATE_DATE, true).first().forEach { s ->
            val at = s.song.likedDate?.atZone(zone)?.toInstant()?.toEpochMilli() ?: return@forEach
            out += Like(
                catalogId = null,
                songId = s.id,
                artist = s.artists.firstOrNull()?.name.orEmpty(),
                at = at,
                track = FriendsHub.TrackRef(
                    title = s.song.title, artist = s.artists.joinToString { it.name }, catalogId = null,
                    mediaId = s.id, cover = s.song.thumbnailUrl, durationMs = s.song.duration * 1000L,
                ),
            )
        }
        return out.filter { it.artist.isNotBlank() }
    }

    private fun refOf(t: com.metrolist.spotify.models.SpotifyTrack) = FriendsHub.TrackRef(
        title = t.name,
        artist = t.artists.joinToString { it.name },
        catalogId = t.id,
        mediaId = null,
        cover = t.album?.images?.filter { (it.width ?: 0) in 200..400 }?.minByOrNull { it.width ?: 0 }?.url
            ?: com.metrolist.spotify.SpotifyMapper.getTrackThumbnail(t),
        durationMs = t.durationMs.toLong(),
    )

    fun parseTime(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(text.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    }
}

/** Packing a portrait for friends (gzipped JSON) and reading it back. */
object PortraitCodec {
    private const val PREFIX = "gz1:"

    fun pack(p: ProfilePortrait): String {
        val o = JSONObject()
            .put("v", 1)
            .put("at", p.at)
            .put("tags", JSONArray(p.tags))
        p.week?.let { o.put("w", toJson(it)) }
        p.month?.let { o.put("m", toJson(it)) }
        p.year?.let { o.put("y", toJson(it)) }
        o.put("months", JSONObject().apply { p.months.forEach { (k, v) -> put(k, toJson(v)) } })
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(o.toString().toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        return PREFIX + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    fun unpack(content: String): ProfilePortrait? = runCatching {
        val text = if (content.startsWith(PREFIX)) {
            val bytes = android.util.Base64.decode(content.removePrefix(PREFIX), android.util.Base64.NO_WRAP)
            java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
        } else {
            content
        }
        val o = JSONObject(text)
        val months = o.optJSONObject("months")?.let { m -> m.keys().asSequence().mapNotNull { k -> m.optJSONObject(k)?.let { k to fromJson(it) } }.toMap() }.orEmpty()
        ProfilePortrait(
            at = o.optLong("at"),
            tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { it.isNotBlank() },
            week = o.optJSONObject("w")?.let(::fromJson),
            month = o.optJSONObject("m")?.let(::fromJson),
            year = o.optJSONObject("y")?.let(::fromJson),
            months = months,
        )
    }.getOrNull()

    private fun toJson(p: Portrait) = JSONObject()
        .put("min", p.minutes).put("n", p.tracks).put("likes", p.newLikes).put("bl", p.byLikes)
        .put("a", JSONArray().apply { p.artists.forEach { put(JSONArray().put(it.name).put(it.minutes).put(it.likes).put(it.cover.orEmpty())) } })
        .put("g", JSONArray().apply { p.genres.forEach { put(JSONArray().put(it.first).put(it.second)) } })
        .put("t", JSONArray().apply {
            p.topTracks.forEach { t ->
                put(
                    JSONArray().put(t.track.title).put(t.track.artist).put(t.track.catalogId.orEmpty()).put(t.track.mediaId.orEmpty())
                        .put(t.track.cover.orEmpty()).put(t.track.durationMs / 1000).put(t.plays).put(t.liked),
                )
            }
        })

    private fun fromJson(o: JSONObject): Portrait {
        fun arr(key: String) = o.optJSONArray(key) ?: JSONArray()
        val a = arr("a")
        val g = arr("g")
        val t = arr("t")
        return Portrait(
            minutes = o.optInt("min"),
            tracks = o.optInt("n"),
            newLikes = o.optInt("likes"),
            byLikes = o.optBoolean("bl"),
            artists = (0 until a.length()).mapNotNull { i ->
                a.optJSONArray(i)?.let { r -> PortraitArtist(r.optString(0), r.optInt(1), r.optInt(2), r.optString(3).takeIf { it.isNotBlank() }) }
            },
            genres = (0 until g.length()).mapNotNull { i -> g.optJSONArray(i)?.let { r -> r.optString(0) to r.optInt(1) } },
            topTracks = (0 until t.length()).mapNotNull { i ->
                t.optJSONArray(i)?.let { r ->
                    PortraitTrack(
                        FriendsHub.TrackRef(
                            title = r.optString(0),
                            artist = r.optString(1),
                            catalogId = r.optString(2).takeIf { it.isNotBlank() },
                            mediaId = r.optString(3).takeIf { it.isNotBlank() },
                            cover = r.optString(4).takeIf { it.isNotBlank() },
                            durationMs = r.optLong(5) * 1000,
                        ),
                        r.optInt(6),
                        r.optBoolean(7),
                    )
                }
            },
        )
    }
}
