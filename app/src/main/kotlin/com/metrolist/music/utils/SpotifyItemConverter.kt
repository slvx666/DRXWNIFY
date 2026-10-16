/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyTrack

/**
 * Prefix used to distinguish Spotify IDs from YouTube IDs in YTItem wrappers.
 * When an item's ID starts with this prefix, it originated from Spotify.
 */
const val SPOTIFY_ID_PREFIX = "spotify:"

fun String.isSpotifyId(): Boolean = startsWith(SPOTIFY_ID_PREFIX)

fun String.stripSpotifyPrefix(): String = removePrefix(SPOTIFY_ID_PREFIX)

fun SpotifyTrack.toSongItem(): SongItem = SongItem(
    id = "$SPOTIFY_ID_PREFIX$id",
    title = name,
    artists = artists.map { Artist(name = it.name, id = it.id?.let { i -> "$SPOTIFY_ID_PREFIX$i" }) },
    album = album?.let { Album(name = it.name, id = "$SPOTIFY_ID_PREFIX${it.id}") },
    duration = durationMs / 1000,
    thumbnail = SpotifyMapper.getTrackThumbnailMedium(this) ?: "",
    explicit = explicit,
)

fun SpotifyAlbum.toAlbumItem(): AlbumItem = AlbumItem(
    browseId = "$SPOTIFY_ID_PREFIX$id",
    playlistId = "$SPOTIFY_ID_PREFIX$id",
    title = name,
    artists = artists.map { Artist(name = it.name, id = it.id?.let { i -> "$SPOTIFY_ID_PREFIX$i" }) },
    year = releaseDate?.take(4)?.toIntOrNull(),
    thumbnail = images.firstOrNull { it.width in 200..400 }?.url
        ?: images.firstOrNull()?.url ?: "",
    explicit = false,
)

fun SpotifyArtist.toArtistItem(): ArtistItem = ArtistItem(
    id = "$SPOTIFY_ID_PREFIX$id",
    title = name,
    thumbnail = images.firstOrNull { it.width in 200..400 }?.url
        ?: images.firstOrNull()?.url,
    shuffleEndpoint = null,
    radioEndpoint = null,
)

fun SpotifyPlaylist.toPlaylistItem(): PlaylistItem = PlaylistItem(
    id = "$SPOTIFY_ID_PREFIX$id",
    title = name,
    author = owner?.let { Artist(name = it.displayName ?: "", id = null) },
    // Spotify's mixes on the home page come without a count (0): none rather than "Spotify • 0".
    songCountText = tracks?.total?.takeIf { it > 0 }?.toString(),
    thumbnail = SpotifyMapper.getPlaylistThumbnail(this),
    playEndpoint = null,
    shuffleEndpoint = null,
    radioEndpoint = null,
)

/**
 * Extracts the original Spotify track from its SpotifyTrack data
 * for use with SpotifyQueue when a converted YTItem is clicked.
 */
fun SongItem.toSpotifyTrackStub(): SpotifyTrack? {
    if (!id.isSpotifyId()) return null
    return SpotifyTrack(
        id = id.stripSpotifyPrefix(),
        name = title,
        artists = artists.map {
            com.metrolist.spotify.models.SpotifySimpleArtist(
                id = it.id?.stripSpotifyPrefix(),
                name = it.name,
            )
        },
        // The list row's album and cover go along: without them a track played from the stub (the
        // full lookup is often rate-limited) had no cover anywhere in the player.
        album = album?.let { a ->
            com.metrolist.spotify.models.SpotifySimpleAlbum(
                id = a.id.stripSpotifyPrefix(),
                name = a.name,
                images = listOfNotNull(
                    thumbnail.takeIf { it.isNotBlank() }?.let { com.metrolist.spotify.models.SpotifyImage(url = it, width = 300, height = 300) },
                    thumbnail.toLargeSpotifyCover()?.let { com.metrolist.spotify.models.SpotifyImage(url = it, width = 640, height = 640) },
                ),
            )
        } ?: thumbnail.takeIf { it.isNotBlank() }?.let { url ->
            com.metrolist.spotify.models.SpotifySimpleAlbum(
                images = listOfNotNull(
                    com.metrolist.spotify.models.SpotifyImage(url = url, width = 300, height = 300),
                    url.toLargeSpotifyCover()?.let { com.metrolist.spotify.models.SpotifyImage(url = it, width = 640, height = 640) },
                ),
            )
        },
        durationMs = (duration ?: 0) * 1000,
        explicit = explicit,
    )
}

/** Spotify's 640 px variant of a 300 px album cover ("…00001e02<hash>" → "…0000b273<hash>"). */
private fun String.toLargeSpotifyCover(): String? =
    takeIf { "/image/ab67616d00001e02" in it }?.replace("ab67616d00001e02", "ab67616d0000b273")
