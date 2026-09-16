/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import com.metrolist.music.catalog.Catalog
import com.metrolist.music.playback.SimilarArtistsRadio
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.models.SpotifySimpleAlbum
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import timber.log.Timber

/**
 * An artist's popular tracks that keep playing instead of stopping after the last one: first
 * 8–15 more songs by the same artist (from their albums and singles), then songs by similar artists.
 */
class ArtistTopTracksQueue(
    private val artistId: String,
    private val topTracks: List<SpotifyTrack>,
    startIndex: Int,
    mapper: SpotifyYouTubeMapper,
) : SpotifyPagedQueue(startIndex, mapper, null) {

    override val logTag: String = "ArtistTopTracksQueue"

    override val providedTracks: List<SpotifyTrack> = topTracks

    override suspend fun fetchPage(offset: Int, limit: Int): PageResult =
        PageResult(tracks = emptyList(), total = 0, rawCount = 0)

    override val continues: Boolean = true

    private var round = 0

    override suspend fun continueWith(alreadyQueued: List<SpotifyTrack>): List<SpotifyTrack> {
        val current = round++
        val seenIds = alreadyQueued.mapTo(HashSet()) { it.id }
        val seenTitles = alreadyQueued.mapTo(HashSet()) { titleKey(it) }
        fun fresh(tracks: List<SpotifyTrack>) =
            tracks.filter { it.id.isNotBlank() && !it.isLocal && it.id !in seenIds && titleKey(it) !in seenTitles }
                .distinctBy { titleKey(it) }

        return when (current) {
            0 -> fresh(moreFromArtist()).shuffled().take((MIN_MORE..MAX_MORE).random()).also {
                Timber.d("$logTag: ${it.size} more tracks by the artist")
            }
            else -> {
                val seed = alreadyQueued.lastOrNull { track -> track.artists.any { it.id == artistId } }
                    ?: topTracks.firstOrNull()
                    ?: return emptyList()
                fresh(runCatching { SimilarArtistsRadio.build(seed) }.getOrDefault(emptyList())).also {
                    Timber.d("$logTag: ${it.size} tracks from similar artists (round $current)")
                }
            }
        }
    }

    /** Songs from the artist's releases, newest first, beyond their popular tracks. */
    private suspend fun moreFromArtist(): List<SpotifyTrack> = coroutineScope {
        val releases = Catalog.artistReleases(artistId).getOrNull().orEmpty()
            .sortedByDescending { it.releaseDate.orEmpty() }
            .take(RELEASES_TO_SCAN)
        releases.map { release ->
            async {
                val album = Catalog.album(release.id).getOrNull() ?: return@async emptyList()
                val albumRef = SpotifySimpleAlbum(
                    id = album.id,
                    name = album.name,
                    images = album.images,
                    releaseDate = album.releaseDate,
                    albumType = album.albumType,
                    artists = album.artists,
                )
                album.tracks?.items.orEmpty()
                    .filter { track -> track.artists.isEmpty() || track.artists.any { it.id == artistId } }
                    .map { track -> if (track.album == null) track.copy(album = albumRef) else track }
            }
        }.awaitAll().flatten()
    }

    private fun titleKey(track: SpotifyTrack) =
        track.name.lowercase().replace(Regex("\\s*[(\\[].*"), "").trim()

    private companion object {
        const val MIN_MORE = 8
        const val MAX_MORE = 15
        const val RELEASES_TO_SCAN = 8
    }
}
