/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.models.filterYoutubeShorts
import com.metrolist.innertube.pages.ArtistPage
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HideYoutubeShortsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.extensions.filterExplicit
import com.metrolist.music.extensions.filterExplicitAlbums
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import timber.log.Timber
import javax.inject.Inject
import com.metrolist.music.extensions.filterVideoSongs as filterVideoSongsLocal

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArtistViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val syncUtils: SyncUtils,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val artistId = savedStateHandle.get<String>("artistId")!!
    private val isPodcastChannel = savedStateHandle.get<Boolean>("isPodcastChannel") ?: false
    var artistPage by mutableStateOf<ArtistPage?>(null)

    // Track API subscription state separately
    private val _apiSubscribed = MutableStateFlow<Boolean?>(null)

    /** Spotify id behind this profile ("spotify:<id>", "SP_<id>" or a raw Spotify id), if any. */
    val spotifyArtistId: String? = com.metrolist.music.utils.ArtistIdentity.spotifyIdOf(artistId)

    // The DB row for this artist. Spotify artists are keyed by spotifyId — the same row the
    // mini-player subscribe button and the player's artist links use — so subscribing here never
    // creates a bare-id "local" artist (which made the profile collapse to just the photo).
    private val artistRowFlow = if (spotifyArtistId != null) {
        database.artistBySpotifyIdFlow(spotifyArtistId)
            .flatMapLatest { entity ->
                if (entity == null) kotlinx.coroutines.flow.flowOf(null) else database.artist(entity.id)
            }
    } else {
        database.artist(artistId)
    }

    val libraryArtist = artistRowFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // Combine API state with local database state - local takes precedence when not logged in
    val isChannelSubscribed = kotlinx.coroutines.flow.combine(
        _apiSubscribed,
        artistRowFlow,
    ) { apiState, localArtist ->
        val locallyBookmarked = localArtist?.artist?.bookmarkedAt != null
        locallyBookmarked || (apiState == true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val librarySongs = context.dataStore.data
        .map { (it[HideExplicitKey] ?: false) to (it[HideVideoSongsKey] ?: false) }
        .distinctUntilChanged()
        .flatMapLatest { (hideExplicit, hideVideoSongs) ->
            artistRowFlow.flatMapLatest { row ->
                database.artistSongsPreview(row?.artist?.id ?: artistId)
            }.map { it.filterExplicit(hideExplicit).filterVideoSongsLocal(hideVideoSongs) }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val libraryAlbums = context.dataStore.data
        .map { it[HideExplicitKey] ?: false }
        .distinctUntilChanged()
        .flatMapLatest { hideExplicit ->
            database.artistAlbumsPreview(artistId).map { it.filterExplicitAlbums(hideExplicit) }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        if (isYouTubeArtistId(artistId)) {
            // YouTube artist: load page directly, reload when settings change
            viewModelScope.launch {
                context.dataStore.data
                    .map {
                        Triple(
                            it[HideExplicitKey] ?: false,
                            it[HideVideoSongsKey] ?: false,
                            it[HideYoutubeShortsKey] ?: false
                        )
                    }
                    .distinctUntilChanged()
                    .collect {
                        fetchArtistPage(artistId)
                    }
            }
        } else {
            // Non-YouTube artist (Spotify/local): resolve a name, then open the native YouTube
            // artist page (the design the user wants everywhere). The name comes from the local DB
            // when the artist is known there, otherwise straight from Spotify by id — so a Spotify
            // artist that was never played locally still opens the real profile instead of hanging
            // on an empty screen.
            viewModelScope.launch {
                val dbName = kotlinx.coroutines.withTimeoutOrNull(1500) {
                    libraryArtist.first { it != null }
                }?.artist?.name
                val name = dbName ?: resolveSpotifyName(artistId)
                if (name != null) {
                    resolveAndFetchByName(name)
                }
            }
        }
    }

    /** Fetches an artist's display name from Spotify by its id (for non-DB Spotify artists). */
    private suspend fun resolveSpotifyName(id: String): String? {
        val raw = com.metrolist.music.utils.ArtistIdentity.spotifyIdOf(id) ?: return null
        return com.metrolist.spotify.Spotify.artist(raw).getOrNull()?.name
    }

    private val _radioLoading = MutableStateFlow(false)
    val radioLoading = _radioLoading.asStateFlow()

    /**
     * "Radio" = music from this artist's SIMILAR artists. Uses Spotify's related artists (GQL, not the
     * rate-limited REST endpoint) when a Spotify id is known or can be found by exact name; otherwise
     * the YouTube artist page's "Fans might also like" section. A few top tracks from each related
     * artist are interleaved and shuffled into one queue.
     */
    fun playSimilarArtistsRadio(playerConnection: com.metrolist.music.playback.PlayerConnection) {
        if (_radioLoading.value) return
        _radioLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = artistPage?.artist?.title ?: libraryArtist.value?.artist?.name
                val spotifyId = spotifyArtistId
                    ?: libraryArtist.value?.artist?.spotifyId
                    ?: name?.let { n ->
                        com.metrolist.spotify.Spotify.search(n, types = listOf("artist"), limit = 5)
                            .getOrNull()?.artists?.items
                            ?.firstOrNull { it.name.equals(n, ignoreCase = true) }?.id
                    }

                if (spotifyId != null) {
                    val related = com.metrolist.spotify.Spotify.artistRelatedArtists(spotifyId)
                        .getOrNull().orEmpty().take(10)
                    val tracks = kotlinx.coroutines.coroutineScope {
                        related.map { a ->
                            async {
                                com.metrolist.spotify.Spotify.artistTopTracks(a.id).getOrNull()
                                    ?.tracks.orEmpty().take(3)
                            }
                        }.awaitAll()
                    }.flatten().distinctBy { it.id }.shuffled()
                    if (tracks.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            playerConnection.playQueue(
                                com.metrolist.music.playback.queues.SpotifyPlaylistQueue(
                                    playlistId = "artist_radio_$spotifyId",
                                    initialTracks = tracks,
                                    startIndex = 0,
                                    mapper = com.metrolist.music.playback.SpotifyYouTubeMapper(database),
                                ),
                            )
                        }
                        return@launch
                    }
                }

                // YouTube fallback: related artists from the artist page sections.
                val relatedYt = artistPage?.sections
                    ?.flatMap { it.items }
                    ?.filterIsInstance<ArtistItem>()
                    ?.distinctBy { it.id }
                    ?.take(8)
                    .orEmpty()
                val songs = kotlinx.coroutines.coroutineScope {
                    relatedYt.map { a ->
                        async {
                            YouTube.artist(a.id).getOrNull()?.sections
                                ?.flatMap { it.items }
                                ?.filterIsInstance<com.metrolist.innertube.models.SongItem>()
                                ?.take(3)
                                .orEmpty()
                        }
                    }.awaitAll()
                }.flatten().distinctBy { it.id }.shuffled()
                if (songs.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        playerConnection.playQueue(
                            com.metrolist.music.playback.queues.ListQueue(
                                title = name,
                                items = songs.map { it.toMediaMetadata().toMediaItem() },
                            ),
                        )
                    }
                } else {
                    artistPage?.artist?.radioEndpoint?.let { ep ->
                        withContext(Dispatchers.Main) {
                            playerConnection.playQueue(com.metrolist.music.playback.queues.YouTubeQueue(ep))
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Similar-artists radio failed for $artistId")
            } finally {
                _radioLoading.value = false
            }
        }
    }

    private fun isYouTubeArtistId(id: String): Boolean =
        id.startsWith("UC") || id.startsWith("FEmusic_library_privately_owned_artist")

    // Resolved YouTube artist ID for non-YouTube artists (found via search)
    private var resolvedYouTubeId: String? = null

    fun fetchArtistsFromYTM() {
        if (!isYouTubeArtistId(artistId)) {
            viewModelScope.launch {
                val name = libraryArtist.value?.artist?.name ?: resolveSpotifyName(artistId)
                if (name != null) resolveAndFetchByName(name)
            }
            return
        }
        fetchArtistPage(artistId)
    }

    private fun resolveAndFetchByName(name: String) {
        viewModelScope.launch {
            YouTube.search(name, YouTube.SearchFilter.FILTER_ARTIST)
                .onSuccess { result ->
                    val artists = result.items.filterIsInstance<ArtistItem>()
                        .filter { isYouTubeArtistId(it.id) }
                    // Prefer an exact (case-insensitive) name match to avoid opening a different
                    // same-named artist; fall back to the top result.
                    val match = artists.firstOrNull { it.title.equals(name, ignoreCase = true) }
                        ?: artists.firstOrNull()
                    if (match != null) {
                        resolvedYouTubeId = match.id
                        fetchArtistPage(match.id)
                    }
                }
                .onFailure {
                    reportException(it)
                }
        }
    }

    private fun fetchArtistPage(ytArtistId: String) {
        viewModelScope.launch {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
            val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
            YouTube.artist(ytArtistId)
                .onSuccess { page ->
                    val filteredSections = page.sections
                        .map { section ->
                            section.copy(items = section.items.filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs).filterYoutubeShorts(hideYoutubeShorts))
                        }
                        .filter { section -> section.items.isNotEmpty() }

                    artistPage = page.copy(sections = filteredSections)
                    // Store API subscription state. For Spotify-backed profiles the YouTube
                    // channel's subscription is irrelevant — the DB row (synced with Spotify) rules.
                    if (spotifyArtistId == null) _apiSubscribed.value = page.isSubscribed
                }.onFailure {
                    reportException(it)
                }
        }
    }

    fun toggleChannelSubscription() {
        val channelId = artistPage?.artist?.channelId ?: artistId
        val isCurrentlySubscribed = isChannelSubscribed.value
        val shouldBeSubscribed = !isCurrentlySubscribed

        Timber.d("[CHANNEL_TOGGLE] toggleChannelSubscription called: artistId=$artistId, channelId=$channelId, isCurrentlySubscribed=$isCurrentlySubscribed, shouldBeSubscribed=$shouldBeSubscribed")

        // Optimistically update API state for immediate UI feedback
        _apiSubscribed.value = shouldBeSubscribed

        viewModelScope.launch(Dispatchers.IO) {
            Timber.d("[CHANNEL_TOGGLE] Inside coroutine, updating database...")
            // Update local database first (optimistic update)
            // Call DAO methods directly - they're synchronous on IO dispatcher
            val artist = libraryArtist.value?.artist
            Timber.d("[CHANNEL_TOGGLE] libraryArtist.value?.artist = $artist")
            if (artist != null) {
                val newBookmark = if (shouldBeSubscribed) {
                    artist.bookmarkedAt ?: java.time.LocalDateTime.now()
                } else {
                    null
                }
                // Also set isPodcastChannel if subscribing from podcast context
                val updatedArtist = artist.copy(
                    bookmarkedAt = newBookmark,
                    isPodcastChannel = if (shouldBeSubscribed && isPodcastChannel) true else artist.isPodcastChannel
                )
                Timber.d("[CHANNEL_TOGGLE] Updating existing artist: ${artist.id} -> bookmarkedAt=$newBookmark, isPodcastChannel=${updatedArtist.isPodcastChannel}")
                database.update(updatedArtist)
            } else if (shouldBeSubscribed) {
                Timber.d("[CHANNEL_TOGGLE] No existing artist, inserting new one")
                val page = artistPage?.artist
                val name = page?.title ?: resolveSpotifyName(artistId)
                if (name != null) {
                    database.insert(
                        ArtistEntity(
                            // Spotify artists get the canonical "SP_<id>" row with spotifyId set, so the
                            // profile never flips into "local artist" mode after subscribing.
                            id = spotifyArtistId?.let { "SP_$it" } ?: artistId,
                            name = name,
                            channelId = if (spotifyArtistId == null) page?.channelId else null,
                            thumbnailUrl = page?.thumbnail,
                            bookmarkedAt = java.time.LocalDateTime.now(),
                            isPodcastChannel = isPodcastChannel,
                            spotifyId = spotifyArtistId,
                        )
                    )
                } else {
                    Timber.d("[CHANNEL_TOGGLE] no artist name, cannot insert")
                }
            } else {
                Timber.d("[CHANNEL_TOGGLE] No artist and shouldBeSubscribed=false, nothing to do")
            }

            val spotifyTarget = spotifyArtistId ?: libraryArtist.value?.artist?.spotifyId
            if (spotifyTarget != null) {
                // Spotify artist: follow/unfollow on Spotify instead of a YouTube channel.
                if (com.metrolist.spotify.Spotify.isAuthenticated()) {
                    com.metrolist.spotify.Spotify.setFollowingArtist(spotifyTarget, shouldBeSubscribed)
                        .onFailure { Timber.w(it, "Spotify follow sync failed for $spotifyTarget") }
                }
            } else {
                Timber.d("[CHANNEL_TOGGLE] Calling syncUtils.subscribeChannel($channelId, $shouldBeSubscribed)")
                syncUtils.subscribeChannel(channelId, shouldBeSubscribed)
            }
        }
    }
}
