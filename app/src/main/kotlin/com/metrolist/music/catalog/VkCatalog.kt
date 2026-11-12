/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.catalog

import com.metrolist.music.resolver.AudioFallbackEngine
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.providers.VkAudioProvider
import com.metrolist.music.resolver.providers.VkPlaylist
import com.metrolist.music.resolver.providers.VkTrack
import com.metrolist.spotify.ArtistTopTracksResponse
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyImage
import com.metrolist.spotify.models.SpotifyLibraryEntry
import com.metrolist.spotify.models.SpotifyPaging
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyPlaylistOwner
import com.metrolist.spotify.models.SpotifyPlaylistTrack
import com.metrolist.spotify.models.SpotifyPlaylistTracksRef
import com.metrolist.spotify.models.SpotifySavedTrack
import com.metrolist.spotify.models.SpotifySearchResult
import com.metrolist.spotify.models.SpotifySimpleAlbum
import com.metrolist.spotify.models.SpotifySimpleArtist
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * VK Music as a catalog, beside Spotify and Yandex Music: the same models, so every page (album,
 * artist, playlist, liked songs, library) works for VK as it does for the other two.
 *
 * Ids:
 *  - a track is `src:VK:owner_audio[_key]` — the very id a VK track found by the source search has,
 *    so its audio, history, likes and downloads are one and the same whichever way it came;
 *  - an album or playlist is `vk_owner_id[_key]` (VK keeps both as "playlists");
 *  - an artist is `vkar_<VK artist id>`.
 *
 * Only official releases have albums and artist pages in VK; a user's upload has neither.
 */
object VkCatalog {
    const val TRACK_PREFIX = "src:VK:"
    const val PLAYLIST_PREFIX = "vk_"
    const val ARTIST_PREFIX = "vkar_"
    const val LIKED_SONGS_ID = "vk_liked"

    private val provider: VkAudioProvider?
        get() = AudioFallbackEngine.provider(AudioProviderId.VK) as? VkAudioProvider

    fun isVkId(id: String?): Boolean =
        id != null && (id.startsWith(TRACK_PREFIX) || id.startsWith(ARTIST_PREFIX) || id.startsWith(PLAYLIST_PREFIX))

    /** The user's own VK account is linked (not the shared test one): the library and writing work. */
    val isAuthenticated: Boolean
        get() = com.metrolist.music.resolver.VkMusic.ownAccount && provider != null

    // ── ids ──

    fun trackId(fullId: String) = TRACK_PREFIX + fullId

    fun fullIdOf(trackId: String): String? = trackId.takeIf { it.startsWith(TRACK_PREFIX) }?.removePrefix(TRACK_PREFIX)

    fun playlistId(ownerId: Long, id: Long, accessKey: String?) =
        PLAYLIST_PREFIX + listOfNotNull(ownerId.toString(), id.toString(), accessKey).joinToString("_")

    fun playlistId(p: VkPlaylist) = playlistId(p.ownerId, p.id, p.accessKey)

    data class Address(val ownerId: Long, val id: Long, val accessKey: String?)

    fun parsePlaylistId(id: String): Address? {
        if (!id.startsWith(PLAYLIST_PREFIX) || id == LIKED_SONGS_ID) return null
        val parts = id.removePrefix(PLAYLIST_PREFIX).split('_')
        val owner = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val pid = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return Address(owner, pid, parts.getOrNull(2)?.takeIf { it.isNotBlank() })
    }

    fun artistId(raw: String) = ARTIST_PREFIX + raw

    fun rawArtistId(id: String): String? = id.takeIf { it.startsWith(ARTIST_PREFIX) }?.removePrefix(ARTIST_PREFIX)

    // ── mapping ──

    private fun images(url: String?): List<SpotifyImage> = url?.let { listOf(SpotifyImage(url = it, width = 600, height = 600)) }.orEmpty()

    fun trackOf(t: VkTrack): SpotifyTrack = SpotifyTrack(
        id = trackId(t.fullId),
        name = t.title,
        artists = t.artists.map { a -> SpotifySimpleArtist(id = a.id?.let(::artistId), name = a.name) },
        album = t.album?.let { a ->
            SpotifySimpleAlbum(
                id = playlistId(a.ownerId, a.id, a.accessKey),
                name = a.title,
                images = images(a.cover ?: t.thumb),
                albumType = "album",
            )
        } ?: t.thumb?.let { SpotifySimpleAlbum(id = "", name = "", images = images(it)) },
        durationMs = t.durationSec * 1000,
        uri = "vk:audio:${t.fullId}",
    )

    private fun albumType(p: VkPlaylist): String = when {
        !p.isAlbum -> "playlist"
        p.count in 1..3 -> "single"
        p.count in 4..6 -> "ep"
        else -> "album"
    }

    fun albumOf(p: VkPlaylist, tracks: List<VkTrack>): SpotifyAlbum {
        val artistNames = p.artist.split(", ").filter { it.isNotBlank() }
        // The album's artists with VK's ids, taken from its tracks when they name the same people.
        val known = tracks.flatMap { it.artists }.filter { it.id != null }.distinctBy { it.name.lowercase() }
        val artists = artistNames.map { name ->
            SpotifySimpleArtist(id = known.firstOrNull { it.name.equals(name, true) }?.id?.let(::artistId), name = name)
        }.ifEmpty { known.take(1).map { SpotifySimpleArtist(id = it.id?.let(::artistId), name = it.name) } }
        return SpotifyAlbum(
            id = playlistId(p),
            name = p.title,
            albumType = albumType(p),
            artists = artists,
            images = images(p.coverUrl),
            releaseDate = p.year?.toString(),
            totalTracks = tracks.size.takeIf { it > 0 } ?: p.count,
            tracks = SpotifyPaging(
                items = tracks.map { trackOf(it.copy(thumb = it.thumb ?: p.coverUrl)) },
                total = tracks.size,
                limit = tracks.size,
            ),
            uri = "vk:playlist:${p.ownerId}_${p.id}",
        )
    }

    fun simpleAlbumOf(p: VkPlaylist) = albumOf(p, emptyList())

    fun playlistOf(p: VkPlaylist): SpotifyPlaylist = SpotifyPlaylist(
        id = playlistId(p),
        name = p.title,
        images = images(p.coverUrl),
        owner = SpotifyPlaylistOwner(id = p.ownerId.toString(), displayName = p.artist.ifBlank { null }),
        tracks = SpotifyPlaylistTracksRef(total = p.count),
        uri = "vk:playlist:${p.ownerId}_${p.id}",
    )

    // ── entities ──

    private suspend fun playlistAt(address: Address): VkPlaylist =
        com.metrolist.music.resolver.VkMusic.playlist(keyOf(address))
            ?: provider?.playlistById(address.ownerId, address.id, address.accessKey)?.also { com.metrolist.music.resolver.VkMusic.remember(listOf(it)) }
            ?: throw IllegalStateException("VK album not found")

    /** The key [com.metrolist.music.resolver.VkMusic] remembers playlists by. */
    private fun keyOf(a: Address): String = "${a.ownerId}_${a.id}"

    suspend fun album(id: String): Result<SpotifyAlbum> = runCatching {
        val address = parsePlaylistId(id) ?: throw IllegalArgumentException("Not a VK album: $id")
        val p = playlistAt(address)
        val tracks = provider?.playlistTrackList(address.ownerId, address.id, address.accessKey ?: p.accessKey).orEmpty()
        albumOf(p, tracks)
    }

    suspend fun playlist(id: String): Result<SpotifyPlaylist> = runCatching {
        if (id == LIKED_SONGS_ID) {
            return@runCatching SpotifyPlaylist(id = id, name = Catalog.likedSongsName, tracks = SpotifyPlaylistTracksRef(total = likedCount()))
        }
        val address = parsePlaylistId(id) ?: throw IllegalArgumentException("Not a VK playlist: $id")
        playlistOf(playlistAt(address))
    }

    private val playlistPages = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<VkTrack>>>()

    suspend fun playlistTracks(id: String, limit: Int, offset: Int): Result<SpotifyPaging<SpotifyPlaylistTrack>> = runCatching {
        val all = if (id == LIKED_SONGS_ID) {
            myTracks()
        } else {
            val address = parsePlaylistId(id) ?: throw IllegalArgumentException("Not a VK playlist: $id")
            playlistPages[id]?.takeIf { offset > 0 || System.currentTimeMillis() - it.first < PAGE_TTL_MS }?.second
                ?: provider?.playlistTrackList(address.ownerId, address.id, address.accessKey).orEmpty()
                    .also { playlistPages[id] = System.currentTimeMillis() to it }
        }
        SpotifyPaging(
            items = all.drop(offset).take(limit).map { SpotifyPlaylistTrack(track = trackOf(it)) },
            total = all.size,
            limit = limit,
            offset = offset,
        )
    }

    suspend fun getTrack(id: String): Result<SpotifyTrack> = runCatching {
        val fullId = fullIdOf(id) ?: throw IllegalArgumentException("Not a VK track: $id")
        val known = com.metrolist.music.resolver.providers.VkTrackStore.get(fullId)
            ?: provider?.tracksById(listOf(fullId))?.firstOrNull()
            ?: throw IllegalStateException("VK track not found")
        trackOf(known)
    }

    /** The album and artists of a VK track, by its full id (from memory, else asked from VK once). */
    suspend fun trackInfo(fullId: String): VkTrack? =
        com.metrolist.music.resolver.providers.VkTrackStore.get(fullId)
            ?: runCatching { provider?.tracksById(listOf(fullId))?.firstOrNull() }.getOrNull()

    suspend fun artist(id: String): Result<SpotifyArtist> = runCatching {
        val raw = rawArtistId(id) ?: throw IllegalArgumentException("Not a VK artist: $id")
        val a = provider?.artist(raw) ?: throw IllegalStateException("VK artist not found")
        SpotifyArtist(id = id, name = a.name, images = images(a.photo), uri = "vk:artist:$raw")
    }

    suspend fun artistTopTracks(id: String): Result<ArtistTopTracksResponse> = runCatching {
        val raw = rawArtistId(id) ?: throw IllegalArgumentException("Not a VK artist: $id")
        ArtistTopTracksResponse(provider?.artistTracks(raw, 30).orEmpty().map(::trackOf))
    }

    suspend fun artistAlbums(id: String): Result<List<SpotifyAlbum>> = runCatching {
        val raw = rawArtistId(id) ?: throw IllegalArgumentException("Not a VK artist: $id")
        val albums = provider?.artistAlbums(raw).orEmpty()
        com.metrolist.music.resolver.VkMusic.remember(albums)
        albums.map(::simpleAlbumOf).sortedByDescending { it.releaseDate.orEmpty() }
    }

    // ── the library ──

    private val libraryMutex = Mutex()

    @Volatile
    private var myTracksCache: Pair<Long, List<VkTrack>>? = null

    @Volatile
    private var myPlaylistsCache: Pair<Long, List<VkPlaylist>>? = null

    suspend fun myTracks(force: Boolean = false): List<VkTrack> = libraryMutex.withLock {
        myTracksCache?.takeIf { !force && System.currentTimeMillis() - it.first < LIBRARY_TTL_MS }?.let { return it.second }
        val list = provider?.myTrackList().orEmpty()
        myTracksCache = System.currentTimeMillis() to list
        list
    }

    private suspend fun myPlaylists(): List<VkPlaylist> {
        myPlaylistsCache?.takeIf { System.currentTimeMillis() - it.first < LIBRARY_TTL_MS }?.let { return it.second }
        val list = com.metrolist.music.resolver.VkMusic.myPlaylists().getOrThrow()
        myPlaylistsCache = System.currentTimeMillis() to list
        return list
    }

    private fun likedCount(): Int = myTracksCache?.second?.size ?: 0

    fun invalidate() {
        myTracksCache = null
        myPlaylistsCache = null
        playlistPages.clear()
    }

    suspend fun likedSongs(limit: Int, offset: Int): Result<SpotifyPaging<SpotifySavedTrack>> = runCatching {
        val all = myTracks()
        SpotifyPaging(
            items = all.drop(offset).take(limit).map { SpotifySavedTrack(track = trackOf(it)) },
            total = all.size,
            limit = limit,
            offset = offset,
        )
    }

    /**
     * The whole VK library as library entries: "My music" first, then the albums and playlists
     * the user has. VK keeps no list of followed artists; the app's own follows show beside it.
     */
    suspend fun library(likedSongsName: String): Result<List<SpotifyLibraryEntry>> = runCatching {
        val playlists = myPlaylists()
        val liked = SpotifyLibraryEntry(
            kind = SpotifyLibraryEntry.Kind.LIKED_SONGS,
            id = LIKED_SONGS_ID,
            uri = "vk:collection",
            name = likedSongsName,
            totalCount = likedCount(),
            pinned = true,
        )
        listOf(liked) + playlists.map { p ->
            if (p.isAlbum) {
                SpotifyLibraryEntry(
                    kind = SpotifyLibraryEntry.Kind.ALBUM,
                    id = playlistId(p),
                    uri = "vk:album:${p.ownerId}_${p.id}",
                    name = p.title,
                    creator = p.artist.ifBlank { null },
                    imageUrl = p.coverUrl,
                    albumType = albumType(p),
                    totalCount = p.count,
                    year = p.year,
                )
            } else {
                SpotifyLibraryEntry(
                    kind = SpotifyLibraryEntry.Kind.PLAYLIST,
                    id = playlistId(p),
                    uri = "vk:playlist:${p.ownerId}_${p.id}",
                    name = p.title,
                    creator = p.artist.ifBlank { null },
                    imageUrl = p.coverUrl,
                    totalCount = p.count,
                )
            }
        }
    }

    // ── likes and saved albums ──

    @Volatile
    private var appContext: android.content.Context? = null

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    /** A like on VK = the track in the user's "My music" (the copy's address is kept, see VkMusic). */
    suspend fun saveTrack(id: String): Result<Unit> = setSaved(id, true)

    suspend fun removeTrack(id: String): Result<Unit> = setSaved(id, false)

    private suspend fun setSaved(id: String, saved: Boolean): Result<Unit> {
        val fullId = fullIdOf(id) ?: return Result.failure(IllegalArgumentException("Not a VK track: $id"))
        val context = appContext ?: return Result.failure(IllegalStateException("VK catalog not ready"))
        if (!isAuthenticated) return Result.failure(IllegalStateException("VK account not linked"))
        return com.metrolist.music.resolver.VkMusic.setTrackSaved(context, fullId, saved).onSuccess { myTracksCache = null }
    }

    suspend fun isAlbumSaved(id: String): Result<Boolean> = runCatching {
        val a = parsePlaylistId(id) ?: return@runCatching false
        myPlaylists().any { it.ownerId == a.ownerId && it.id == a.id }
    }

    suspend fun saveAlbum(id: String): Result<Unit> = runCatching {
        val a = parsePlaylistId(id) ?: throw IllegalArgumentException("Not a VK album: $id")
        check(isAuthenticated) { "VK account not linked" }
        val p = playlistAt(a)
        if (!myPlaylists().any { it.ownerId == p.ownerId && it.id == p.id }) com.metrolist.music.resolver.VkMusic.toggleSaved(p).getOrThrow()
        myPlaylistsCache = null
    }

    suspend fun removeAlbum(id: String): Result<Unit> = runCatching {
        val a = parsePlaylistId(id) ?: throw IllegalArgumentException("Not a VK album: $id")
        check(isAuthenticated) { "VK account not linked" }
        val p = myPlaylists().firstOrNull { it.ownerId == a.ownerId && it.id == a.id } ?: return@runCatching
        com.metrolist.music.resolver.VkMusic.toggleSaved(p).getOrThrow()
        myPlaylistsCache = null
    }

    // ── search and recommendations ──

    suspend fun search(query: String, types: List<String>, limit: Int, offset: Int): Result<SpotifySearchResult> = runCatching {
        val p = provider ?: throw IllegalStateException("VK is off")
        val tracks = if ("track" in types) p.searchTracks(query, (offset + limit).coerceAtMost(200)).drop(offset).take(limit) else emptyList()
        val playlists = if ("album" in types || "playlist" in types) {
            p.searchPlaylists(query, 40).also { com.metrolist.music.resolver.VkMusic.remember(it) }
        } else {
            emptyList()
        }
        // Artists: the ones the found tracks name, with VK pages.
        val artists = if ("artist" in types) {
            (tracks.ifEmpty { p.searchTracks(query, 30) }).flatMap { it.artists }
                .filter { it.id != null && it.name.contains(query.trim(), ignoreCase = true) }
                .distinctBy { it.id }
                .take(limit)
                .map { SpotifyArtist(id = artistId(it.id!!), name = it.name) }
        } else {
            emptyList()
        }
        SpotifySearchResult(
            tracks = SpotifyPaging(items = tracks.map(::trackOf), total = tracks.size, limit = limit, offset = offset),
            albums = SpotifyPaging(items = playlists.filter { it.isAlbum }.map(::simpleAlbumOf), total = playlists.count { it.isAlbum }, limit = limit),
            playlists = SpotifyPaging(items = playlists.filterNot { it.isAlbum }.map(::playlistOf), total = playlists.count { !it.isAlbum }, limit = limit),
            artists = SpotifyPaging(items = artists, total = artists.size, limit = limit),
        )
    }

    @Volatile
    private var recommendationsCache: Pair<Long, List<SpotifyTrack>>? = null

    /** VK's "For you" for the user, a few hours fresh. */
    suspend fun recommendations(): List<SpotifyTrack> {
        recommendationsCache?.takeIf { System.currentTimeMillis() - it.first < RECOMMENDATIONS_TTL_MS }?.let { return it.second }
        val list = runCatching { provider?.recommendations(100).orEmpty().map(::trackOf) }.getOrDefault(emptyList())
        if (list.isNotEmpty()) recommendationsCache = System.currentTimeMillis() to list
        return list
    }

    // ── writing playlists ──

    /** Creates a playlist in the user's VK music; returns its catalog id. */
    suspend fun createPlaylist(title: String): Result<String> = runCatching {
        check(isAuthenticated) { "VK account not linked" }
        val p = provider?.createPlaylist(title) ?: throw IllegalStateException("VK refused to create the playlist")
        myPlaylistsCache = null
        playlistId(p)
    }

    /** Adds VK tracks (catalog ids) to one of the user's playlists. */
    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Result<Unit> = runCatching {
        val a = parsePlaylistId(playlistId) ?: throw IllegalArgumentException("Not a VK playlist: $playlistId")
        val audioIds = trackIds.mapNotNull { fullIdOf(it)?.split('_')?.take(2)?.joinToString("_") }
        provider?.addToPlaylist(a.ownerId, a.id, audioIds) ?: throw IllegalStateException("VK is off")
        playlistPages.remove(playlistId)
    }

    private const val LIBRARY_TTL_MS = 5 * 60_000L
    private const val PAGE_TTL_MS = 5 * 60_000L
    private const val RECOMMENDATIONS_TTL_MS = 3 * 60 * 60_000L
}
