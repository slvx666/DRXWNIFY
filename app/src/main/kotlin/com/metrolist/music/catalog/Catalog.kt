/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.catalog

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.constants.EnableSpotifyKey
import com.metrolist.music.constants.MetadataSource
import com.metrolist.music.constants.PrimaryMetadataSourceKey
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.constants.YandexAccessTokenKey
import com.metrolist.music.constants.YandexUidKey
import com.metrolist.music.utils.SpotifyTokenManager
import com.metrolist.spotify.ArtistTopTracksResponse
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyLibraryEntry
import com.metrolist.spotify.models.SpotifyPaging
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyPlaylistTrack
import com.metrolist.spotify.models.SpotifySavedTrack
import com.metrolist.spotify.models.SpotifySearchResult
import com.metrolist.spotify.models.SpotifyTrack
import com.metrolist.yandex.YandexIds
import com.metrolist.yandex.YandexMusic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The music catalog = metadata + the user's library, owned by ONE account at a time: Spotify or
 * Yandex Music. The two are never mixed. With both accounts signed in, the user picks the data
 * source in Settings → Music sources (Spotify by default).
 *
 *  - Collection calls (liked songs, library, search) use the selected source only.
 *  - Entity calls (album, playlist, artist, track, likes) are routed by id — Yandex entities carry a
 *    `ym_` id ([YandexIds]), everything else is Spotify — so an item that is already on screen keeps
 *    working even right after the source is switched.
 *
 * Audio is never taken from the catalog: see the resolver package.
 */
object Catalog {
    data class State(
        val spotifyConnected: Boolean = false,
        val yandexConnected: Boolean = false,
        val preferred: MetadataSource = MetadataSource.SPOTIFY,
    ) {
        /** The source feeding library/search right now, or null when no account is connected. */
        val source: MetadataSource?
            get() = when {
                spotifyConnected && yandexConnected -> preferred
                spotifyConnected -> MetadataSource.SPOTIFY
                yandexConnected -> MetadataSource.YANDEX
                else -> null
            }

        val isActive: Boolean get() = source != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Localized title of the Liked Songs pseudo-playlist for Yandex Music. */
    @Volatile
    var likedSongsName: String = "Liked Songs"

    fun init(dataStore: DataStore<Preferences>, scope: CoroutineScope, likedSongsTitle: String) {
        likedSongsName = likedSongsTitle
        scope.launch(Dispatchers.IO) {
            dataStore.data
                .map { prefs ->
                    val yandexToken = prefs[YandexAccessTokenKey].orEmpty()
                    YandexMusic.accessToken = yandexToken.ifBlank { null }
                    YandexMusic.uid = prefs[YandexUidKey]?.ifBlank { null }
                    State(
                        spotifyConnected = (prefs[EnableSpotifyKey] ?: false) &&
                            prefs[SpotifyAccessTokenKey].orEmpty().isNotEmpty(),
                        yandexConnected = yandexToken.isNotEmpty(),
                        preferred = prefs[PrimaryMetadataSourceKey]
                            ?.let { v -> MetadataSource.entries.firstOrNull { it.name == v } }
                            ?: MetadataSource.SPOTIFY,
                    )
                }
                .distinctUntilChanged()
                .collect { s ->
                    if (s.source != _state.value.source) invalidateCaches()
                    _state.value = s
                }
        }
    }

    val source: MetadataSource? get() = _state.value.source

    val isActive: Boolean get() = _state.value.isActive

    val isYandexSource: Boolean get() = source == MetadataSource.YANDEX

    fun isYandexId(id: String?): Boolean = YandexIds.isYandex(id)

    /** True when the account owning [id] is signed in, i.e. writes (like, save, follow) can succeed. */
    fun canWrite(id: String?): Boolean =
        if (isYandexId(id)) YandexMusic.isAuthenticated() else Spotify.isAuthenticated()

    /** Makes sure the selected source has a usable session. */
    suspend fun ensureAuthenticated(): Boolean = when (source) {
        MetadataSource.SPOTIFY -> SpotifyTokenManager.ensureAuthenticated()
        MetadataSource.YANDEX -> YandexMusic.isAuthenticated()
        null -> false
    }

    private fun noAccount(): Nothing = throw IllegalStateException("No music account connected")

    // ── Entities (routed by id) ──────────────────────────────────────────────────────────────────

    suspend fun album(id: String): Result<SpotifyAlbum> =
        if (isYandexId(id)) YandexMusic.album(id) else Spotify.album(id)

    suspend fun artist(id: String): Result<SpotifyArtist> =
        if (isYandexId(id)) YandexMusic.artist(id) else Spotify.artist(id)

    suspend fun artistTopTracks(id: String): Result<ArtistTopTracksResponse> =
        if (isYandexId(id)) YandexMusic.artistTopTracks(id) else Spotify.artistTopTracks(id)

    /** All releases of an artist (albums, singles, EPs, compilations) from the catalog that owns it. */
    suspend fun artistReleases(id: String): Result<List<SpotifyAlbum>> =
        if (isYandexId(id)) {
            YandexMusic.artistAlbums(id)
        } else {
            runCatching {
                // The GQL overview only carries the most recent/popular releases; the REST listing is
                // complete but rate-limited. Union both so EPs and older albums are never missing.
                kotlinx.coroutines.coroutineScope {
                    val overview = async {
                        Spotify.artistDiscography(id).getOrNull()?.let { it.albums + it.singles + it.compilations }.orEmpty()
                    }
                    val rest = async {
                        val all = mutableListOf<SpotifyAlbum>()
                        var offset = 0
                        while (offset < 300) {
                            val page = Spotify.artistAlbums(id, limit = 50, offset = offset).getOrNull() ?: break
                            all += page
                            if (page.size < 50) break
                            offset += 50
                        }
                        all
                    }
                    val merged = (overview.await() + rest.await())
                        .filter { it.id.isNotBlank() }
                        .groupBy { it.id }
                        .map { (_, versions) ->
                            // Prefer the entry that carries the most information.
                            versions.maxBy { (if (it.releaseDate != null) 2 else 0) + (if (it.images.isNotEmpty()) 1 else 0) }
                        }
                    if (merged.isEmpty()) throw IllegalStateException("No releases for artist $id")
                    merged.sortedByDescending { it.releaseDate.orEmpty() }
                }
            }
        }

    suspend fun getTrack(id: String): Result<SpotifyTrack> =
        if (isYandexId(id)) YandexMusic.getTrack(id) else Spotify.getTrack(id)

    suspend fun playlist(id: String): Result<SpotifyPlaylist> =
        if (isYandexId(id)) YandexMusic.playlist(id) else Spotify.playlist(id)

    suspend fun playlistTracks(id: String, limit: Int, offset: Int): Result<SpotifyPaging<SpotifyPlaylistTrack>> =
        if (isYandexId(id)) YandexMusic.playlistTracks(id, limit, offset) else Spotify.playlistTracks(id, limit, offset)

    suspend fun saveTrack(id: String): Result<Unit> =
        if (isYandexId(id)) YandexMusic.setTrackLiked(id, true) else Spotify.saveTrack(id)

    suspend fun removeTrack(id: String): Result<Unit> =
        if (isYandexId(id)) YandexMusic.setTrackLiked(id, false) else Spotify.removeTrack(id)

    suspend fun isAlbumSaved(id: String): Result<Boolean> =
        if (isYandexId(id)) YandexMusic.isAlbumLiked(id) else Spotify.isAlbumSaved(id)

    suspend fun saveAlbum(id: String): Result<Unit> =
        if (isYandexId(id)) YandexMusic.setAlbumLiked(id, true) else Spotify.saveAlbum(id)

    suspend fun removeAlbum(id: String): Result<Unit> =
        if (isYandexId(id)) YandexMusic.setAlbumLiked(id, false) else Spotify.removeAlbum(id)

    suspend fun isFollowingArtist(id: String): Result<Boolean> =
        if (isYandexId(id)) YandexMusic.isArtistLiked(id) else Spotify.isFollowingArtist(id)

    suspend fun setFollowingArtist(id: String, follow: Boolean): Result<Unit> =
        if (isYandexId(id)) YandexMusic.setArtistLiked(id, follow) else Spotify.setFollowingArtist(id, follow)

    /** Yandex playlists are read-only in Meld (no reorder/rename/remove through this API). */
    fun isEditablePlaylist(id: String): Boolean = !isYandexId(id)

    // ── Collections (selected source only) ───────────────────────────────────────────────────────

    private const val LIBRARY_TTL_MS = 2 * 60 * 1000L
    private val libraryMutex = Mutex()
    private val yandexLibrary = HashMap<String, Pair<Long, List<SpotifyLibraryEntry>>>()

    fun invalidateCaches() {
        yandexLibrary.clear()
    }

    suspend fun likedSongs(limit: Int, offset: Int): Result<SpotifyPaging<SpotifySavedTrack>> = when (source) {
        MetadataSource.SPOTIFY -> Spotify.likedSongs(limit, offset)
        MetadataSource.YANDEX -> YandexMusic.likedSongs(limit, offset)
        null -> runCatching { noAccount() }
    }

    /** One page of the library for [filter] ("Playlists" / "Albums" / "Artists" / null = all). */
    suspend fun myLibrary(filter: String?, limit: Int, offset: Int): Result<SpotifyPaging<SpotifyLibraryEntry>> =
        when (source) {
            MetadataSource.SPOTIFY -> Spotify.myLibrary(filter, limit, offset)
            MetadataSource.YANDEX -> runCatching {
                val key = filter ?: "*"
                val all = libraryMutex.withLock {
                    yandexLibrary[key]?.takeIf { offset > 0 || System.currentTimeMillis() - it.first < LIBRARY_TTL_MS }?.second
                        ?: YandexMusic.library(likedSongsName).getOrThrow()
                            .filter { it.matchesFilter(filter) }
                            .also { yandexLibrary[key] = System.currentTimeMillis() to it }
                }
                SpotifyPaging(items = all.drop(offset).take(limit), total = all.size, limit = limit, offset = offset)
            }
            null -> runCatching { noAccount() }
        }

    private fun SpotifyLibraryEntry.matchesFilter(filter: String?): Boolean = when (filter) {
        "Playlists" -> kind == SpotifyLibraryEntry.Kind.PLAYLIST ||
            kind == SpotifyLibraryEntry.Kind.LIKED_SONGS || kind == SpotifyLibraryEntry.Kind.FOLDER
        "Albums" -> kind == SpotifyLibraryEntry.Kind.ALBUM
        "Artists" -> kind == SpotifyLibraryEntry.Kind.ARTIST
        else -> true
    }

    /** Search in the selected source. [types] are Spotify type names. */
    suspend fun search(query: String, types: List<String>, limit: Int, offset: Int = 0): Result<SpotifySearchResult> =
        when (source) {
            MetadataSource.SPOTIFY -> Spotify.search(query, types, limit, offset)
            MetadataSource.YANDEX -> YandexMusic.search(query, types, limit, offset)
            null -> runCatching { noAccount() }
        }
}
