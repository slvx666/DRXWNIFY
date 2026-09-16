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

    /**
     * The artist's full discography from the account that owns the metadata (Spotify / Yandex Music).
     * YouTube artist pages miss many EPs and older albums, so these rows replace YouTube's album and
     * single rows whenever they are available.
     */
    val catalogReleases = MutableStateFlow<List<com.metrolist.spotify.models.SpotifyAlbum>>(emptyList())

    /** Similar artists and top tracks from the same catalog (YouTube pages often lack them). */
    val catalogRelated = MutableStateFlow<List<com.metrolist.spotify.models.SpotifyArtist>>(emptyList())
    val catalogTopTracks = MutableStateFlow<List<com.metrolist.spotify.models.SpotifyTrack>>(emptyList())
    val catalogArtist = MutableStateFlow<com.metrolist.spotify.models.SpotifyArtist?>(null)
    var catalogArtistId: String? = null
        private set
    private var catalogReleasesRequested = false

    private fun loadCatalogReleases(nameHint: String?) {
        if (catalogReleasesRequested) return
        catalogReleasesRequested = true
        viewModelScope.launch(Dispatchers.IO) {
            val catalog = com.metrolist.music.catalog.Catalog
            val known = spotifyArtistId ?: libraryArtist.value?.artist?.spotifyId
            val id = known ?: run {
                if (nameHint.isNullOrBlank() || !catalog.ensureAuthenticated()) return@run null
                catalog.search(nameHint, types = listOf("artist"), limit = 10).getOrNull()?.artists?.items
                    ?.firstOrNull { com.metrolist.spotify.SearchRelevance.score(nameHint, it.name) == 100 }?.id
            }
            if (id == null) {
                catalogReleasesRequested = false
                return@launch
            }
            if (known != null) catalog.ensureAuthenticated()
            catalogArtistId = id
            if (catalog.canWrite(id)) {
                launch {
                    // Two-way: an artist followed/unfollowed in the Spotify (or Yandex) app shows so here.
                    catalog.isFollowingArtist(id).onSuccess { following ->
                        _apiSubscribed.value = following
                        val row = libraryArtist.value?.artist
                        if (!following && row?.bookmarkedAt != null && (row.spotifyId == null || row.spotifyId == id)) {
                            database.update(row.copy(bookmarkedAt = null))
                        }
                    }
                }
            }
            launch { catalog.artist(id).onSuccess { catalogArtist.value = it } }
            launch { catalog.relatedArtists(id).onSuccess { catalogRelated.value = it } }
            launch { catalog.artistTopTracks(id).onSuccess { catalogTopTracks.value = it.tracks.filter { t -> t.id.isNotBlank() } } }
            catalog.artistReleases(id)
                .onSuccess { catalogReleases.value = it }
                .onFailure {
                    Timber.w(it, "Catalog releases failed for $id")
                    catalogReleasesRequested = false
                }
        }
    }

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
                loadCatalogReleases(name)
                if (name != null) {
                    resolveAndFetchByName(name)
                }
            }
        }
    }

    /** Fetches an artist's display name from Spotify by its id (for non-DB Spotify artists). */
    private suspend fun resolveSpotifyName(id: String): String? {
        val raw = com.metrolist.music.utils.ArtistIdentity.spotifyIdOf(id) ?: return null
        return withRetry { com.metrolist.music.catalog.Catalog.artist(raw) }.getOrNull()?.name
    }

    private val _radioLoading = MutableStateFlow(false)
    val radioLoading = _radioLoading.asStateFlow()

    private val _shuffleLoading = MutableStateFlow(false)
    val shuffleLoading = _shuffleLoading.asStateFlow()

    /** Artist's full song list, fetched once per profile and reused by every shuffle press. */
    private var shufflePool: List<com.metrolist.innertube.models.SongItem>? = null
    private var lastShuffleFirstId: String? = null

    /**
     * Real shuffle: a random track from the artist's whole song list, different from the one the
     * previous press started with. YouTube's shuffleEndpoint always began with the same song.
     */
    fun playArtistShuffle(playerConnection: com.metrolist.music.playback.PlayerConnection) {
        if (_shuffleLoading.value) return
        _shuffleLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Catalog profile: shuffle the artist's catalog tracks, never starting with the same one twice.
                val catalogTracks = catalogTopTracks.value
                if (catalogTracks.isNotEmpty()) {
                    var order = catalogTracks.shuffled()
                    if (order.size > 1 && order.first().id == lastShuffleFirstId) order = order.drop(1) + order.first()
                    lastShuffleFirstId = order.first().id
                    withContext(Dispatchers.Main) {
                        playerConnection.playQueue(
                            com.metrolist.music.playback.queues.SpotifyPlaylistQueue(
                                playlistId = "artist_shuffle_$catalogArtistId",
                                initialTracks = order,
                                startIndex = 0,
                                mapper = com.metrolist.music.playback.SpotifyYouTubeMapper(database),
                            ),
                        )
                    }
                    return@launch
                }
                val pool = shufflePool ?: run {
                    val page = artistPage
                    val songsSection = page?.sections?.firstOrNull { s ->
                        s.items.any { it is com.metrolist.innertube.models.SongItem }
                    }
                    val full = songsSection?.moreEndpoint?.let { ep ->
                        withRetry { YouTube.artistItems(ep) }.getOrNull()?.items
                            ?.filterIsInstance<com.metrolist.innertube.models.SongItem>()
                    }
                    val fromPage = page?.sections?.flatMap { it.items }
                        ?.filterIsInstance<com.metrolist.innertube.models.SongItem>().orEmpty()
                    (full.orEmpty() + fromPage).distinctBy { it.id }
                }.also { shufflePool = it }

                if (pool.isEmpty()) {
                    artistPage?.artist?.shuffleEndpoint?.let { ep ->
                        withContext(Dispatchers.Main) {
                            playerConnection.playQueue(com.metrolist.music.playback.queues.YouTubeQueue(ep))
                        }
                    }
                    return@launch
                }
                var order = pool.shuffled()
                if (order.size > 1 && order.first().id == lastShuffleFirstId) {
                    order = order.drop(1) + order.first()
                }
                lastShuffleFirstId = order.first().id
                withContext(Dispatchers.Main) {
                    playerConnection.playQueue(
                        com.metrolist.music.playback.queues.ListQueue(
                            title = artistPage?.artist?.title,
                            items = order.map { it.toMediaMetadata().toMediaItem() },
                        ),
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "Artist shuffle failed for $artistId")
            } finally {
                _shuffleLoading.value = false
            }
        }
    }

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

    /**
     * Retries a flaky network call a few times with a growing pause. A single failed YouTube request
     * (403/timeouts are common right now) used to leave the profile shimmering forever until the user
     * re-opened it.
     */
    private suspend fun <T> withRetry(attempts: Int = 4, block: suspend () -> Result<T>): Result<T> {
        var last: Result<T> = block()
        var n = 1
        while (last.isFailure && n < attempts) {
            kotlinx.coroutines.delay(800L * n)
            last = block()
            n++
        }
        return last
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
            withRetry { YouTube.search(name, YouTube.SearchFilter.FILTER_ARTIST) }
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
            withRetry { YouTube.artist(ytArtistId) }
                .onSuccess { page ->
                    val filteredSections = page.sections
                        .map { section ->
                            section.copy(items = section.items.filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs).filterYoutubeShorts(hideYoutubeShorts))
                        }
                        .filter { section -> section.items.isNotEmpty() }

                    artistPage = page.copy(sections = filteredSections)
                    loadCatalogReleases(page.artist.title)
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
                    spotifyId = artist.spotifyId ?: catalogArtistId,
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
                            // Remember the catalog artist so un-following from the library also
                            // reaches the account.
                            spotifyId = spotifyArtistId ?: catalogArtistId,
                        )
                    )
                } else {
                    Timber.d("[CHANNEL_TOGGLE] no artist name, cannot insert")
                }
            } else {
                Timber.d("[CHANNEL_TOGGLE] No artist and shouldBeSubscribed=false, nothing to do")
            }

            val spotifyTarget = spotifyArtistId ?: libraryArtist.value?.artist?.spotifyId ?: catalogArtistId
            if (spotifyTarget != null) {
                // Spotify artist: follow/unfollow on Spotify instead of a YouTube channel.
                if (com.metrolist.music.catalog.Catalog.canWrite(spotifyTarget)) {
                    com.metrolist.music.catalog.Catalog.setFollowingArtist(spotifyTarget, shouldBeSubscribed)
                        .onFailure { Timber.w(it, "Spotify follow sync failed for $spotifyTarget") }
                }
            } else {
                Timber.d("[CHANNEL_TOGGLE] Calling syncUtils.subscribeChannel($channelId, $shouldBeSubscribed)")
                syncUtils.subscribeChannel(channelId, shouldBeSubscribed)
            }
        }
    }
}
