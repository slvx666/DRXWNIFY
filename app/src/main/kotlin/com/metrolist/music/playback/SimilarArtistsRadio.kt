/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.catalog.Catalog
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import timber.log.Timber

/**
 * "What plays next" for a track: songs by the artists the catalog (Spotify / Yandex Music) lists as
 * similar to the track's artist, with a few songs of that artist mixed in. Never falls back to
 * unrelated personal favourites, so a metalcore single isn't followed by synthwave.
 */
object SimilarArtistsRadio {
    private const val RELATED_ARTISTS = 12
    private const val TRACKS_PER_RELATED_ARTIST = 3
    private const val SEED_ARTIST_TRACKS = 6

    /** One song of the seed artist after every this many songs of similar artists. */
    private const val SEED_EVERY = 4

    suspend fun build(seed: SpotifyTrack, limit: Int = 50): List<SpotifyTrack> = coroutineScope {
        val artistId = seed.artists.firstNotNullOfOrNull { it.id?.takeIf { id -> id.isNotBlank() } }
            ?: return@coroutineScope emptyList()

        val seedTopDeferred = async { Catalog.artistTopTracks(artistId).getOrNull()?.tracks.orEmpty() }
        val related = Catalog.relatedArtists(artistId).getOrNull().orEmpty()
            .filter { it.id.isNotBlank() && it.id != artistId }
            .take(RELATED_ARTISTS)
        val relatedTops = related.map { artist ->
            async { Catalog.artistTopTracks(artist.id).getOrNull()?.tracks.orEmpty() }
        }.awaitAll()
        val seedTop = seedTopDeferred.await()

        val seen = HashSet<String>()
        fun keyOf(track: SpotifyTrack) =
            (track.artists.firstOrNull()?.name.orEmpty() + "|" + track.name).lowercase().replace(Regex("\\s*[(\\[].*"), "")
        fun accept(track: SpotifyTrack): Boolean =
            track.id.isNotBlank() && !track.isLocal && track.id != seed.id &&
                keyOf(track) != keyOf(seed) && seen.add(track.id) && seen.add(keyOf(track))

        // Each similar artist contributes a few of its popular songs, taken round-robin so the queue
        // walks across artists instead of playing one of them for ten songs in a row.
        val perArtist = relatedTops.map { tracks -> tracks.take(8).shuffled().take(TRACKS_PER_RELATED_ARTIST) }
        val relatedOrder = buildList {
            for (round in 0 until TRACKS_PER_RELATED_ARTIST) {
                for (picks in perArtist) picks.getOrNull(round)?.let { add(it) }
            }
        }
        val seedPicks = seedTop.shuffled().take(SEED_ARTIST_TRACKS).toMutableList()

        val result = mutableListOf<SpotifyTrack>()
        var sinceSeed = 0
        for (track in relatedOrder) {
            if (result.size >= limit) break
            if (sinceSeed >= SEED_EVERY && seedPicks.isNotEmpty()) {
                val seedTrack = seedPicks.removeAt(0)
                if (accept(seedTrack)) result += seedTrack
                sinceSeed = 0
            }
            if (accept(track)) {
                result += track
                sinceSeed++
            }
        }
        // Few or no similar artists known: the artist's own songs are still better than nothing.
        for (track in seedPicks) {
            if (result.size >= limit) break
            if (accept(track)) result += track
        }
        Timber.d("SimilarArtistsRadio: '${seed.name}' -> ${result.size} tracks from ${related.size} similar artists")
        result
    }
}
