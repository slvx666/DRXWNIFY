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
import com.metrolist.music.catalog.Catalog
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SpotifyAlbumViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    savedStateHandle: SavedStateHandle,
    val database: MusicDatabase,
) : ViewModel() {
    val albumId: String = savedStateHandle.get<String>("albumId")
        ?: throw IllegalArgumentException("albumId is required")

    init {
        com.metrolist.music.playback.LibraryRecents.album(albumId)
    }
    val mapper = SpotifyYouTubeMapper(database)

    private val _album = MutableStateFlow<SpotifyAlbum?>(null)
    val album = _album.asStateFlow()

    private val _tracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        loadAlbum()
    }

    private fun loadAlbum() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null

            Catalog.album(albumId).onSuccess { own ->
                // A Yandex album (from the Yandex library) is shown from Spotify when Spotify has it:
                // its catalog is fuller. Saving it still goes to the account, by [albumId].
                val album = com.metrolist.music.catalog.CrossCatalog
                    .spotifyAlbumFor(albumId, own.name, own.artists.firstOrNull()?.name)
                    ?.let { Catalog.album(it).getOrNull() }
                    ?.takeIf { !it.tracks?.items.isNullOrEmpty() }
                    ?: own
                _album.value = album
                val loaded = album.tracks?.items?.filter { !it.isLocal } ?: emptyList()
                _tracks.value = loaded
                // The whole album from VK in one go, when VK has it (see AlbumSources).
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    runCatching {
                        com.metrolist.music.resolver.AlbumSources.prefetchVkAlbum(
                            albumId, album.name, album.artists.firstOrNull()?.name.orEmpty(), loaded,
                        )
                    }
                }
                // Diagnostic: verify the returned album content matches the requested id. If the track
                // names look unrelated to the album (e.g. ".m0lly /bin"), the mismatch is visible here.
                val trackNames = loaded.take(15).joinToString(separator = " | ") { it.name }
                val albumArtists = album.artists.joinToString { it.name }
                // Always-on (release-visible) diagnostic: prints the exact albumId, name, artists and
                // track names so a wrong album (e.g. ".m0lly /bin" showing unrelated tracks) can be
                // compared against Spotify web. android.util.Log is used because Timber has no tree
                // planted in release builds.
                android.util.Log.i(
                    "SpotifyAlbumDiag",
                    "albumId=$albumId name='${album.name}' artists='$albumArtists' tracks(${loaded.size})=$trackNames",
                )
                _isLoading.value = false
            }.onFailure { e ->
                Timber.e(e, "Failed to load Spotify album: $albumId")
                _error.value = e.message ?: "Failed to load album"
                _isLoading.value = false
            }
        }
    }

    fun retry() = loadAlbum()

    /** Whether the linked account has this album saved; null while unknown. */
    val isSaved = MutableStateFlow<Boolean?>(null)

    /** The album can be liked only when the account that owns it is signed in. */
    val canSave: Boolean get() = Catalog.canWrite(albumId)

    /** What the account is known to hold (the last state read from it or written to it). */
    private var syncedSaved: Boolean? = null
    private var userTouched = false
    private var syncJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (!canSave) return@launch
            Catalog.isAlbumSaved(albumId)
                .onSuccess {
                    syncedSaved = it
                    // A tap made while this was loading wins over the loaded state.
                    if (!userTouched) isSaved.value = it
                }
                .onFailure {
                    Timber.w(it, "Album saved-state check failed for $albumId")
                    if (!userTouched) isSaved.value = false
                }
        }
    }

    /**
     * Two-way like. The heart flips on the tap itself, even before the account's state has loaded;
     * the account gets only the state the taps settle on (several quick taps send one request, and
     * none if they cancel out), and the heart goes back if that request fails.
     */
    fun toggleSaved(onError: (String) -> Unit) {
        userTouched = true
        val target = !(isSaved.value ?: false)
        isSaved.value = target
        syncJob?.cancel()
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(LIKE_SETTLE_MS)
            if (target == syncedSaved) return@launch
            Timber.i("Album %s: %s in the account", albumId, if (target) "saving" else "removing")
            val result = if (target) Catalog.saveAlbum(albumId) else Catalog.removeAlbum(albumId)
            result
                .onSuccess { syncedSaved = target }
                .onFailure { e ->
                    Timber.w(e, "Album %s: like sync failed", albumId)
                    if (isSaved.value == target) isSaved.value = syncedSaved ?: !target
                    val message = e.message ?: "sync failed"
                    kotlinx.coroutines.withContext(Dispatchers.Main) { onError(message) }
                }
        }
    }

    private companion object {
        const val LIKE_SETTLE_MS = 600L
    }
}
