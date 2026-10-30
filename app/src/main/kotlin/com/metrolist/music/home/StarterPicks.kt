/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.home

import android.content.Context
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.constants.MetadataSource
import com.metrolist.music.playback.ForYouRecommender
import com.metrolist.music.reviews.NightReviews
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifySimpleAlbum
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The home page of a listener the app knows next to nothing about yet (see [isNewcomer]): instead of
 * rows built from a history that isn't there, rows built around the albums on drxwnslvt's review
 * site. The squares (the usual "For you" grid, dice included) are tracks from those albums, the genre and "More like" rows are the site's
 * artists and the ones close to them, "Worth a listen today" is the site's best-rated albums. Every
 * refresh picks anew. Looked up in Spotify (the account's, or the public catalog) like the search,
 * and in the linked Yandex Music account's catalog when Spotify doesn't answer.
 */
object StarterPicks {
    data class Row(val title: String, val tracks: List<SpotifyTrack>)

    data class Shown(
        val grid: List<SpotifyTrack>,
        val today: List<SpotifyAlbum>,
        val genreRows: List<Row>,
        val artistRows: List<Row>,
        val artists: List<SpotifyArtist>,
    ) {
        val isEmpty get() = grid.isEmpty() && today.isEmpty() && genreRows.isEmpty() && artistRows.isEmpty() && artists.isEmpty()
    }

    private const val ROWS_EACH = 3
    private const val TRACKS_PER_ROW = 12
    private const val TODAY = 8
    private const val TODAY_MIN_RATING = 9.4
    private const val ARTISTS = 10
    private const val SPOTIFY_PROBE_MS = 8_000L

    /** Fewer site albums than this from Spotify: the Yandex catalog is tried too. */
    private const val MIN_FOUND = 5

    /** Listens in the history (not mere starts) from which the listener's own picks join the squares. */
    private const val BLEND_FROM_PLAYS = 20

    /** Listens in the history after which the squares are the listener's own. */
    private const val GROWN_PLAYS = 75

    /**
     * A newcomer until there is enough to go on: 7 followed artists with 20 liked tracks, or either
     * alone in plenty (20 artists, 40 tracks), or [GROWN_PLAYS] listens in the history.
     */
    fun isNewcomer(plays: Int, liked: Int, followed: Int): Boolean {
        val grown = (followed >= 7 && liked >= 20) || followed >= 20 || liked >= 40 || plays >= GROWN_PLAYS
        return !grown
    }

    /**
     * How much of a newcomer's squares are their own recommendations: none before
     * [BLEND_FROM_PLAYS] listens, then a little more with every listen, nearly all just before
     * [GROWN_PLAYS] — so the site's picks give way gradually, not all at once.
     */
    fun personalShare(plays: Int): Double =
        ((plays - BLEND_FROM_PLAYS).toDouble() / (GROWN_PLAYS - BLEND_FROM_PLAYS)).coerceIn(0.0, 1.0)

    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    @Volatile
    private var siteAsked = false

    // Catalog answers for this run of the app: a refresh picks anew without asking again.
    private val topTracks = ConcurrentHashMap<String, List<SpotifyTrack>>()
    private val related = ConcurrentHashMap<String, List<SpotifyArtist>>()
    private val profiles = ConcurrentHashMap<String, SpotifyArtist>()

    suspend fun build(context: Context, hideExplicit: Boolean): Shown? = coroutineScope {
        // Never waits for the site: without a VPN it may not open at all. It is asked behind the page
        // (once a run); the next refresh picks from what it gave.
        val reviews = NightReviews.offline(context)
        if (!siteAsked) {
            siteAsked = true
            background.launch { NightReviews.refresh(context) }
        }
        // Looked up in Spotify, like the search box does for everyone: a Yandex account is for its
        // library and likes, and its catalog lacks much of the site.
        Catalog.awaitAccounts()
        if (reviews.isEmpty()) return@coroutineScope null
        val gate = Semaphore(6)
        fun SpotifyTrack.ok() = id.isNotBlank() && !isLocal && (!hideExplicit || !explicit)

        /** Every album of the site found in [target] (ids are remembered, albums cached by Catalog). */
        suspend fun albumsIn(target: MetadataSource) = reviews.map { review ->
            async {
                gate.withPermit {
                    NightReviews.findAlbum(context, review, target)?.let { Catalog.album(it).getOrNull() }?.let { review to it }
                }
            }
        }.awaitAll().filterNotNull()

        // Spotify first, like the search. One quick question tells whether it answers at all: where it
        // is blocked without a VPN, every lookup would wait for its timeout and the page never came.
        val spotifyAnswers = withTimeoutOrNull(SPOTIFY_PROBE_MS) {
            Catalog.ensureSearchable(MetadataSource.SPOTIFY) &&
                Catalog.searchIn(MetadataSource.SPOTIFY, reviews.first().artist, listOf("artist"), 1).isSuccess
        } == true
        var albums = if (spotifyAnswers) albumsIn(MetadataSource.SPOTIFY) else emptyList()
        // Without it (or with next to nothing from it), the linked Yandex Music account's catalog.
        if (albums.size < MIN_FOUND && Catalog.ensureSearchable(MetadataSource.YANDEX)) {
            val fromYandex = albumsIn(MetadataSource.YANDEX)
            if (fromYandex.size > albums.size) albums = fromYandex
        }
        timber.log.Timber.i("Starter: %d site albums, Spotify answers %s, %d found", reviews.size, spotifyAnswers, albums.size)
        if (albums.isEmpty()) return@coroutineScope null

        // Album tracks come without their album: the squares need its cover.
        fun SpotifyAlbum.playable() = tracks?.items.orEmpty().filter { it.ok() }
            .map { if (it.album?.images.isNullOrEmpty()) it.copy(album = SpotifySimpleAlbum(id, name, images, releaseDate, albumType, artists)) else it }

        // One track from each album: the first page shows nine squares (the dice among them), more pages follow.
        // As many squares as the listener's own "For you" has, at most two per artist like there:
        // a track from every album first, then second ones until the pages are full.
        val perAlbum = albums.shuffled().map { (_, album) -> album.playable().shuffled() }
        val grid = buildList<SpotifyTrack> {
            val perArtist = HashMap<String, Int>()
            for (round in 0 until 3) {
                for (tracks in perAlbum) {
                    if (size >= ForYouRecommender.TARGET) break
                    val track = tracks.getOrNull(round) ?: continue
                    val artist = track.artists.firstOrNull()?.name.orEmpty().lowercase()
                    if ((perArtist[artist] ?: 0) >= 2 || any { it.id == track.id }) continue
                    perArtist[artist] = (perArtist[artist] ?: 0) + 1
                    add(track)
                }
            }
        }
        val today = albums.filter { it.first.rating > TODAY_MIN_RATING }.shuffled().take(TODAY).map { it.second }

        // The site's artists, by the catalog's id, with the genres the site gives their albums.
        val siteArtists = albums.mapNotNull { (review, album) ->
            album.artists.firstOrNull()?.takeIf { !it.id.isNullOrBlank() }?.let { Triple(it.id!!, it.name, review.mainGenres) }
        }.distinctBy { it.first }
        val siteIds = siteArtists.map { it.first }.toSet()

        suspend fun relatedOf(id: String) = related[id] ?: gate.withPermit {
            Catalog.relatedArtists(id).getOrNull().orEmpty().also { related[id] = it }
        }
        suspend fun topOf(id: String) = topTracks[id] ?: gate.withPermit {
            Catalog.artistTopTracks(id).getOrNull()?.tracks.orEmpty().filter { it.ok() }.also { topTracks[id] = it }
        }

        /** One track from each artist (a different one each time), up to a row. */
        /**
         * Exactly a row of tracks when the artists have that many: one from each artist in turn
         * (a different one each time), going round again while the row isn't full.
         */
        suspend fun rowOf(artistIds: List<String>): List<SpotifyTrack> {
            val pools = artistIds.distinct().shuffled().take(TRACKS_PER_ROW)
                .map { id -> async { topOf(id).take(8).shuffled() } }
                .awaitAll().filter { it.isNotEmpty() }
            val row = LinkedHashMap<String, SpotifyTrack>()
            var round = 0
            while (row.size < TRACKS_PER_ROW && pools.any { it.size > round }) {
                for (pool in pools) {
                    if (row.size >= TRACKS_PER_ROW) break
                    pool.getOrNull(round)?.let { row.putIfAbsent(it.id, it) }
                }
                round++
            }
            return row.values.toList()
        }

        /** The first [ROWS_EACH] of [candidates] that give a full row (or the fullest ones there are). */
        suspend fun <T> rowsFrom(candidates: List<T>, build: suspend (T) -> Row?): List<Row> {
            val rows = candidates.take(ROWS_EACH + 2).map { async { build(it) } }.awaitAll().filterNotNull()
            return rows.sortedByDescending { it.tracks.size >= TRACKS_PER_ROW }.take(ROWS_EACH)
        }

        val artistRows = async {
            rowsFrom(siteArtists.shuffled()) { (id, name, _) ->
                val near = relatedOf(id).map { it.id }.filter { it.isNotBlank() && it !in siteIds }
                rowOf(near).takeIf { it.isNotEmpty() }?.let { Row(name, it) }
            }
        }

        // Genres the site has several albums in: its artists there and the ones close to them.
        val genres = siteArtists.flatMap { it.third }.groupingBy { it }.eachCount()
            .filter { it.value >= 2 }.keys.shuffled()
        val genreRows = async {
            rowsFrom(genres) { genre ->
                val ofGenre = siteArtists.filter { genre in it.third }.map { it.first }.shuffled().take(4)
                val near = ofGenre.flatMap { id -> relatedOf(id).take(6).map { it.id } }.filter { it.isNotBlank() }
                rowOf(ofGenre + near).takeIf { it.isNotEmpty() }?.let { Row(genre.titleCase(), it) }
            }
        }

        // Ten of them: more are asked for, since a profile now and then doesn't come.
        val artists = siteArtists.shuffled().take(ARTISTS + 5).map { (id, _, _) ->
            async { profiles[id] ?: gate.withPermit { Catalog.artist(id).getOrNull()?.also { profiles[id] = it } } }
        }

        Shown(
            grid = grid,
            today = today,
            genreRows = genreRows.await(),
            artistRows = artistRows.await(),
            artists = artists.awaitAll().filterNotNull().take(ARTISTS),
        ).takeIf { !it.isEmpty }
    }

    private fun String.titleCase() = split(' ', '-').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
}
