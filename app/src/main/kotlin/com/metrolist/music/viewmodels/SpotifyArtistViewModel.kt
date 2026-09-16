/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Loads a Spotify artist by its Spotify id — profile, top tracks and releases. Deterministic (keyed
 * on the artist's actual Spotify id), unlike the old "search YouTube by name, open first result"
 * navigation that opened the wrong same-named artist.
 */
@HiltViewModel
class SpotifyArtistViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    savedStateHandle: SavedStateHandle,
    val database: MusicDatabase,
) : ViewModel() {
    val artistId: String = savedStateHandle.get<String>("artistId")
        ?: throw IllegalArgumentException("artistId is required")
    val mapper = SpotifyYouTubeMapper(database)

    private val _artist = MutableStateFlow<SpotifyArtist?>(null)
    val artist = _artist.asStateFlow()

    private val _topTracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val topTracks = _topTracks.asStateFlow()

    /** Real albums (album_type == "album"). */
    private val _albums = MutableStateFlow<List<SpotifyAlbum>>(emptyList())
    val albums = _albums.asStateFlow()

    /** Singles, EPs and compilations, kept separate so "Albums" means albums. */
    private val _singles = MutableStateFlow<List<SpotifyAlbum>>(emptyList())
    val singles = _singles.asStateFlow()

    /** Whether the linked Spotify account follows this artist (null = unknown / not linked). */
    private val _isFollowing = MutableStateFlow<Boolean?>(null)
    val isFollowing = _isFollowing.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null

            // Fetch the three pieces in parallel; the profile is the only hard requirement.
            val artistDeferred = async { Spotify.artist(artistId) }
            val topTracksDeferred = async { Spotify.artistTopTracks(artistId) }
            // Discography comes from the same GQL payload as the top tracks, which is reliable — the
            // REST /artists/{id}/albums endpoint was silently returning nothing for some artists,
            // leaving this screen showing only the top-10 tracks with no albums/singles.
            val discoDeferred = async { Spotify.artistDiscography(artistId) }

            artistDeferred.await()
                .onSuccess { _artist.value = it }
                .onFailure {
                    Timber.e(it, "SpotifyArtistViewModel: failed to load artist $artistId")
                    _error.value = it.message ?: "Failed to load artist"
                }

            topTracksDeferred.await().onSuccess { _topTracks.value = it.tracks }

            discoDeferred.await().onSuccess { disco ->
                applyDiscography(disco.albums, disco.singles + disco.compilations)
            }.onFailure { e ->
                Timber.w(e, "artistDiscography failed for $artistId, falling back to REST albums")
                // Fallback: the old REST path, split the same way.
                Spotify.artistAlbums(artistId).onSuccess { all ->
                    val (albums, other) = all.partition { it.albumType.equals("album", ignoreCase = true) }
                    applyDiscography(albums, other)
                }
            }

            // Reflect the real Spotify follow state so the button doesn't say "Follow" for an artist
            // the user already follows.
            if (com.metrolist.music.catalog.Catalog.canWrite(artistId)) {
                com.metrolist.music.catalog.Catalog.isFollowingArtist(artistId).onSuccess { _isFollowing.value = it }
            }

            _isLoading.value = false
        }
    }

    /**
     * Splits releases into Albums vs Singles & EPs. A real album is album_type album with more than
     * one track; 1-track "albums" are demoted to singles. total_tracks == 0 (unknown) stays an album
     * so real albums are never dropped. Newest first within each group.
     */
    private fun applyDiscography(albumGroup: List<SpotifyAlbum>, otherGroup: List<SpotifyAlbum>) {
        val (realAlbums, oneTrackAlbums) = albumGroup.partition { it.totalTracks != 1 }
        _albums.value = realAlbums.sortedByDescending { it.releaseDate ?: "" }
        _singles.value = (otherGroup + oneTrackAlbums)
            .distinctBy { it.id }
            .sortedByDescending { it.releaseDate ?: "" }
    }

    /** Toggles follow on the linked Spotify account, updating state optimistically on success. */
    fun toggleFollow() {
        val target = !(_isFollowing.value ?: false)
        viewModelScope.launch(Dispatchers.IO) {
            com.metrolist.music.catalog.Catalog.setFollowingArtist(artistId, target)
                .onSuccess { _isFollowing.value = target }
                .onFailure { Timber.w(it, "toggleFollow failed for $artistId") }
        }
    }
}
