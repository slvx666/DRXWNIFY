/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.catalog.Catalog
import com.metrolist.music.constants.ArtistSortType
import com.metrolist.music.constants.MetadataSource
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.SPOTIFY_ID_PREFIX
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyHomeFeedItem
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.exp

/**
 * The home page's "For you" row.
 *
 * 1. With Spotify connected, Spotify's own personal mixes come first — Discover Weekly, the Daily
 *    Mixes, Release Radar: they are made from the whole listening history and beat anything that can
 *    be rebuilt here.
 * 2. Otherwise (or to fill up) the row is grown from the listener's taste as the app sees it: what
 *    was played lately (recent plays count most), liked, and followed. Each strong artist brings
 *    tracks of artists close to it.
 *
 * Whatever the source, the result is filtered against that taste: nothing already liked or recently
 * played, at most two tracks per artist, and — for someone who hardly listens to Cyrillic-titled
 * music — no Cyrillic-titled tracks, which is what used to slip Russian songs into the row.
 */
object ForYouRecommender {
    private const val TARGET = 30
    private const val MAX_PER_ARTIST = 2

    /** Below this share of Cyrillic in what was listened to, Cyrillic results are left out. */
    private const val CYRILLIC_MIN_SHARE = 0.15

    private const val ID_KEY = "id:"
    private const val NAME_KEY = "name:"

    private data class Taste(
        /** Artist key (catalog id when known, else lower-case name) → weight. */
        val artists: Map<String, Double>,
        val artistNames: Map<String, String>,
        /** "artist|title" of everything liked or recently played: not recommended again. */
        val knownTracks: Set<String>,
        val cyrillicShare: Double,
    )

    suspend fun build(database: MusicDatabase, hideExplicit: Boolean): List<SpotifyTrack> {
        if (!Catalog.ensureAuthenticated()) return emptyList()
        val taste = taste(database)

        val fromSpotify = runCatching {
            if (Catalog.source == MetadataSource.SPOTIFY) madeForYou() else emptyList()
        }.onFailure { Timber.w(it, "forYou: Spotify mixes unavailable") }.getOrDefault(emptyList())

        val candidates = filter(fromSpotify, taste, hideExplicit).toMutableList()
        if (candidates.size < TARGET) {
            candidates += filter(fromTaste(taste), taste, hideExplicit)
        }
        return diversify(candidates.distinctBy { it.id }).take(TARGET)
    }

    // ── Taste ────────────────────────────────────────────────────────────────────────────────────

    private suspend fun taste(database: MusicDatabase): Taste {
        val weights = HashMap<String, Double>()
        val names = HashMap<String, String>()
        val known = HashSet<String>()
        var cyrillic = 0
        var total = 0

        fun addArtist(id: String?, name: String, weight: Double) {
            if (name.isBlank()) return
            val key = id?.takeIf { it.startsWith(SPOTIFY_ID_PREFIX) }?.let { ID_KEY + it.removePrefix(SPOTIFY_ID_PREFIX) }
                ?: (NAME_KEY + name.trim().lowercase())
            weights[key] = (weights[key] ?: 0.0) + weight
            names.putIfAbsent(key, name)
        }

        fun sample(artist: String, title: String) {
            total++
            if (hasCyrillic(artist) || hasCyrillic(title)) cyrillic++
            known += trackKey(artist, title)
        }

        // Recent plays, the strongest signal; a play two weeks ago counts about half as much.
        val now = LocalDateTime.now()
        database.events().first().take(600).forEach { e ->
            val ageDays = Duration.between(e.event.timestamp, now).toHours() / 24.0
            val decay = exp(-ageDays / 20.0)
            val artists = e.song.artists
            sample(artists.firstOrNull()?.name.orEmpty(), e.song.song.title)
            artists.take(2).forEach { addArtist(it.id, it.name, 3.0 * decay) }
        }

        database.likedSongs(SongSortType.CREATE_DATE, descending = true).first().take(300).forEach { s ->
            sample(s.artists.firstOrNull()?.name.orEmpty(), s.song.title)
            s.artists.take(2).forEach { addArtist(it.id, it.name, 2.0) }
        }

        database.artistsBookmarked(ArtistSortType.CREATE_DATE, descending = true).first().forEach { a ->
            addArtist(a.artist.id, a.artist.name, 3.0)
        }

        if (Catalog.isActive) {
            Catalog.likedSongs(limit = 50, offset = 0).getOrNull()?.items?.forEach { saved ->
                val t = saved.track
                sample(t.artists.firstOrNull()?.name.orEmpty(), t.name)
                t.artists.take(2).forEach { a -> addArtist(a.id?.let { SPOTIFY_ID_PREFIX + it }, a.name, 2.0) }
            }
        }

        return Taste(
            artists = weights,
            artistNames = names,
            knownTracks = known,
            cyrillicShare = if (total == 0) 1.0 else cyrillic.toDouble() / total,
        )
    }

    // ── Sources ──────────────────────────────────────────────────────────────────────────────────

    /** Tracks of Spotify's personal mixes, interleaved so no single mix dominates. */
    private suspend fun madeForYou(): List<SpotifyTrack> = coroutineScope {
        val playlists = Spotify.home(sectionItemsLimit = 20).getOrThrow().sections
            .flatMap { it.items }
            .filterIsInstance<SpotifyHomeFeedItem.Playlist>()
            .filter { p -> p.madeForUsername != null || MIX_NAME.containsMatchIn(p.name) }
            .distinctBy { it.id }
            .sortedBy { p -> MIX_PRIORITY.indexOfFirst { it.containsMatchIn(p.name) }.let { if (it < 0) 99 else it } }
            .take(6)
        val lists = playlists.map { p ->
            async {
                Catalog.playlistTracks(p.id, limit = 50, offset = 0).getOrNull()
                    ?.items?.mapNotNull { it.track }?.filter { it.id.isNotEmpty() && !it.isLocal }
                    .orEmpty()
                    .shuffled()
            }
        }.awaitAll()
        interleave(lists)
    }

    /** Tracks of artists close to the strongest artists of the listener's taste. */
    private suspend fun fromTaste(taste: Taste): List<SpotifyTrack> = coroutineScope {
        val seeds = taste.artists.entries.sortedByDescending { it.value }.take(10)
        if (seeds.isEmpty()) return@coroutineScope emptyList()
        val seedIds = seeds.mapNotNull { (key, _) ->
            if (key.startsWith(ID_KEY)) {
                key.removePrefix(ID_KEY)
            } else {
                Catalog.search(taste.artistNames[key] ?: key.removePrefix(NAME_KEY), types = listOf("artist"), limit = 1)
                    .getOrNull()?.artists?.items?.firstOrNull()?.id?.takeIf { it.isNotEmpty() }
            }
        }.distinct()
        val seedSet = seedIds.toSet()
        // The strongest seeds most of the time, a weaker one now and then for variety.
        val picked = seedIds.take(4) + seedIds.drop(4).shuffled().take(2)
        val lists = picked.map { seed ->
            async {
                val related = Catalog.relatedArtists(seed).getOrNull().orEmpty()
                    .filter { it.id.isNotEmpty() && it.id !in seedSet }
                    .take(6)
                    .shuffled()
                    .take(3)
                related.flatMap { artist ->
                    Catalog.artistTopTracks(artist.id).getOrNull()?.tracks.orEmpty().shuffled().take(2)
                }
            }
        }.awaitAll()
        interleave(lists)
    }

    // ── Filtering ────────────────────────────────────────────────────────────────────────────────

    private fun filter(tracks: List<SpotifyTrack>, taste: Taste, hideExplicit: Boolean): List<SpotifyTrack> =
        tracks.filter { t ->
            val artist = t.artists.firstOrNull()?.name.orEmpty()
            t.id.isNotEmpty() && !t.isLocal &&
                (!hideExplicit || !t.explicit) &&
                trackKey(artist, t.name) !in taste.knownTracks &&
                (taste.cyrillicShare >= CYRILLIC_MIN_SHARE || !(hasCyrillic(t.name) || t.artists.any { hasCyrillic(it.name) }))
        }

    /** At most [MAX_PER_ARTIST] per artist, spread out rather than back to back. */
    private fun diversify(tracks: List<SpotifyTrack>): List<SpotifyTrack> {
        val perArtist = HashMap<String, Int>()
        val kept = tracks.filter { t ->
            val key = t.artists.firstOrNull()?.name?.lowercase().orEmpty()
            val n = perArtist[key] ?: 0
            if (n >= MAX_PER_ARTIST) false else { perArtist[key] = n + 1; true }
        }
        val byArtist = kept.groupBy { it.artists.firstOrNull()?.name?.lowercase().orEmpty() }.values.map { it.toMutableList() }
        return interleave(byArtist)
    }

    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val queues = lists.map { ArrayDeque(it) }
        val out = ArrayList<T>()
        while (queues.any { it.isNotEmpty() }) queues.forEach { q -> q.removeFirstOrNull()?.let(out::add) }
        return out
    }

    private fun trackKey(artist: String, title: String): String =
        artist.lowercase().filter { it.isLetterOrDigit() } + "|" +
            title.lowercase().substringBefore(" (").substringBefore(" - ").filter { it.isLetterOrDigit() }

    private fun hasCyrillic(text: String): Boolean = text.any { it in 'Ѐ'..'ӿ' }

    private val MIX_NAME = Regex(
        "Discover Weekly|Daily Mix|Release Radar|Mix|daylist|Открытия недели|Микс|Радар новинок",
        RegexOption.IGNORE_CASE,
    )
    private val MIX_PRIORITY = listOf(
        Regex("Discover Weekly|Открытия недели", RegexOption.IGNORE_CASE),
        Regex("Daily Mix|Микс дня", RegexOption.IGNORE_CASE),
        Regex("Release Radar|Радар новинок", RegexOption.IGNORE_CASE),
        Regex("Mix|Микс", RegexOption.IGNORE_CASE),
    )
}
