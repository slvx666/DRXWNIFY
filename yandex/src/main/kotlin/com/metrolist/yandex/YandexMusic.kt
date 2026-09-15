package com.metrolist.yandex

import com.metrolist.spotify.ArtistTopTracksResponse
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyLibraryEntry
import com.metrolist.spotify.models.SpotifyPaging
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyPlaylistTrack
import com.metrolist.spotify.models.SpotifySavedTrack
import com.metrolist.spotify.models.SpotifySearchResult
import com.metrolist.spotify.models.SpotifyTrack
import com.metrolist.yandex.YandexParser.arr
import com.metrolist.yandex.YandexParser.obj
import com.metrolist.yandex.YandexParser.objOrNull
import com.metrolist.yandex.YandexParser.str
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.TimeUnit

/**
 * Minimal client for the (unofficial) Yandex Music API — the one the official Android app talks to.
 * Used ONLY as a metadata + library source: liked tracks, playlists, albums, artists and search.
 * Audio is never taken from here; Meld's audio fallback layer finds the stream elsewhere.
 *
 * Authentication is an OAuth token obtained through Yandex's standard implicit-grant login page
 * (see the app's Yandex login screen). All results are mapped onto the shared catalog models with
 * `ym_`-prefixed ids ([YandexIds]).
 */
object YandexMusic {
    private const val BASE_URL = "https://api.music.yandex.net"

    /** Client id of the official Yandex Music Android app, used by the OAuth implicit flow. */
    const val OAUTH_CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d"
    const val OAUTH_URL =
        "https://oauth.yandex.ru/authorize?response_type=token&client_id=$OAUTH_CLIENT_ID"

    @Volatile
    var accessToken: String? = null

    /** Numeric account uid; every /users/{uid}/… endpoint needs it. Resolved lazily from the token. */
    @Volatile
    var uid: String? = null
        set(value) {
            if (value != field) likedRefsCache = null
            field = value
        }

    class YandexException(val statusCode: Int, message: String) : Exception(message)

    data class Account(val uid: String, val login: String?, val displayName: String?)

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(10, TimeUnit.SECONDS)
                    readTimeout(20, TimeUnit.SECONDS)
                    writeTimeout(10, TimeUnit.SECONDS)
                }
            }
            expectSuccess = false
        }
    }

    fun isAuthenticated(): Boolean = !accessToken.isNullOrBlank()

    // ── Core ─────────────────────────────────────────────────────────────────────────────────────

    private fun io.ktor.client.request.HttpRequestBuilder.commonHeaders() {
        val token = accessToken ?: throw YandexException(401, "Not authenticated")
        header("Authorization", "OAuth $token")
        header("X-Yandex-Music-Client", "YandexMusicAndroid/24023621")
        header("User-Agent", "Yandex-Music-API")
        header("Accept-Language", "ru")
    }

    private suspend fun unwrap(response: HttpResponse): JsonElement {
        val text = response.bodyAsText()
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        if (response.status.value !in 200..299 || root == null) {
            val message = root?.obj("error")?.let { it.str("message") ?: it.str("name") }
                ?: "HTTP ${response.status.value}"
            throw YandexException(response.status.value, message)
        }
        root.obj("error")?.let { throw YandexException(400, it.str("message") ?: it.str("name") ?: "error") }
        return root["result"] ?: throw YandexException(500, "Empty result")
    }

    private suspend fun getResult(path: String, params: Map<String, String> = emptyMap()): JsonElement =
        unwrap(
            client.get("$BASE_URL/$path") {
                commonHeaders()
                params.forEach { (k, v) -> parameter(k, v) }
            },
        )

    private suspend fun postForm(path: String, form: Map<String, String>): JsonElement =
        unwrap(
            client.post("$BASE_URL/$path") {
                commonHeaders()
                setBody(FormDataContent(Parameters.build { form.forEach { (k, v) -> append(k, v) } }))
            },
        )

    private suspend fun requireUid(): String = uid ?: account().getOrThrow().uid.also { uid = it }

    // ── Account ──────────────────────────────────────────────────────────────────────────────────

    suspend fun account(): Result<Account> = runCatching {
        val result = getResult("account/status") as? JsonObject
            ?: throw YandexException(500, "Invalid account/status")
        val account = result.obj("account") ?: throw YandexException(401, "No account in status")
        val id = account.str("uid") ?: throw YandexException(401, "Token is not bound to an account")
        Account(
            uid = id,
            login = account.str("login"),
            displayName = account.str("displayName") ?: account.str("fullName") ?: account.str("login"),
        ).also { uid = it.uid }
    }

    // ── Tracks ───────────────────────────────────────────────────────────────────────────────────

    /** Full track objects for raw ids (optionally `id:albumId`). Chunked; order preserved. */
    suspend fun tracks(requestIds: List<String>): Result<List<SpotifyTrack>> = runCatching {
        if (requestIds.isEmpty()) return@runCatching emptyList()
        coroutineScope {
            requestIds.chunked(TRACKS_CHUNK).map { chunk ->
                async {
                    val arr = postForm("tracks", mapOf("track-ids" to chunk.joinToString(","), "with-positions" to "true"))
                        as? JsonArray
                    arr.orEmpty().mapNotNull { it.objOrNull()?.let(YandexParser::track) }
                }
            }.awaitAll().flatten()
        }
    }

    suspend fun getTrack(trackId: String): Result<SpotifyTrack> = runCatching {
        tracks(listOf(YandexIds.raw(trackId))).getOrThrow().firstOrNull()
            ?: throw YandexException(404, "Track not found")
    }

    @Volatile
    private var likedRefsCache: Pair<Long, List<YandexParser.TrackRef>>? = null

    /**
     * Liked-track references, newest first. Cheap (ids only) and cached briefly, because paged screens
     * ask for several pages of the same list at once.
     */
    suspend fun likedTrackRefs(): Result<List<YandexParser.TrackRef>> = runCatching {
        likedRefsCache?.takeIf { System.currentTimeMillis() - it.first < LIKED_REFS_TTL_MS }?.let { return@runCatching it.second }
        val result = getResult("users/${requireUid()}/likes/tracks", mapOf("if-modified-since-revision" to "0"))
            as? JsonObject ?: throw YandexException(500, "Invalid likes payload")
        YandexParser.likedTrackRefs(result).also { likedRefsCache = System.currentTimeMillis() to it }
    }

    /** One page of liked tracks in the Spotify saved-tracks shape. */
    suspend fun likedSongs(limit: Int, offset: Int): Result<SpotifyPaging<SpotifySavedTrack>> = runCatching {
        val refs = likedTrackRefs().getOrThrow()
        val page = refs.drop(offset).take(limit)
        val byId = tracks(page.map { it.requestId }).getOrThrow().associateBy { it.id }
        SpotifyPaging(
            items = page.mapNotNull { ref ->
                byId[YandexIds.track(ref.id)]?.let { SpotifySavedTrack(addedAt = ref.timestamp, track = it) }
            },
            total = refs.size,
            limit = limit,
            offset = offset,
        )
    }

    /** Every liked track (used when merging libraries). */
    suspend fun allLikedSongs(): Result<List<SpotifySavedTrack>> = runCatching {
        val refs = likedTrackRefs().getOrThrow()
        val byId = tracks(refs.map { it.requestId }).getOrThrow().associateBy { it.id }
        refs.mapNotNull { ref -> byId[YandexIds.track(ref.id)]?.let { SpotifySavedTrack(ref.timestamp, it) } }
    }

    suspend fun setTrackLiked(trackId: String, liked: Boolean): Result<Unit> = likeAction("track", trackId, liked)

    suspend fun setAlbumLiked(albumId: String, liked: Boolean): Result<Unit> = likeAction("album", albumId, liked)

    suspend fun setArtistLiked(artistId: String, liked: Boolean): Result<Unit> = likeAction("artist", artistId, liked)

    private suspend fun likeAction(type: String, id: String, add: Boolean): Result<Unit> = runCatching {
        val action = if (add) "add-multiple" else "remove"
        postForm("users/${requireUid()}/likes/${type}s/$action", mapOf("$type-ids" to YandexIds.raw(id)))
        if (type == "track") likedRefsCache = null
        Unit
    }

    suspend fun isAlbumLiked(albumId: String): Result<Boolean> = runCatching {
        likedAlbums().getOrThrow().any { it.id == albumId }
    }

    suspend fun isArtistLiked(artistId: String): Result<Boolean> = runCatching {
        likedArtists().getOrThrow().any { it.first.id == artistId }
    }

    // ── Albums / artists / playlists ─────────────────────────────────────────────────────────────

    suspend fun album(albumId: String): Result<SpotifyAlbum> = runCatching {
        val obj = getResult("albums/${YandexIds.raw(albumId)}/with-tracks") as? JsonObject
            ?: throw YandexException(404, "Album not found")
        YandexParser.album(obj) ?: throw YandexException(500, "Invalid album payload")
    }

    suspend fun artist(artistId: String): Result<SpotifyArtist> = runCatching {
        val obj = getResult("artists/${YandexIds.raw(artistId)}/brief-info") as? JsonObject
            ?: throw YandexException(404, "Artist not found")
        obj.obj("artist")?.let(YandexParser::fullArtist) ?: throw YandexException(500, "Invalid artist payload")
    }

    suspend fun artistTopTracks(artistId: String): Result<ArtistTopTracksResponse> = runCatching {
        val obj = getResult("artists/${YandexIds.raw(artistId)}/brief-info") as? JsonObject
            ?: throw YandexException(404, "Artist not found")
        ArtistTopTracksResponse(
            tracks = obj.arr("popularTracks").orEmpty().mapNotNull { it.objOrNull()?.let(YandexParser::track) },
        )
    }

    /** Every release of the artist (albums, singles, EPs), newest first. */
    suspend fun artistAlbums(artistId: String): Result<List<SpotifyAlbum>> = runCatching {
        val raw = YandexIds.raw(artistId)
        val direct = runCatching {
            (getResult("artists/$raw/direct-albums", mapOf("page" to "0", "page-size" to "200", "sort-by" to "year")) as? JsonObject)
                ?.arr("albums").orEmpty().mapNotNull { it.objOrNull()?.let(YandexParser::album) }
        }.getOrDefault(emptyList())
        direct.ifEmpty {
            val obj = getResult("artists/$raw/brief-info") as? JsonObject
                ?: throw YandexException(404, "Artist not found")
            obj.arr("albums").orEmpty().mapNotNull { it.objOrNull()?.let(YandexParser::album) }
        }
    }

    suspend fun similarArtists(artistId: String): Result<List<SpotifyArtist>> = runCatching {
        val obj = getResult("artists/${YandexIds.raw(artistId)}/similar") as? JsonObject
            ?: throw YandexException(404, "Artist not found")
        obj.arr("similarArtists").orEmpty().mapNotNull { it.objOrNull()?.let(YandexParser::fullArtist) }
    }

    suspend fun myPlaylists(): Result<List<SpotifyPlaylist>> = runCatching {
        (getResult("users/${requireUid()}/playlists/list") as? JsonArray).orEmpty()
            .mapNotNull { it.objOrNull()?.let(YandexParser::playlist) }
    }

    suspend fun playlist(playlistId: String): Result<SpotifyPlaylist> = runCatching {
        val (owner, kind) = YandexIds.parsePlaylist(playlistId) ?: throw YandexException(400, "Bad playlist id")
        val obj = getResult("users/$owner/playlists/$kind") as? JsonObject
            ?: throw YandexException(404, "Playlist not found")
        YandexParser.playlist(obj) ?: throw YandexException(500, "Invalid playlist payload")
    }

    /**
     * Playlist tracks. The playlist payload carries every track reference; full track objects are
     * included for most playlists, otherwise they are fetched through `/tracks` for the requested page.
     */
    suspend fun playlistTracks(playlistId: String, limit: Int, offset: Int): Result<SpotifyPaging<SpotifyPlaylistTrack>> =
        runCatching {
            val (owner, kind) = YandexIds.parsePlaylist(playlistId) ?: throw YandexException(400, "Bad playlist id")
            val obj = getResult("users/$owner/playlists/$kind", mapOf("rich-tracks" to "true")) as? JsonObject
                ?: throw YandexException(404, "Playlist not found")
            val refs = obj.arr("tracks").orEmpty().mapNotNull { it.objOrNull() }
            val page = refs.drop(offset).take(limit)
            val missing = page.filter { it.obj("track") == null }.mapNotNull { ref ->
                val id = ref.str("id") ?: return@mapNotNull null
                ref.str("albumId")?.let { "$id:$it" } ?: id
            }
            val fetched = if (missing.isEmpty()) emptyMap() else tracks(missing).getOrThrow().associateBy { it.id }
            val items = page.mapNotNull { ref ->
                val track = ref.obj("track")?.let(YandexParser::track)
                    ?: ref.str("id")?.let { fetched[YandexIds.track(it)] }
                    ?: return@mapNotNull null
                SpotifyPlaylistTrack(addedAt = ref.str("timestamp"), track = track)
            }
            SpotifyPaging(items = items, total = refs.size, limit = limit, offset = offset)
        }

    suspend fun likedAlbums(): Result<List<SpotifyAlbum>> = runCatching {
        (getResult("users/${requireUid()}/likes/albums", mapOf("rich" to "true")) as? JsonArray).orEmpty()
            .mapNotNull { el -> el.objOrNull()?.let { o -> (o.obj("album") ?: o).let(YandexParser::album) } }
    }

    /** Liked artists with their like timestamps (when provided). */
    suspend fun likedArtists(): Result<List<Pair<SpotifyArtist, String?>>> = runCatching {
        (getResult("users/${requireUid()}/likes/artists", mapOf("with-timestamps" to "true")) as? JsonArray).orEmpty()
            .mapNotNull { el ->
                val o = el.objOrNull() ?: return@mapNotNull null
                val artistObj = o.obj("artist") ?: o
                YandexParser.fullArtist(artistObj)?.let { it to o.str("timestamp") }
            }
    }

    /**
     * The whole library as Spotify-style library entries: Liked Songs first, then playlists, albums and
     * artists. [likedSongsName] is the localized pseudo-playlist title.
     */
    suspend fun library(likedSongsName: String): Result<List<SpotifyLibraryEntry>> = runCatching {
        coroutineScope {
            val likes = async { likedTrackRefs().getOrNull() }
            val playlists = async { myPlaylists().getOrNull().orEmpty() }
            val albums = async {
                runCatching {
                    (getResult("users/${requireUid()}/likes/albums", mapOf("rich" to "true")) as? JsonArray).orEmpty()
                        .mapNotNull { el ->
                            val o = el.objOrNull() ?: return@mapNotNull null
                            val albumObj = o.obj("album") ?: o
                            YandexParser.simpleAlbum(albumObj)?.let { a ->
                                YandexParser.albumEntry(
                                    a,
                                    addedAt = o.str("timestamp"),
                                    totalCount = albumObj.str("trackCount")?.toIntOrNull() ?: 0,
                                )
                            }
                        }
                }.getOrDefault(emptyList())
            }
            val artists = async { likedArtists().getOrNull().orEmpty() }

            val likeRefs = likes.await()
            buildList {
                if (likeRefs != null) {
                    add(
                        SpotifyLibraryEntry(
                            kind = SpotifyLibraryEntry.Kind.LIKED_SONGS,
                            id = YandexIds.LIKED_SONGS_ID,
                            uri = "yandexmusic:collection:tracks",
                            name = likedSongsName,
                            pinned = true,
                            totalCount = likeRefs.size,
                        ),
                    )
                }
                // The "Мне нравится" playlist (kind 3) duplicates Liked Songs.
                playlists.await().filter { YandexIds.parsePlaylist(it.id)?.second != "3" }
                    .forEach { add(YandexParser.playlistEntry(it)) }
                addAll(albums.await())
                artists.await().forEach { (a, ts) -> add(YandexParser.artistEntry(a, ts)) }
            }
        }
    }

    // ── Search ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Search mapped onto the Spotify search result shape. [types] uses Spotify's type names
     * ("track", "album", "artist", "playlist"); several types → Yandex "all".
     */
    suspend fun search(query: String, types: List<String>, limit: Int, offset: Int): Result<SpotifySearchResult> =
        runCatching {
            val yType = if (types.size == 1) types.first() else "all"
            val page = if (limit > 0) offset / limit else 0
            val obj = getResult(
                "search",
                mapOf("text" to query, "type" to yType, "page" to page.toString(), "nocorrect" to "false"),
            ) as? JsonObject ?: throw YandexException(500, "Invalid search payload")

            fun <T> section(key: String, map: (JsonObject) -> T?): SpotifyPaging<T>? {
                val sec = obj.obj(key) ?: return null
                val items = sec.arr("results").orEmpty().mapNotNull { it.objOrNull()?.let(map) }.take(limit)
                val total = sec.str("total")?.toIntOrNull() ?: items.size
                return SpotifyPaging(items = items, total = total, limit = limit, offset = offset)
            }

            SpotifySearchResult(
                tracks = if ("track" in types) section("tracks", YandexParser::track) else null,
                albums = if ("album" in types) section("albums", YandexParser::album) else null,
                artists = if ("artist" in types) section("artists", YandexParser::fullArtist) else null,
                playlists = if ("playlist" in types) section("playlists", YandexParser::playlist) else null,
            )
        }

    private const val TRACKS_CHUNK = 200
    private const val LIKED_REFS_TTL_MS = 30_000L
}
