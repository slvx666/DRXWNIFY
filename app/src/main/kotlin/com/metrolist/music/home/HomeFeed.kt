/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.home

import android.content.Context
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.catalog.LikedSongsCache
import com.metrolist.music.constants.ArtistSortType
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.genre.GenrePlaylists
import com.metrolist.music.portrait.Genres
import com.metrolist.music.portrait.Listening
import com.metrolist.music.portrait.RadioPlays
import com.metrolist.music.utils.SPOTIFY_ID_PREFIX
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifySimpleArtist
import com.metrolist.spotify.models.SpotifyTrack
import com.metrolist.yandex.YandexIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import timber.log.Timber
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.exp
import kotlin.random.Random

/**
 * A row of tracks from artists close to the listener's: "More like <artist>" ([genre] null) or
 * "More <genre>" — artists close to the ones the listener loves in that genre.
 */
@Serializable
data class SimilarRow(
    val artist: String,
    val artistId: String? = null,
    val image: String? = null,
    val tracks: List<SpotifyTrack> = emptyList(),
    val genre: String? = null,
)

/** The personal part of the home page, built from what the listener likes, follows and plays. */
@Serializable
data class HomeFeedData(
    val builtAt: Long = 0,
    /** [HomeFeed.FORMAT] it was built with: an older one is rebuilt at once. */
    val format: Int = 0,
    /** Releases of the favourite artists from the last months, newest first. */
    val newReleases: List<SpotifyAlbum> = emptyList(),
    /** "More like <artist>" for two of the favourites, a different pair each day and each refresh. */
    val similar: List<SimilarRow> = emptyList(),
    /** "More <genre>": the two main genres and the one with many likes but less listening. */
    val genreRows: List<SimilarRow> = emptyList(),
    /** The listener's main genres, strongest first (each opens a genre playlist). */
    val genres: List<String> = emptyList(),
    /** Liked tracks not heard for a long time. */
    val forgotten: List<SpotifyTrack> = emptyList(),
    /** "Deeper into favourites": album tracks of loved artists, not their hits, not heard yet. */
    val deepCuts: List<SpotifyTrack> = emptyList(),
    /** The favourite artists by name, strongest first (other rows check against them). */
    val favoriteArtists: List<String> = emptyList(),
)

/**
 * Builds the home page's personal rows and keeps them on disk: the page shows the last ones at once
 * and they are rebuilt behind it when older than [TTL_MS] (or on a pull to refresh). Every row is
 * published as soon as it is ready, so one slow source never holds the others back.
 *
 * "Favourite" means loved, not just played: likes, followed artists and tracks played again and
 * again count; plain plays only break ties. An album listened to many times for a review doesn't
 * make its artist a favourite — that is what kept pushing a disliked artist into "More like".
 */
object HomeFeed {
    private const val TTL_MS = 6 * 60 * 60_000L
    private const val FORGOTTEN_AFTER_DAYS = 60L

    /** Bumped when the rows are chosen differently: a feed kept from before is rebuilt on the next start. */
    const val FORMAT = 3

    /** Below this an artist is only listened to, not loved: never a seed for recommendations. */
    private const val MIN_LOVE = 1.0

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private var file: File? = null
    private val buildMutex = Mutex()

    private val _feed = MutableStateFlow<HomeFeedData?>(null)
    val feed: StateFlow<HomeFeedData?> = _feed

    fun init(context: Context) {
        val f = File(context.filesDir, "home_feed.json")
        file = f
        runCatching {
            if (f.exists()) {
                val saved = json.decodeFromString(HomeFeedData.serializer(), f.readText())
                _feed.value = if (saved.format == FORMAT) saved else saved.copy(similar = emptyList(), genreRows = emptyList())
            }
        }.onFailure { Timber.w(it, "HomeFeed: saved feed unreadable") }
    }

    /** Rebuilds the feed when it is older than [TTL_MS], or always with [force] (a pull to refresh). */
    suspend fun refresh(database: MusicDatabase, hideExplicit: Boolean, force: Boolean = false) = withContext(Dispatchers.IO) {
        fun fresh() = _feed.value?.let { it.format == FORMAT && System.currentTimeMillis() - it.builtAt < TTL_MS } == true
        if (!force && fresh()) return@withContext
        buildMutex.withLock {
            if (!force && fresh()) return@withLock
            if (!Catalog.ensureAuthenticated()) return@withLock
            // The day picks the rows; a pull to refresh picks anew.
            val rotation = if (force) Random.nextLong() else LocalDate.now().toEpochDay()
            build(database, hideExplicit, rotation)
        }
    }

    private data class Favorite(val name: String, val id: String?, val score: Double)

    private suspend fun build(database: MusicDatabase, hideExplicit: Boolean, rotation: Long) = coroutineScope {
        val taste = taste(database)
        val favorites = resolveIds(taste.favorites)
        val catalogGate = Semaphore(6)
        if (_feed.value == null) _feed.value = HomeFeedData()
        _feed.update { it?.copy(favoriteArtists = favorites.map { f -> f.name }) }

        val stats = async { section("genres") { genreStats(taste) } }
        val parts = listOf(
            launch { section("deepCuts") { deepCuts(favorites, taste, hideExplicit, rotation, catalogGate).let { r -> _feed.update { it?.copy(deepCuts = r) } } } },
            launch { section("similar") { similar(favorites, taste, hideExplicit, rotation, catalogGate).let { r -> _feed.update { it?.copy(similar = r) } } } },
            launch { section("forgotten") { forgotten(taste, hideExplicit, rotation).let { r -> _feed.update { it?.copy(forgotten = r) } } } },
            launch {
                val s = stats.await() ?: return@launch
                _feed.update { it?.copy(genres = s.top) }
                section("genreRows") {
                    genreRows(s, favorites, taste, hideExplicit, rotation, catalogGate).let { r -> _feed.update { it?.copy(genreRows = r) } }
                }
            },
        )
        parts.forEach { it.join() }
        _feed.update { it?.copy(builtAt = System.currentTimeMillis(), format = FORMAT) }
        _feed.value?.let { save(it) }
    }

    private suspend fun <T> section(name: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.w(e, "HomeFeed: '$name' failed")
            null
        }

    // ── Taste ────────────────────────────────────────────────────────────────────────────────────

    private class Taste(
        val favorites: List<Favorite>,
        /** "artist|title" of everything liked or played: not suggested as new. */
        val known: Set<String>,
        /** Lower-case names of every artist liked, followed or played: "new" artists are none of these. */
        val knownArtists: Set<String>,
        /** "artist|title" played in the last [FORGOTTEN_AFTER_DAYS] days. */
        val recentlyHeard: Set<String>,
        /** "artist|album" of albums played from. */
        val playedAlbums: Set<String>,
        val likedTracks: List<SpotifyTrack>,
        val likedAt: Map<String, Long>,
        /** Per artist (lower case): likes and chosen plays, for the genre rows. */
        val likesByArtist: Map<String, Int>,
        val playsByArtist: Map<String, Double>,
    )

    private suspend fun taste(database: MusicDatabase): Taste {
        val love = HashMap<String, Double>()
        val names = HashMap<String, String>()
        val ids = HashMap<String, String>()
        val known = HashSet<String>()
        val knownArtists = HashSet<String>()
        val recent = HashSet<String>()
        val albums = HashSet<String>()
        val likes = HashMap<String, Int>()
        val plays = HashMap<String, Double>()

        fun note(name: String, id: String?): String? {
            val n = name.trim()
            if (n.isBlank()) return null
            val key = n.lowercase()
            names.putIfAbsent(key, n)
            catalogIdOf(id)?.let { ids.putIfAbsent(key, it) }
            knownArtists += key
            return key
        }

        val now = LocalDateTime.now()
        val chosenPerTrack = HashMap<String, Int>()
        val artistOfTrack = HashMap<String, String>()
        database.events().first().take(5000).forEach { e ->
            val song = e.song
            val artist = song.artists.firstOrNull() ?: return@forEach
            val key = note(artist.name, artist.id) ?: return@forEach
            val ageDays = Duration.between(e.event.timestamp, now).toHours() / 24.0
            val trackKey = trackKey(artist.name, song.song.title)
            known += trackKey
            if (ageDays <= FORGOTTEN_AFTER_DAYS) recent += trackKey
            song.song.albumName?.takeIf { it.isNotBlank() }?.let { albums += albumKey(artist.name, it) }
            if (ageDays > 240 || !Listening.counts(e.event.playTime, song.song.duration * 1000L)) return@forEach
            if (RadioPlays.isRadio(song.id, e.event.timestamp.toLocalDate())) return@forEach
            val weight = exp(-ageDays / 90.0)
            plays.merge(key, weight, Double::plus)
            chosenPerTrack.merge(trackKey, 1, Int::plus)
            artistOfTrack[trackKey] = key
        }
        // Played again and again (chosen, not the radio): that is love too, a bit less than a like —
        // capped per artist, so an album played over and over for a review doesn't outweigh likes.
        val repeats = HashMap<String, Double>()
        chosenPerTrack.forEach { (track, n) -> if (n >= 3) artistOfTrack[track]?.let { repeats.merge(it, 0.6, Double::plus) } }
        repeats.forEach { (key, r) -> love.merge(key, minOf(1.2, r), Double::plus) }
        // Plain plays only break ties — capped, so a lot of listening alone never reaches MIN_LOVE.
        plays.forEach { (key, p) -> love.merge(key, minOf(0.5, p * 0.02), Double::plus) }

        // Likes say what is loved, however long ago.
        val source = Catalog.source
        val liked = source?.let { LikedSongsCache.get(it.name) }?.tracks.orEmpty()
        val likedAt = source?.let { LikedSongsCache.addedAt(it.name) }.orEmpty()
        liked.forEach { t ->
            val artist = t.artists.firstOrNull() ?: return@forEach
            val key = note(artist.name, artist.id) ?: return@forEach
            known += trackKey(artist.name, t.name)
            love.merge(key, 1.0, Double::plus)
            likes.merge(key, 1, Int::plus)
        }
        database.likedSongs(SongSortType.CREATE_DATE, descending = true).first().forEach { s ->
            val artist = s.artists.firstOrNull() ?: return@forEach
            val key = note(artist.name, artist.id) ?: return@forEach
            known += trackKey(artist.name, s.song.title)
            love.merge(key, 1.0, Double::plus)
            likes.merge(key, 1, Int::plus)
        }
        database.artistsBookmarked(ArtistSortType.CREATE_DATE, descending = true).first().forEach { a ->
            val key = note(a.artist.name, a.artist.id) ?: return@forEach
            love.merge(key, 4.0, Double::plus)
        }

        val favorites = love.entries.filter { it.value >= MIN_LOVE }.sortedByDescending { it.value }.take(40).map { (key, s) ->
            Favorite(names.getValue(key), ids[key], s)
        }
        return Taste(favorites, known, knownArtists, recent, albums, liked, likedAt, likes, plays)
    }

    /** Catalog ids for the strongest favourites that only have a name (YouTube or local tracks). */
    private suspend fun resolveIds(favorites: List<Favorite>): List<Favorite> = coroutineScope {
        val gate = Semaphore(4)
        favorites.mapIndexed { i, f ->
            async {
                if (f.id != null || i >= 20) return@async f
                gate.withPermit { f.copy(id = findArtist(f.name)?.id) }
            }
        }.awaitAll()
    }

    private suspend fun findArtist(name: String): SpotifyArtist? = runCatching {
        Catalog.search(name, types = listOf("artist"), limit = 3).getOrNull()?.artists?.items
            ?.firstOrNull { it.name.equals(name, ignoreCase = true) && it.id.isNotBlank() }
    }.getOrNull()

    /** [count] favourites drawn at random, the more loved the likelier, from the strongest [pool]. */
    private fun pick(favorites: List<Favorite>, count: Int, pool: Int, random: Random): List<Favorite> {
        val left = favorites.filter { it.id != null }.take(pool).toMutableList()
        val out = ArrayList<Favorite>()
        while (out.size < count && left.isNotEmpty()) {
            var r = random.nextDouble(left.sumOf { it.score })
            val index = left.indexOfFirst { r -= it.score; r <= 0 }.let { if (it < 0) left.lastIndex else it }
            out += left.removeAt(index)
        }
        return out
    }

    // ── Rows ─────────────────────────────────────────────────────────────────────────────────────

    /** "More like": two favourites, drawn anew each day (and each refresh), and tracks of artists close to them. */
    private suspend fun similar(
        favorites: List<Favorite>,
        taste: Taste,
        hideExplicit: Boolean,
        rotation: Long,
        gate: Semaphore,
    ): List<SimilarRow> = coroutineScope {
        val random = Random(rotation)
        pick(favorites, 2, pool = 12, random).mapIndexed { index, seed ->
            async {
                val rowRandom = Random(rotation * 31 + index)
                val image = async { gate.withPermit { artistImage(seed.id!!) } }
                val spotifyRelated = gate.withPermit { Catalog.relatedArtists(seed.id!!).getOrNull().orEmpty() }
                    .filter { it.id.isNotBlank() && it.name.lowercase() != seed.name.lowercase() }
                val alsoOnLastFm = lastFmSimilar(seed.name)
                // Both services agree first: Spotify's list alone drifted away from the artist now and then.
                val agreed = spotifyRelated.filter { norm(it.name) in alsoOnLastFm }
                val related = (if (agreed.size >= 5) agreed else spotifyRelated.take(10))
                    .take(15)
                    .shuffled(rowRandom)
                    .take(7)
                android.util.Log.i("DrxwHome", "more like ${seed.name} (${seed.id}): ${related.joinToString { it.name }} [agreed ${agreed.size}/${spotifyRelated.size}]")
                val tracks = tracksOf(related.map { it.id }, taste, hideExplicit, rowRandom, gate)
                SimilarRow(seed.name, seed.id, image.await(), tracks)
            }
        }.awaitAll().filter { it.tracks.size >= 4 }
    }

    private class GenreStats(val top: List<String>, val rows: List<String>, val artistsOf: Map<String, List<String>>)

    /**
     * Genres too wide to say anything about taste: "Metal" brought old-school heavy metal to someone
     * who listens to progressive metalcore. Rows and tiles use the narrower tags.
     */
    private val UMBRELLA = setOf(
        "metal", "rock", "pop", "hip-hop", "electronic", "alternative rock", "alternative", "indie",
        "punk", "dance", "experimental", "electronic dance", "hard rock", "heavy metal", "soundtrack",
        "classical", "jazz", "folk", "r&b", "soul", "rap",
    )

    /**
     * The listener's genres: each artist's genres (Last.fm tags) weighted by how loved and how
     * listened to the artist is. Rows go to the two strongest genres and to the one with the most
     * likes for how little it is listened to (the genre loved more than played).
     */
    private suspend fun genreStats(taste: Taste): GenreStats {
        val artists = (taste.favorites.take(40).map { it.name } +
            taste.playsByArtist.entries.sortedByDescending { it.value }.take(30).map { it.key }).distinctBy { it.lowercase() }
        runCatching { Genres.ensure(artists) }
        val loveScore = HashMap<String, Double>()
        val likeScore = HashMap<String, Double>()
        val playScore = HashMap<String, Double>()
        val artistsOf = HashMap<String, MutableList<String>>()
        val favoriteScore = taste.favorites.associate { it.name.lowercase() to it.score }
        artists.forEach { name ->
            val key = name.lowercase()
            val genres = Genres.cached(name).filter { it.lowercase() !in UMBRELLA }.distinct().take(2)
            genres.forEachIndexed { i, g ->
                val part = if (i == 0) 1.0 else 0.5
                loveScore.merge(g, (favoriteScore[key] ?: 0.0) * part, Double::plus)
                likeScore.merge(g, (taste.likesByArtist[key] ?: 0) * part, Double::plus)
                playScore.merge(g, (taste.playsByArtist[key] ?: 0.0) * part, Double::plus)
                artistsOf.getOrPut(g) { mutableListOf() } += name
            }
        }
        val totalLove = loveScore.values.sum().coerceAtLeast(1.0)
        val totalPlays = playScore.values.sum().coerceAtLeast(1.0)
        val totalLikes = likeScore.values.sum().coerceAtLeast(1.0)
        val combined = loveScore.keys.associateWith { g -> loveScore[g]!! / totalLove + (playScore[g] ?: 0.0) / totalPlays }
        val top = combined.entries.sortedByDescending { it.value }.map { it.key }
        // A row needs a few loved artists of its own to start from.
        val rowWorthy = top.filter { g -> (artistsOf[g]?.count { favoriteScore.containsKey(it.lowercase()) } ?: 0) >= 2 }
        val main = rowWorthy.take(2)
        val lovedMore = likeScore.entries
            .filter { it.key !in main && it.key in rowWorthy && it.value >= 3 }
            .maxByOrNull { (g, likes) -> (likes / totalLikes) / ((playScore[g] ?: 0.0) / totalPlays + 0.02) }
            ?.key
        return GenreStats(top.take(10), main + listOfNotNull(lovedMore), artistsOf)
    }

    /**
     * "More <genre>": artists close to the listener's favourites in that genre ("fans also like" of
     * up to four of them), kept to the genre by its Last.fm artists or known tags, and new to the
     * listener. Never the genre's general chart: that is what brought old classics nobody asked for.
     */
    private suspend fun genreRows(
        stats: GenreStats,
        favorites: List<Favorite>,
        taste: Taste,
        hideExplicit: Boolean,
        rotation: Long,
        gate: Semaphore,
    ): List<SimilarRow> = coroutineScope {
        val byName = favorites.associateBy { it.name.lowercase() }
        stats.rows.mapIndexed { index, genre ->
            async {
                val random = Random(rotation * 17 + index)
                val ofGenre = runCatching { GenrePlaylists.topArtistsOf(genre) }.getOrDefault(emptySet())
                fun inGenre(name: String) = norm(name) in ofGenre ||
                    Genres.cached(name).any { it.equals(genre, ignoreCase = true) }

                val mine = stats.artistsOf[genre].orEmpty().mapNotNull { byName[it.lowercase()] }.filter { it.id != null }
                val seeds = pick(mine, 4, pool = 8, random)
                val related = seeds.map { seed ->
                    async { gate.withPermit { Catalog.relatedArtists(seed.id!!).getOrNull().orEmpty() } }
                }.awaitAll().flatten()
                    .filter { it.id.isNotBlank() && it.name.lowercase() !in taste.knownArtists }
                // Close to several of the listener's artists first, then the rest.
                val counted = related.groupingBy { it.id }.eachCount()
                val candidates = related.distinctBy { it.id }
                    .sortedByDescending { (counted[it.id] ?: 0) + if (inGenre(it.name)) 2 else 0 }
                    // In-genre ones first; a narrow genre with few of them is filled up with the rest.
                    .let { list -> (list.filter { inGenre(it.name) } + list.filterNot { inGenre(it.name) }).take(12) }
                    .shuffled(random)
                    .take(7)
                android.util.Log.i("DrxwHome", "more $genre from ${seeds.joinToString { it.name }}: ${candidates.joinToString { it.name }}")
                val tracks = tracksOf(candidates.map { it.id }, taste, hideExplicit, random, gate)
                SimilarRow(artist = seeds.firstOrNull()?.name.orEmpty(), artistId = null, image = null, tracks = tracks, genre = genre)
            }
        }.awaitAll().filter { it.tracks.size >= 4 }
    }

    /**
     * "Deeper into favourites": tracks from the albums of loved artists that are neither their hits
     * nor heard yet — the part of a discography the radio never gets to.
     */
    private suspend fun deepCuts(
        favorites: List<Favorite>,
        taste: Taste,
        hideExplicit: Boolean,
        rotation: Long,
        gate: Semaphore,
    ): List<SpotifyTrack> = coroutineScope {
        val random = Random(rotation * 7 + 3)
        pick(favorites, 5, pool = 15, random).map { seed ->
            async {
                val hits = gate.withPermit { Catalog.artistTopTracks(seed.id!!).getOrNull()?.tracks.orEmpty() }.mapTo(HashSet()) { it.id }
                val albums = gate.withPermit { Catalog.latestReleases(seed.id!!).getOrNull().orEmpty() }
                    .filter { it.totalTracks >= 4 || it.albumType.equals("album", ignoreCase = true) }
                    .shuffled(random)
                    .take(2)
                albums.flatMap { release ->
                    val album = gate.withPermit { Catalog.album(release.id).getOrNull() } ?: return@flatMap emptyList()
                    val cover = com.metrolist.spotify.models.SpotifySimpleAlbum(
                        id = album.id, name = album.name, images = album.images.ifEmpty { release.images },
                        releaseDate = album.releaseDate, albumType = album.albumType, artists = album.artists,
                    )
                    album.tracks?.items.orEmpty()
                        .filter { t ->
                            t.id.isNotEmpty() && t.id !in hits && !t.isLocal && (!hideExplicit || !t.explicit) &&
                                trackKey(t.artists.firstOrNull()?.name ?: seed.name, t.name) !in taste.known
                        }
                        .shuffled(random)
                        .take(2)
                        .map { t -> if (t.album?.images.isNullOrEmpty()) t.copy(album = cover) else t }
                }
            }
        }.awaitAll().let(::interleave).take(14)
    }

    /** Last.fm's similar artists of [artist], normalized; empty when it doesn't know them. Kept for the run. */
    private suspend fun lastFmSimilar(artist: String): Set<String> = withContext(Dispatchers.IO) {
        similarMemory[artist.lowercase()]?.let { return@withContext it }
        val names = runCatching {
            val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrl().newBuilder()
                .addQueryParameter("method", "artist.getsimilar")
                .addQueryParameter("artist", artist)
                .addQueryParameter("autocorrect", "1")
                .addQueryParameter("limit", "150")
                .addQueryParameter("api_key", Genres.LASTFM_KEY)
                .addQueryParameter("format", "json")
                .build()
            val body = Genres.client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.body?.string() }
            val list = org.json.JSONObject(body ?: "{}").optJSONObject("similarartists")?.optJSONArray("artist")
            (0 until (list?.length() ?: 0)).mapNotNullTo(HashSet()) { i -> list?.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::norm) }
        }.getOrDefault(emptySet())
        if (names.isNotEmpty()) similarMemory[artist.lowercase()] = names
        names
    }

    private val similarMemory = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /** Two new-to-the-listener top tracks of each artist, interleaved. */
    private suspend fun tracksOf(
        artistIds: List<String>,
        taste: Taste,
        hideExplicit: Boolean,
        random: Random,
        gate: Semaphore,
    ): List<SpotifyTrack> = coroutineScope {
        val lists = artistIds.map { id ->
            async {
                gate.withPermit { Catalog.artistTopTracks(id).getOrNull()?.tracks.orEmpty() }
                    .filter { t ->
                        t.id.isNotEmpty() && !t.isLocal && (!hideExplicit || !t.explicit) &&
                            trackKey(t.artists.firstOrNull()?.name.orEmpty(), t.name) !in taste.known
                    }
                    .take(5)
                    .shuffled(random)
                    .take(2)
            }
        }.awaitAll()
        interleave(lists).take(14)
    }

    private suspend fun artistImage(id: String): String? =
        Catalog.artist(id).getOrNull()?.images
            ?.let { imgs -> imgs.firstOrNull { (it.width ?: 0) in 200..400 } ?: imgs.firstOrNull() }?.url

    private fun forgotten(taste: Taste, hideExplicit: Boolean, rotation: Long): List<SpotifyTrack> {
        val longAgo = System.currentTimeMillis() - FORGOTTEN_AFTER_DAYS * 24 * 60 * 60_000L
        return taste.likedTracks
            .filter { t ->
                t.id.isNotEmpty() && (!hideExplicit || !t.explicit) &&
                    (taste.likedAt[t.id] ?: 0L) in 1 until longAgo &&
                    trackKey(t.artists.firstOrNull()?.name.orEmpty(), t.name) !in taste.recentlyHeard
            }
            .shuffled(Random(rotation))
            .distinctBy { it.artists.firstOrNull()?.name?.lowercase() }
            .take(16)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun catalogIdOf(id: String?): String? = when {
        id.isNullOrBlank() -> null
        id.startsWith(SPOTIFY_ID_PREFIX) -> id.removePrefix(SPOTIFY_ID_PREFIX)
        YandexIds.isYandex(id) -> id
        // A plain catalog id, as the account's liked tracks carry them.
        id.length == 22 && id.all { it.isLetterOrDigit() } -> id
        else -> null
    }

    private fun norm(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    private fun trackKey(artist: String, title: String): String =
        artist.lowercase().filter { it.isLetterOrDigit() } + "|" +
            title.lowercase().substringBefore(" (").substringBefore(" - ").filter { it.isLetterOrDigit() }

    private fun albumKey(artist: String, album: String): String =
        artist.lowercase().filter { it.isLetterOrDigit() } + "|" + album.lowercase().substringBefore(" (").filter { it.isLetterOrDigit() }

    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val queues = lists.map { ArrayDeque(it) }
        val out = ArrayList<T>()
        while (queues.any { it.isNotEmpty() }) queues.forEach { q -> q.removeFirstOrNull()?.let(out::add) }
        return out
    }

    private fun save(data: HomeFeedData) {
        runCatching { file?.writeText(json.encodeToString(HomeFeedData.serializer(), data)) }
    }
}
