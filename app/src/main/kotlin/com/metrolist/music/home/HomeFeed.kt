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
import com.metrolist.music.portrait.Genres
import com.metrolist.music.portrait.Listening
import com.metrolist.music.portrait.RadioPlays
import com.metrolist.music.utils.SPOTIFY_ID_PREFIX
import com.metrolist.spotify.models.SpotifyAlbum
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
import timber.log.Timber
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.exp
import kotlin.random.Random

/** "More like <artist>": tracks of artists close to one of the listener's favourites. */
@Serializable
data class SimilarRow(
    val artist: String,
    val artistId: String? = null,
    val image: String? = null,
    val tracks: List<SpotifyTrack> = emptyList(),
)

/** The personal part of the home page, built from what the listener plays, likes and follows. */
@Serializable
data class HomeFeedData(
    val builtAt: Long = 0,
    /** Releases of the favourite artists from the last months, newest first. */
    val newReleases: List<SpotifyAlbum> = emptyList(),
    val similar: List<SimilarRow> = emptyList(),
    /** The listener's main genres, strongest first (each opens a genre playlist). */
    val genres: List<String> = emptyList(),
    /** Liked tracks not heard for a long time. */
    val forgotten: List<SpotifyTrack> = emptyList(),
)

/**
 * Builds the home page's personal rows and keeps them on disk: the page shows the last ones at once
 * and they are rebuilt behind it when older than [TTL_MS]. Every row is published as soon as it is
 * ready, so one slow source never holds the others back.
 *
 * Favourite artists are counted the way the profile portrait counts them: chosen plays of the last
 * months (the radio counts a third, older plays less), likes of any age and followed artists — so
 * "More like" is about the artists the listener really loves, not the last one played.
 */
object HomeFeed {
    private const val TTL_MS = 6 * 60 * 60_000L
    private const val NEW_RELEASE_DAYS = 120L
    private const val FORGOTTEN_AFTER_DAYS = 60L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private var file: File? = null
    private val buildMutex = Mutex()

    private val _feed = MutableStateFlow<HomeFeedData?>(null)
    val feed: StateFlow<HomeFeedData?> = _feed

    fun init(context: Context) {
        val f = File(context.filesDir, "home_feed.json")
        file = f
        runCatching {
            if (f.exists()) _feed.value = json.decodeFromString(HomeFeedData.serializer(), f.readText())
        }.onFailure { Timber.w(it, "HomeFeed: saved feed unreadable") }
    }

    /** Rebuilds the feed when it is older than [TTL_MS] (or always with [force]). */
    suspend fun refresh(database: MusicDatabase, hideExplicit: Boolean, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force && System.currentTimeMillis() - (_feed.value?.builtAt ?: 0L) < TTL_MS) return@withContext
        buildMutex.withLock {
            if (!force && System.currentTimeMillis() - (_feed.value?.builtAt ?: 0L) < TTL_MS) return@withLock
            if (!Catalog.ensureAuthenticated()) return@withLock
            build(database, hideExplicit)
        }
    }

    private data class Favorite(val name: String, val id: String?, val score: Double)

    private suspend fun build(database: MusicDatabase, hideExplicit: Boolean) = coroutineScope {
        val taste = taste(database)
        val favorites = resolveIds(taste.favorites)
        val day = LocalDate.now().toEpochDay()
        if (_feed.value == null) _feed.value = HomeFeedData()

        val parts = listOf(
            launch { section("newReleases") { newReleases(favorites, taste).let { r -> _feed.update { it?.copy(newReleases = r) } } } },
            launch { section("similar") { similar(favorites, taste, hideExplicit, day).let { r -> _feed.update { it?.copy(similar = r) } } } },
            launch { section("genres") { genres(favorites).let { r -> _feed.update { it?.copy(genres = r) } } } },
            launch { section("forgotten") { forgotten(taste, hideExplicit, day).let { r -> _feed.update { it?.copy(forgotten = r) } } } },
        )
        parts.forEach { it.join() }
        _feed.update { it?.copy(builtAt = System.currentTimeMillis()) }
        _feed.value?.let { save(it) }
    }

    private suspend fun section(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.w(e, "HomeFeed: '$name' failed")
        }
    }

    // ── Taste ────────────────────────────────────────────────────────────────────────────────────

    private class Taste(
        val favorites: List<Favorite>,
        /** "artist|title" of everything liked or played: not suggested as new. */
        val known: Set<String>,
        /** "artist|title" played in the last [FORGOTTEN_AFTER_DAYS] days. */
        val recentlyHeard: Set<String>,
        /** "artist|album" of albums played from. */
        val playedAlbums: Set<String>,
        val likedTracks: List<SpotifyTrack>,
        val likedAt: Map<String, Long>,
    )

    private suspend fun taste(database: MusicDatabase): Taste {
        val score = HashMap<String, Double>()
        val names = HashMap<String, String>()
        val ids = HashMap<String, String>()
        val known = HashSet<String>()
        val recent = HashSet<String>()
        val albums = HashSet<String>()

        fun add(name: String, id: String?, weight: Double) {
            val n = name.trim()
            if (n.isBlank()) return
            val key = n.lowercase()
            score.merge(key, weight, Double::plus)
            names.putIfAbsent(key, n)
            catalogIdOf(id)?.let { ids.putIfAbsent(key, it) }
        }

        val now = LocalDateTime.now()
        database.events().first().take(4000).forEach { e ->
            val song = e.song
            val artist = song.artists.firstOrNull() ?: return@forEach
            val ageDays = Duration.between(e.event.timestamp, now).toHours() / 24.0
            val key = trackKey(artist.name, song.song.title)
            known += key
            if (ageDays <= FORGOTTEN_AFTER_DAYS) recent += key
            song.song.albumName?.takeIf { it.isNotBlank() }?.let { albums += albumKey(artist.name, it) }
            if (ageDays > 240 || !Listening.counts(e.event.playTime, song.song.duration * 1000L)) return@forEach
            val chosen = if (RadioPlays.isRadio(song.id, e.event.timestamp.toLocalDate())) 0.33 else 1.0
            add(artist.name, artist.id, chosen * exp(-ageDays / 75.0))
        }

        // Likes say what is loved, however long ago it was liked.
        val source = Catalog.source
        val liked = source?.let { LikedSongsCache.get(it.name) }?.tracks.orEmpty()
        val likedAt = source?.let { LikedSongsCache.addedAt(it.name) }.orEmpty()
        liked.forEach { t ->
            val artist = t.artists.firstOrNull() ?: return@forEach
            known += trackKey(artist.name, t.name)
            add(artist.name, artist.id, 0.6)
        }
        database.likedSongs(SongSortType.CREATE_DATE, descending = true).first().forEach { s ->
            val artist = s.artists.firstOrNull() ?: return@forEach
            known += trackKey(artist.name, s.song.title)
            add(artist.name, artist.id, 0.6)
        }
        database.artistsBookmarked(ArtistSortType.CREATE_DATE, descending = true).first().forEach { a ->
            add(a.artist.name, a.artist.id, 3.0)
        }

        val favorites = score.entries.sortedByDescending { it.value }.take(40).map { (key, s) ->
            Favorite(names.getValue(key), ids[key], s)
        }
        return Taste(favorites, known, recent, albums, liked, likedAt)
    }

    /** Catalog ids for the strongest favourites that only have a name (YouTube or local tracks). */
    private suspend fun resolveIds(favorites: List<Favorite>): List<Favorite> = coroutineScope {
        val gate = Semaphore(4)
        favorites.mapIndexed { i, f ->
            async {
                if (f.id != null || i >= 16) return@async f
                gate.withPermit {
                    val found = runCatching {
                        Catalog.search(f.name, types = listOf("artist"), limit = 3).getOrNull()?.artists?.items
                            ?.firstOrNull { it.name.equals(f.name, ignoreCase = true) && it.id.isNotBlank() }?.id
                    }.getOrNull()
                    f.copy(id = found)
                }
            }
        }.awaitAll()
    }

    // ── Rows ─────────────────────────────────────────────────────────────────────────────────────

    private suspend fun newReleases(favorites: List<Favorite>, taste: Taste): List<SpotifyAlbum> = coroutineScope {
        val since = LocalDate.now().minusDays(NEW_RELEASE_DAYS).toString()
        val gate = Semaphore(4)
        favorites.filter { it.id != null }.take(18).map { f ->
            async {
                gate.withPermit {
                    Catalog.latestReleases(f.id!!).getOrNull().orEmpty()
                        // Only releases dated to the day: a bare year can't say "new".
                        .filter { (it.releaseDate?.length ?: 0) == 10 && it.releaseDate!! >= since }
                        .filter { albumKey(f.name, it.name) !in taste.playedAlbums }
                        .map { album ->
                            album.copy(
                                artists = album.artists.ifEmpty { listOf(SpotifySimpleArtist(id = f.id, name = f.name)) },
                                tracks = null,
                            )
                        }
                }
            }
        }.awaitAll().flatten()
            .distinctBy { it.id }
            .sortedByDescending { it.releaseDate }
            .take(20)
    }

    private suspend fun similar(favorites: List<Favorite>, taste: Taste, hideExplicit: Boolean, day: Long): List<SimilarRow> = coroutineScope {
        val favoriteKeys = favorites.take(8).mapTo(HashSet()) { it.name.lowercase() }
        favorites.filter { it.id != null }.take(3).mapIndexed { index, seed ->
            async {
                val random = Random(day * 31 + index)
                val image = async {
                    Catalog.artist(seed.id!!).getOrNull()?.images
                        ?.let { imgs -> imgs.firstOrNull { (it.width ?: 0) in 200..400 } ?: imgs.firstOrNull() }?.url
                }
                val related = Catalog.relatedArtists(seed.id!!).getOrNull().orEmpty()
                    .filter { it.id.isNotBlank() && it.name.lowercase() !in favoriteKeys }
                    .take(12)
                    .shuffled(random)
                    .take(7)
                val lists = related.map { artist ->
                    async {
                        Catalog.artistTopTracks(artist.id).getOrNull()?.tracks.orEmpty()
                            .filter { t ->
                                t.id.isNotEmpty() && !t.isLocal && (!hideExplicit || !t.explicit) &&
                                    trackKey(t.artists.firstOrNull()?.name.orEmpty(), t.name) !in taste.known
                            }
                            .take(5)
                            .shuffled(random)
                            .take(2)
                    }
                }.awaitAll()
                SimilarRow(seed.name, seed.id, image.await(), interleave(lists).take(14))
            }
        }.awaitAll().filter { it.tracks.size >= 4 }
    }

    private suspend fun genres(favorites: List<Favorite>): List<String> {
        val top = favorites.take(30)
        runCatching { Genres.ensure(top.map { it.name }) }
        val score = HashMap<String, Double>()
        top.forEach { f ->
            val genres = Genres.cached(f.name).map(Genres::family).distinct().take(3)
            val parts = listOf(1.0, 0.4, 0.2)
            genres.forEachIndexed { i, g -> score.merge(g, f.score * parts[i], Double::plus) }
        }
        return score.entries.sortedByDescending { it.value }.take(10).map { it.key }
    }

    private fun forgotten(taste: Taste, hideExplicit: Boolean, day: Long): List<SpotifyTrack> {
        val longAgo = System.currentTimeMillis() - FORGOTTEN_AFTER_DAYS * 24 * 60 * 60_000L
        return taste.likedTracks
            .filter { t ->
                t.id.isNotEmpty() && (!hideExplicit || !t.explicit) &&
                    (taste.likedAt[t.id] ?: 0L) in 1 until longAgo &&
                    trackKey(t.artists.firstOrNull()?.name.orEmpty(), t.name) !in taste.recentlyHeard
            }
            .shuffled(Random(day))
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
