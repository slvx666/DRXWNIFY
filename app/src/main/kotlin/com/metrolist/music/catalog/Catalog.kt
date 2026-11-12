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
import com.metrolist.music.constants.VkAccessTokenKey
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/**
 * The music catalog = metadata + the user's library, owned by the MAIN account: Spotify, Yandex
 * Music or VK. With several accounts linked the user picks the main one (Spotify by default); the
 * others can show as tabs of their own in the library.
 *
 *  - Collection calls (liked songs, library) use the main account; [myLibraryOf] reads another one.
 *  - Entity calls (album, playlist, artist, track, likes) are routed by id ([sourceOf]) — Yandex
 *    entities carry a `ym_` id ([YandexIds]), VK ones `src:VK:` / `vk_` / `vkar_` ([VkCatalog]),
 *    everything else is Spotify — so an item already on screen keeps working after a switch.
 *
 * Audio is never taken from the catalog: see the resolver package.
 */
object Catalog {
    data class State(
        val spotifyConnected: Boolean = false,
        val yandexConnected: Boolean = false,
        val vkConnected: Boolean = false,
        val preferred: MetadataSource = MetadataSource.SPOTIFY,
    ) {
        fun isConnected(s: MetadataSource): Boolean = when (s) {
            MetadataSource.SPOTIFY -> spotifyConnected
            MetadataSource.YANDEX -> yandexConnected
            MetadataSource.VK -> vkConnected
        }

        /** Every linked account, Spotify first. */
        val connected: List<MetadataSource> get() = MetadataSource.entries.filter(::isConnected)

        /**
         * The main account, or null when none is linked. The chosen one while it is linked; else
         * the first linked in the order Spotify, Yandex Music, VK.
         */
        val source: MetadataSource?
            get() = preferred.takeIf(::isConnected) ?: connected.firstOrNull()

        val isActive: Boolean get() = source != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** True once the accounts were read from preferences (before that [state] is just the default). */
    private val stateLoaded = MutableStateFlow(false)

    /** Localized title of the Liked Songs pseudo-playlist for Yandex Music. */
    @Volatile
    var likedSongsName: String = "Liked Songs"

    fun init(dataStore: DataStore<Preferences>, scope: CoroutineScope, likedSongsTitle: String) {
        likedSongsName = likedSongsTitle
        MainAccount.init(dataStore)
        // Liked Songs read from disk into memory a little after start (not holding up the intro): the
        // screen then opens with its list on the first frame.
        scope.launch(Dispatchers.IO) {
            stateLoaded.first { it }
            kotlinx.coroutines.delay(4_000L)
            source?.let { LikedSongsCache.get(it.name) }
        }
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
                        // The user's own VK account only: the shared test account has no library.
                        vkConnected = prefs[VkAccessTokenKey].orEmpty().isNotEmpty(),
                        preferred = prefs[PrimaryMetadataSourceKey]
                            ?.let { v -> MetadataSource.entries.firstOrNull { it.name == v } }
                            ?: MetadataSource.SPOTIFY,
                    )
                }
                .distinctUntilChanged()
                .collect { s ->
                    if (s.source != _state.value.source) invalidateCaches()
                    _state.value = s
                    stateLoaded.value = true
                }
        }
    }

    val source: MetadataSource? get() = _state.value.source

    val isActive: Boolean get() = _state.value.isActive

    val isYandexSource: Boolean get() = source == MetadataSource.YANDEX

    fun isYandexId(id: String?): Boolean = YandexIds.isYandex(id)

    fun isVkId(id: String?): Boolean = VkCatalog.isVkId(id)

    /** The liked songs of Spotify opened as a playlist (from Spotify's own library tab). */
    const val SPOTIFY_LIKED_ID = "sp_liked"

    /** The catalog an id belongs to. */
    fun sourceOf(id: String?): MetadataSource = when {
        isYandexId(id) -> MetadataSource.YANDEX
        isVkId(id) -> MetadataSource.VK
        else -> MetadataSource.SPOTIFY
    }

    /** True when the account owning [id] is signed in, i.e. writes (like, save, follow) can succeed. */
    fun canWrite(id: String?): Boolean = when (sourceOf(id)) {
        MetadataSource.YANDEX -> YandexMusic.isAuthenticated()
        MetadataSource.VK -> _state.value.vkConnected && VkCatalog.isAuthenticated
        // Spotify's anonymous token (search and pages without an account) is a token too: it can't
        // write anything, so only a connected account counts.
        MetadataSource.SPOTIFY -> _state.value.spotifyConnected && Spotify.isAuthenticated()
    }

    /** Makes sure the selected source has a usable session. */
    suspend fun ensureAuthenticated(): Boolean = ensureAuthenticated(source)

    suspend fun ensureAuthenticated(of: MetadataSource?): Boolean = when (of) {
        MetadataSource.SPOTIFY -> SpotifyTokenManager.ensureAuthenticated()
        MetadataSource.YANDEX -> YandexMusic.isAuthenticated()
        MetadataSource.VK -> VkCatalog.isAuthenticated
        // No account: Spotify's public catalog is still open to anonymous clients, so search and
        // browsing work. Anything to do with a library stays unavailable ([noAccount]).
        null -> SpotifyTokenManager.ensurePublicAccess()
    }

    private fun noAccount(): Nothing = throw IllegalStateException("No music account connected")

    // ── Entities (routed by id) ──────────────────────────────────────────────────────────────────

    /**
     * An album, from the cache when it was fetched in the last [ALBUM_CACHE_TTL_MS] (albums barely
     * change): reopening one used to download it again every time. Kept in memory and on disk, so
     * it opens at once after a restart too.
     */
    suspend fun album(id: String): Result<SpotifyAlbum> {
        val now = System.currentTimeMillis()
        albumMemory[id]?.takeIf { now - it.first < ALBUM_CACHE_TTL_MS }?.let { return Result.success(it.second) }
        val onDisk = readAlbumFromDisk(id)
        onDisk?.takeIf { now - it.first < ALBUM_CACHE_TTL_MS }?.let { cached ->
            albumMemory[id] = cached
            return Result.success(cached.second)
        }
        suspend fun fetch(): Result<SpotifyAlbum> {
            val fresh = when (sourceOf(id)) {
                MetadataSource.YANDEX -> YandexMusic.album(id)
                MetadataSource.VK -> VkCatalog.album(id)
                MetadataSource.SPOTIFY -> Spotify.album(id)
            }
            fresh.onSuccess { album ->
                if (album.tracks?.items.isNullOrEmpty()) return@onSuccess
                albumMemory[id] = System.currentTimeMillis() to album
                if (albumMemory.size > ALBUM_MEMORY_MAX) albumMemory.keys.firstOrNull()?.let { albumMemory.remove(it) }
                writeAlbumToDisk(id, album)
            }
            return fresh
        }
        if (onDisk != null) {
            // An older copy is there: a slow or missing connection must not keep a downloaded album
            // behind a spinner. Wait briefly for a fresh one, else show the copy and refresh behind it.
            val fresh = kotlinx.coroutines.withTimeoutOrNull(STALE_ALBUM_WAIT_MS) { fetch() }
            if (fresh?.isSuccess == true) return fresh
            if (fresh == null) refreshScope.launch { fetch() }
            return Result.success(onDisk.second)
        }
        return fetch()
    }

    private const val STALE_ALBUM_WAIT_MS = 2_500L
    private val refreshScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private const val ALBUM_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val ALBUM_MEMORY_MAX = 80
    private val albumMemory = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, SpotifyAlbum>>()
    private val albumJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Volatile
    private var cacheDir: java.io.File? = null

    /** Where catalog entities are kept between launches (the app's cache directory). */
    fun setCacheDir(dir: java.io.File) {
        cacheDir = java.io.File(dir, "catalog_albums_v2").also { it.mkdirs() }
    }

    private fun albumFile(id: String): java.io.File? =
        cacheDir?.let { java.io.File(it, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json") }

    private fun readAlbumFromDisk(id: String): Pair<Long, SpotifyAlbum>? = runCatching {
        val file = albumFile(id)?.takeIf { it.exists() } ?: return null
        file.lastModified() to albumJson.decodeFromString(SpotifyAlbum.serializer(), file.readText())
    }.getOrNull()

    private fun writeAlbumToDisk(id: String, album: SpotifyAlbum) {
        runCatching { albumFile(id)?.writeText(albumJson.encodeToString(SpotifyAlbum.serializer(), album)) }
        // A pinned (downloaded) album keeps its copy up to date too.
        pinnedFile(id)?.takeIf { it.exists() }?.let { file -> runCatching { file.writeText(albumJson.encodeToString(SpotifyAlbum.serializer(), album)) } }
    }

    // ── Downloaded albums ──
    // The cache above lives in the cache directory, which the system (or "clear cache") may empty
    // at any time, and its copy is only made when the album is opened. A downloaded album must open
    // without a connection whatever happened to the cache: its page is pinned in the app's files.

    @Volatile
    private var pinnedDir: java.io.File? = null

    fun setPinnedDir(filesDir: java.io.File) {
        pinnedDir = java.io.File(filesDir, "catalog_albums_pinned").also { it.mkdirs() }
    }

    private fun pinnedFile(id: String): java.io.File? =
        pinnedDir?.let { java.io.File(it, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json") }

    /** Keeps [album] (shown as album [id]) for opening offline: it has downloaded tracks. */
    fun pinAlbum(id: String, album: SpotifyAlbum) {
        if (album.tracks?.items.isNullOrEmpty()) return
        runCatching { pinnedFile(id)?.writeText(albumJson.encodeToString(SpotifyAlbum.serializer(), album)) }
    }

    /** The pinned copy of album [id], or null. */
    fun pinnedAlbum(id: String): SpotifyAlbum? = runCatching {
        val file = pinnedFile(id)?.takeIf { it.exists() } ?: return null
        albumJson.decodeFromString(SpotifyAlbum.serializer(), file.readText())
    }.getOrNull()

    suspend fun artist(id: String): Result<SpotifyArtist> = when (sourceOf(id)) {
        MetadataSource.YANDEX -> YandexMusic.artist(id)
        MetadataSource.VK -> VkCatalog.artist(id)
        MetadataSource.SPOTIFY -> Spotify.artist(id)
    }

    suspend fun artistTopTracks(id: String): Result<ArtistTopTracksResponse> = when (sourceOf(id)) {
        MetadataSource.YANDEX -> YandexMusic.artistTopTracks(id)
        MetadataSource.VK -> VkCatalog.artistTopTracks(id)
        MetadataSource.SPOTIFY -> Spotify.artistTopTracks(id)
    }

    /** All releases of an artist (albums, singles, EPs, compilations) from the catalog that owns it. */
    suspend fun artistReleases(id: String): Result<List<SpotifyAlbum>> =
        if (isYandexId(id)) {
            YandexMusic.artistAlbums(id)
        } else if (isVkId(id)) {
            VkCatalog.artistAlbums(id)
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

    /**
     * An artist's newest releases in one request (the home page asks for many artists at once;
     * [artistReleases] pages through the whole discography).
     */
    suspend fun latestReleases(id: String): Result<List<SpotifyAlbum>> =
        if (isYandexId(id)) {
            YandexMusic.artistAlbums(id)
        } else if (isVkId(id)) {
            VkCatalog.artistAlbums(id)
        } else {
            Spotify.artistDiscography(id).map { d -> (listOfNotNull(d.latest) + d.albums + d.singles).distinctBy { it.id } }
        }

    /** Releases of other artists [id] appears on (features, collaborations), newest first. */
    suspend fun artistAppearsOn(id: String): Result<List<SpotifyAlbum>> =
        (
            when (sourceOf(id)) {
                MetadataSource.YANDEX -> YandexMusic.artistAppearsOn(id)
                // VK has no "appears on" list.
                MetadataSource.VK -> Result.success(emptyList())
                MetadataSource.SPOTIFY -> Spotify.artistAppearsOn(id)
            }
            )
            .map { list -> list.filter { it.id.isNotBlank() }.sortedByDescending { it.releaseDate.orEmpty() } }

    suspend fun relatedArtists(id: String): Result<List<SpotifyArtist>> = when (sourceOf(id)) {
        MetadataSource.YANDEX -> YandexMusic.similarArtists(id)
        // VK keeps no "similar artists": the same artist on Spotify's public catalog has them.
        MetadataSource.VK -> runCatching {
            val name = VkCatalog.artist(id).getOrNull()?.name ?: return@runCatching emptyList()
            val spotifyId = CrossCatalog.spotifyArtistByName(name) ?: return@runCatching emptyList()
            Spotify.artistRelatedArtists(spotifyId).getOrDefault(emptyList())
        }
        MetadataSource.SPOTIFY -> Spotify.artistRelatedArtists(id)
    }

    suspend fun getTrack(id: String): Result<SpotifyTrack> = when (sourceOf(id)) {
        MetadataSource.YANDEX -> YandexMusic.getTrack(id)
        MetadataSource.VK -> VkCatalog.getTrack(id)
        MetadataSource.SPOTIFY -> Spotify.getTrack(id)
    }

    suspend fun playlist(id: String): Result<SpotifyPlaylist> = likedAsPlaylist(id)?.let { Result.success(it) } ?: when (sourceOf(id)) {
        MetadataSource.YANDEX -> YandexMusic.playlist(id)
        MetadataSource.VK -> VkCatalog.playlist(id)
        MetadataSource.SPOTIFY -> Spotify.playlist(id)
    }

    /** An account's liked songs as a playlist page (its library tab opens them so), or null for any other id. */
    private fun likedAsPlaylist(id: String): SpotifyPlaylist? {
        val source = when (id) {
            SPOTIFY_LIKED_ID -> MetadataSource.SPOTIFY
            YandexIds.LIKED_SONGS_ID -> MetadataSource.YANDEX
            else -> return null
        }
        return SpotifyPlaylist(
            id = id,
            name = if (source == MetadataSource.YANDEX) "$likedSongsName · Yandex Music" else "$likedSongsName · Spotify",
        )
    }

    suspend fun playlistTracks(id: String, limit: Int, offset: Int): Result<SpotifyPaging<SpotifyPlaylistTrack>> = when (id) {
        SPOTIFY_LIKED_ID, YandexIds.LIKED_SONGS_ID ->
            likedSongsOf(if (id == SPOTIFY_LIKED_ID) MetadataSource.SPOTIFY else MetadataSource.YANDEX, limit, offset).map { page ->
                SpotifyPaging(
                    items = page.items.map { SpotifyPlaylistTrack(addedAt = it.addedAt, track = it.track) },
                    total = page.total,
                    limit = page.limit,
                    offset = page.offset,
                )
            }
        else -> playlistTracksById(id, limit, offset)
    }

    private suspend fun playlistTracksById(id: String, limit: Int, offset: Int): Result<SpotifyPaging<SpotifyPlaylistTrack>> = when (sourceOf(id)) {
        MetadataSource.YANDEX -> YandexMusic.playlistTracks(id, limit, offset)
        MetadataSource.VK -> VkCatalog.playlistTracks(id, limit, offset)
        MetadataSource.SPOTIFY -> Spotify.playlistTracks(id, limit, offset)
    }

    suspend fun saveTrack(id: String): Result<Unit> =
        when (sourceOf(id)) {
            MetadataSource.YANDEX -> YandexMusic.setTrackLiked(id, true)
            MetadataSource.VK -> VkCatalog.saveTrack(id)
            MetadataSource.SPOTIFY -> Spotify.saveTrack(id)
        }.onSuccess { invalidateCaches() }

    suspend fun removeTrack(id: String): Result<Unit> =
        when (sourceOf(id)) {
            MetadataSource.YANDEX -> YandexMusic.setTrackLiked(id, false)
            MetadataSource.VK -> VkCatalog.removeTrack(id)
            MetadataSource.SPOTIFY -> Spotify.removeTrack(id)
        }
            .onSuccess {
                LikedSongsCache.remove(id)
                invalidateCaches()
            }

    // Spotify albums are saved/checked through "Your Library" (the web player's GQL operations). The
    // public REST /me/albums endpoints reject the web-player token, which is why the old "+" always failed.

    private const val SAVED_ALBUMS_TTL_MS = 2 * 60 * 1000L

    @Volatile
    private var spotifySavedAlbums: Pair<Long, MutableSet<String>>? = null
    private val savedAlbumsMutex = Mutex()

    private suspend fun spotifySavedAlbumIds(): MutableSet<String> = savedAlbumsMutex.withLock {
        spotifySavedAlbums?.takeIf { System.currentTimeMillis() - it.first < SAVED_ALBUMS_TTL_MS }?.let { return it.second }
        SpotifyTokenManager.ensureAuthenticated()
        val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var offset = 0
        while (offset < 2000) {
            val page = Spotify.myLibrary(filter = "Albums", limit = 50, offset = offset).getOrThrow()
            page.items.filter { it.kind == SpotifyLibraryEntry.Kind.ALBUM }.forEach { ids += it.id }
            offset += 50
            if (page.items.isEmpty() || offset >= page.total) break
        }
        spotifySavedAlbums = System.currentTimeMillis() to ids
        ids
    }

    suspend fun isAlbumSaved(id: String): Result<Boolean> = when (sourceOf(id)) {
        MetadataSource.YANDEX -> YandexMusic.isAlbumLiked(id)
        MetadataSource.VK -> VkCatalog.isAlbumSaved(id)
        MetadataSource.SPOTIFY -> runCatching { id in spotifySavedAlbumIds() }
    }

    suspend fun saveAlbum(id: String): Result<Unit> =
        if (isYandexId(id)) {
            YandexMusic.setAlbumLiked(id, true)
        } else if (isVkId(id)) {
            VkCatalog.saveAlbum(id)
        } else {
            SpotifyTokenManager.ensureAuthenticated()
            Spotify.addToLibrary(listOf("spotify:album:$id")).onSuccess { spotifySavedAlbums?.second?.add(id) }
        }.also { invalidateCaches() }

    suspend fun removeAlbum(id: String): Result<Unit> =
        if (isYandexId(id)) {
            YandexMusic.setAlbumLiked(id, false)
        } else if (isVkId(id)) {
            VkCatalog.removeAlbum(id)
        } else {
            SpotifyTokenManager.ensureAuthenticated()
            Spotify.removeFromLibrary(listOf("spotify:album:$id")).onSuccess { spotifySavedAlbums?.second?.remove(id) }
        }.also { invalidateCaches() }

    @Volatile
    private var spotifyFollowedArtists: Pair<Long, MutableSet<String>>? = null
    private val followedArtistsMutex = Mutex()

    /** Artists in the Spotify library ("Your Library" -> Artists = the artists the user follows). */
    private suspend fun spotifyFollowedArtistIds(): MutableSet<String> = followedArtistsMutex.withLock {
        spotifyFollowedArtists?.takeIf { System.currentTimeMillis() - it.first < SAVED_ALBUMS_TTL_MS }?.let { return it.second }
        SpotifyTokenManager.ensureAuthenticated()
        val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var offset = 0
        while (offset < 5000) {
            val page = Spotify.myLibrary(filter = "Artists", limit = 50, offset = offset).getOrThrow()
            page.items.filter { it.kind == SpotifyLibraryEntry.Kind.ARTIST }.forEach { ids += it.id }
            offset += 50
            if (page.items.isEmpty() || offset >= page.total) break
        }
        spotifyFollowedArtists = System.currentTimeMillis() to ids
        ids
    }

    suspend fun isFollowingArtist(id: String): Result<Boolean> =
        if (isYandexId(id)) {
            YandexMusic.isArtistLiked(id)
        } else if (isVkId(id)) {
            // VK keeps no follows: an artist is followed in the app only.
            Result.success(false)
        } else {
            runCatching { id in spotifyFollowedArtistIds() }
                .recoverCatching { Spotify.isFollowingArtist(id).getOrThrow() }
        }

    /**
     * Follow / unfollow. On Spotify this goes through the library API the web player itself uses
     * (the REST /me/following endpoint rejects the web-player token, so following in the app never
     * reached the account); REST stays as a fallback.
     */
    suspend fun setFollowingArtist(id: String, follow: Boolean): Result<Unit> =
        if (isYandexId(id)) {
            YandexMusic.setArtistLiked(id, follow)
        } else if (isVkId(id)) {
            Result.failure(UnsupportedOperationException("VK has no artist follows"))
        } else {
            SpotifyTokenManager.ensureAuthenticated()
            val uris = listOf("spotify:artist:$id")
            (if (follow) Spotify.addToLibrary(uris) else Spotify.removeFromLibrary(uris))
                .recoverCatching { gqlError ->
                    Spotify.setFollowingArtist(id, follow).getOrElse { throw gqlError }
                }
                .onSuccess {
                    spotifyFollowedArtists?.second?.let { set -> if (follow) set.add(id) else set.remove(id) }
                }
        }.also { invalidateCaches() }

    /** Yandex and VK playlists are read-only in Drxwnify (no reorder/rename/remove through these APIs). */
    fun isEditablePlaylist(id: String): Boolean = !isYandexId(id) && !isVkId(id) && id != SPOTIFY_LIKED_ID

    // ── Collections (selected source only) ───────────────────────────────────────────────────────

    private const val LIBRARY_TTL_MS = 2 * 60 * 1000L
    private val libraryMutex = Mutex()
    private val yandexLibrary = HashMap<String, Pair<Long, List<SpotifyLibraryEntry>>>()

    /** Spotify library pages fetched recently (e.g. warmed up during the launch intro). */
    private val spotifyLibraryPages = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, SpotifyPaging<SpotifyLibraryEntry>>>()

    /** Bumped whenever the account library changes from here (a like, a follow, a saved album). */
    private val _libraryVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val libraryVersion: kotlinx.coroutines.flow.StateFlow<Int> = _libraryVersion

    fun invalidateCaches() {
        _libraryVersion.value++
        AccountLikes.markStale()
        LibraryCache.markStale()
        LikedSongsCache.markStale()
        yandexLibrary.clear()
        spotifyLibraryPages.clear()
        VkCatalog.invalidate()
    }

    suspend fun likedSongs(limit: Int, offset: Int): Result<SpotifyPaging<SpotifySavedTrack>> = likedSongsOf(source, limit, offset)

    /** Liked songs of one account (the main one or another linked one). */
    suspend fun likedSongsOf(of: MetadataSource?, limit: Int, offset: Int): Result<SpotifyPaging<SpotifySavedTrack>> = when (of) {
        MetadataSource.SPOTIFY -> Spotify.likedSongs(limit, offset)
        MetadataSource.YANDEX -> YandexMusic.likedSongs(limit, offset)
        MetadataSource.VK -> VkCatalog.likedSongs(limit, offset)
        null -> runCatching { noAccount() }
    }

    /** Waits until the connected accounts are read at app start (before that, [source] reads as none). */
    suspend fun awaitAccounts() {
        stateLoaded.first { it }
    }

    /** One page of the library for [filter] ("Playlists" / "Albums" / "Artists" / null = all). */
    /** Loads the whole library ahead of time (e.g. during the launch intro) so the Library tab opens instantly. */
    suspend fun prewarmLibrary(): List<SpotifyLibraryEntry> {
        // At app start the accounts may not be read yet; without this the prewarm saw "no account".
        stateLoaded.first { it }
        if (source == null || !ensureAuthenticated()) return emptyList()
        // Same paging as the library screen, so every page it asks for is already cached.
        val all = mutableListOf<SpotifyLibraryEntry>()
        var offset = 0
        while (offset < 1000) {
            val page = myLibrary(filter = null, limit = 50, offset = offset).getOrNull() ?: break
            all += page.items
            offset += 50
            if (page.items.isEmpty() || offset >= page.total) break
        }
        return all
    }

    suspend fun myLibrary(filter: String?, limit: Int, offset: Int): Result<SpotifyPaging<SpotifyLibraryEntry>> =
        myLibraryOf(source, filter, limit, offset)

    /** One page of the library of [of] — the main account, or another linked one shown as its own tab. */
    suspend fun myLibraryOf(of: MetadataSource?, filter: String?, limit: Int, offset: Int): Result<SpotifyPaging<SpotifyLibraryEntry>> =
        when (of) {
            MetadataSource.SPOTIFY -> {
                val key = "$filter|$limit|$offset"
                val cached = spotifyLibraryPages[key]?.takeIf { System.currentTimeMillis() - it.first < LIBRARY_TTL_MS }
                if (cached != null) Result.success(cached.second)
                else Spotify.myLibrary(filter, limit, offset).onSuccess { spotifyLibraryPages[key] = System.currentTimeMillis() to it }
            }
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
            MetadataSource.VK -> runCatching {
                val key = "vk|" + (filter ?: "*")
                val all = libraryMutex.withLock {
                    yandexLibrary[key]?.takeIf { offset > 0 || System.currentTimeMillis() - it.first < LIBRARY_TTL_MS }?.second
                        ?: VkCatalog.library(likedSongsName).getOrThrow()
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

    /**
     * Search in one given catalog, whatever the library source is: the search box offers Spotify
     * and (when linked) Yandex Music side by side. Results carry their own ids, so opening them is
     * routed to the right service.
     */
    suspend fun searchIn(
        target: MetadataSource,
        query: String,
        types: List<String>,
        limit: Int,
        offset: Int = 0,
    ): Result<SpotifySearchResult> = when (target) {
        MetadataSource.SPOTIFY -> Spotify.search(query, types, limit, offset)
        MetadataSource.YANDEX -> YandexMusic.search(query, types, limit, offset)
        MetadataSource.VK -> VkCatalog.search(query, types, limit, offset)
    }

    /** Spotify works without an account too (public catalog); Yandex Music needs its account. */
    suspend fun ensureSearchable(target: MetadataSource): Boolean = when (target) {
        MetadataSource.YANDEX -> YandexMusic.isAuthenticated()
        MetadataSource.VK -> VkCatalog.isAuthenticated
        MetadataSource.SPOTIFY -> if (_state.value.spotifyConnected) {
            SpotifyTokenManager.ensureAuthenticated()
        } else {
            SpotifyTokenManager.ensurePublicAccess()
        }
    }

    /** Search in the selected source. [types] are Spotify type names. */
    suspend fun search(query: String, types: List<String>, limit: Int, offset: Int = 0): Result<SpotifySearchResult> =
        when (source) {
            MetadataSource.SPOTIFY -> Spotify.search(query, types, limit, offset)
            MetadataSource.YANDEX -> YandexMusic.search(query, types, limit, offset)
            // VK's search has a strict limit (and is shared with playback): bulk lookups (an import,
            // a like across catalogs) go to Spotify's public catalog instead.
            MetadataSource.VK -> Spotify.search(query, types, limit, offset)
            // Public catalog, no account needed (see [ensureAuthenticated]).
            null -> Spotify.search(query, types, limit, offset)
        }
}
