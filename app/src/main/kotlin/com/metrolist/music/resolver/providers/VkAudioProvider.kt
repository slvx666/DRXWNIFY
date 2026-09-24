/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver.providers

import com.metrolist.music.resolver.AudioProvider
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.AudioQuery
import com.metrolist.music.resolver.AudioStream
import com.metrolist.music.resolver.ProviderMatch
import com.metrolist.music.playback.datasource.HlsConcatDataSource
import com.metrolist.spotify.SpotifyMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * VK Music: strong coverage of Russian-language music and part of the underground scene, 320 kbps MP3
 * for most licensed tracks. Limitations: needs the user's VK login (the audio API is not public), and
 * some tracks are region- or rights-restricted (they come back without a URL and are skipped).
 */
class VkAudioProvider(
    private val token: () -> String?,
    private val userId: () -> String? = { null },
) : AudioProvider {
    override val id = AudioProviderId.VK
    override val searchTimeoutMs = 15_000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(14, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun isReady(): Boolean = !token().isNullOrBlank()

    private class VkApiException(val code: Int, message: String) : Exception(message)

    // VK allows ~3 API calls per second per token; queues resolve many tracks at once.
    private val throttle = kotlinx.coroutines.sync.Mutex()
    private var lastCallAtMs = 0L

    private suspend fun <T> throttled(block: () -> T): T {
        throttle.withLock {
            val wait = lastCallAtMs + MIN_CALL_SPACING_MS - System.currentTimeMillis()
            if (wait > 0) kotlinx.coroutines.delay(wait)
            lastCallAtMs = System.currentTimeMillis()
        }
        return try {
            runInterruptible(Dispatchers.IO) { block() }
        } catch (e: VkApiException) {
            if (e.code != TOO_MANY_REQUESTS) throw e
            kotlinx.coroutines.delay(1_000)
            runInterruptible(Dispatchers.IO) { block() }
        }
    }

    private fun call(method: String, params: Map<String, String>): JSONObject? {
        val accessToken = token()?.takeIf { it.isNotBlank() } ?: return null
        val body = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
            add("access_token", accessToken)
            add("v", API_VERSION)
        }.build()
        val request = Request.Builder()
            .url("https://api.vk.com/method/$method")
            .header("User-Agent", CLIENT_USER_AGENT)
            .post(body)
            .build()
        Timber.tag("VkAuth").i("%s → sending %s", method, params)
        http.newCall(request).execute().use { response ->
            val text = response.body?.string()
            Timber.tag("VkAuth").i("%s → HTTP %d in %dms: %s", method, response.code, response.receivedResponseAtMillis - response.sentRequestAtMillis, text?.take(600))
            if (!response.isSuccessful) return null
            val root = JSONObject(text ?: return null)
            root.optJSONObject("error")?.let { err ->
                throw VkApiException(err.optInt("error_code"), "VK error ${err.optInt("error_code")}: ${err.optString("error_msg")}")
            }
            return root
        }
    }

    private data class VkAudio(
        val fullId: String,
        val title: String,
        val artist: String,
        val durationSec: Int,
        val url: String?,
        val thumb: String?,
    )

    private fun parse(o: JSONObject): VkAudio? {
        val id = o.optLong("id", 0L).takeIf { it != 0L } ?: return null
        val owner = o.optLong("owner_id", 0L).takeIf { it != 0L } ?: return null
        val accessKey = o.optString("access_key").takeIf { it.isNotBlank() }
        val subtitle = o.optString("subtitle").takeIf { it.isNotBlank() }
        val title = o.optString("title").let { t -> if (subtitle != null && !t.contains(subtitle, true)) "$t ($subtitle)" else t }
        val mainArtists = o.optJSONArray("main_artists")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        }.orEmpty()
        return VkAudio(
            fullId = listOfNotNull("${owner}_$id", accessKey).joinToString("_"),
            title = title,
            artist = mainArtists.joinToString(", ").ifBlank { o.optString("artist") },
            durationSec = o.optInt("duration", 0),
            url = o.optString("url").takeIf { it.isNotBlank() }?.let(::toMp3Url),
            thumb = o.optJSONObject("album")?.optJSONObject("thumb")?.optString("photo_300")?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun searchFree(text: String, limit: Int): List<ProviderMatch> {
        if (!isReady() || text.isBlank()) return emptyList()
        // Unlike the gated search this keeps tracks that came back without a stream URL: VK leaves
        // it out of search results for many accounts and hands it over on audio.getById instead,
        // which is exactly what stream() does. Dropping them here meant "VK found nothing at all".
        val items = runCatching { fetch(text, limit.coerceIn(1, 500), requireUrl = false) }
            .onFailure {
                Timber.tag("VkAuth").w(it, "search '%s' failed", text)
                com.metrolist.music.resolver.AudioDiagnostics.warn(
                    "source search VK ✘ '$text': ${it.message ?: it.javaClass.simpleName}",
                )
            }
            .getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        return items.take(limit).map { audio ->
            audio.url?.let { recentUrls[audio.fullId] = it to now }
            ProviderMatch(
                provider = id,
                trackId = audio.fullId,
                title = audio.title,
                artist = audio.artist,
                durationMs = audio.durationSec * 1000L,
                confidence = 0.0,
                thumbnailUrl = audio.thumb,
            )
        }
    }

    /**
     * Raw search results. VK leaves stream URLs out of search results for many accounts,
     * so [requireUrl] is false by default: the actual URL is fetched via audio.getById
     * when [stream] is called.
     */
    private suspend fun fetch(text: String, count: Int, requireUrl: Boolean = false): List<VkAudio> {
        // VK answers at most a few hundred per request and often fewer, so the results are paged
        // until [count] is reached or VK runs out (one page was why a search stopped at ~120).
        val raw = mutableListOf<VkAudio>()
        var offset = 0
        while (raw.size < count) {
            val pageSize = (count - raw.size).coerceAtMost(SEARCH_PAGE)
            val (page, total) = throttled {
                val root = call(
                    "audio.search",
                    mapOf(
                        "q" to text,
                        "count" to pageSize.toString(),
                        "offset" to offset.toString(),
                        "auto_complete" to "1",
                        "sort" to "2",
                    ),
                ) ?: throw IllegalStateException("VK API HTTP error")
                val response = root.optJSONObject("response")
                val arr = response?.optJSONArray("items")
                (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it)?.let(::parse) } to
                    (response?.optInt("count", 0) ?: 0)
            }
            raw += page
            offset += pageSize
            if (page.isEmpty() || offset >= total) break
        }
        // HLS (.m3u8) results are kept: that is how VK serves nearly every track to its official
        // client, and stream() plays them. Dropping them was why VK "found nothing at all".
        return if (requireUrl) raw.filter { it.url != null } else raw
    }

    override suspend fun search(query: AudioQuery): ProviderMatch? {
        if (!isReady()) return null
        val text = ProviderGate.searchText(query)
        if (text.isBlank()) return null
        // Errors (bad token, API refused) propagate so the audio search log shows the real reason.
        // VK leaves URLs out of search results for most accounts; stream() fetches them via audio.getById.
        val items = fetch(text, 30, requireUrl = false)
        if (items.isEmpty()) return null

        val chosen = ProviderGate.ranked(
            query,
            items.map { SpotifyMapper.Candidate(it.fullId, it.title, it.artist, it.durationSec.takeIf { d -> d > 0 }, thumbnailUrl = it.thumb) },
            limit = 1,
        ).firstOrNull() ?: return null
        val audio = items.first { it.fullId == chosen.id }
        audio.url?.let { recentUrls[audio.fullId] = it to System.currentTimeMillis() }
        return ProviderMatch(
            provider = id,
            trackId = audio.fullId,
            title = audio.title,
            artist = audio.artist,
            durationMs = audio.durationSec * 1000L,
            confidence = chosen.score,
            thumbnailUrl = audio.thumb,
        )
    }

    // ── Albums & playlists ───────────────────────────────────────────────────────────────────────

    /** Albums and playlists matching [text] (VK's own "albums" search). */
    suspend fun searchPlaylists(text: String, count: Int = 60): List<VkPlaylist> {
        if (!isReady() || text.isBlank()) return emptyList()
        return throttled {
            val root = call("audio.searchPlaylists", mapOf("q" to text, "count" to count.toString()))
                ?: throw IllegalStateException("VK API HTTP error")
            parsePlaylists(root.optJSONObject("response")?.optJSONArray("items"))
        }
    }

    /**
     * The account's own playlists — including every album the user added in VK itself; those
     * point at the original, which is where their tracks are read from.
     */
    suspend fun myPlaylists(): List<VkPlaylist> {
        if (!isReady()) return emptyList()
        val owner = ownerId() ?: return emptyList()
        val all = mutableListOf<VkPlaylist>()
        var offset = 0
        while (all.size < MAX_PLAYLISTS) {
            val (page, total) = throttled {
                val root = call(
                    "audio.getPlaylists",
                    mapOf("owner_id" to owner, "count" to PLAYLIST_PAGE.toString(), "offset" to offset.toString()),
                ) ?: throw IllegalStateException("VK API HTTP error")
                val response = root.optJSONObject("response")
                parsePlaylists(response?.optJSONArray("items")) to (response?.optInt("count", 0) ?: 0)
            }
            all += page
            offset += PLAYLIST_PAGE
            if (page.isEmpty() || offset >= total) break
        }
        return all
    }

    /** The account's own tracks ("My music"), newest first. */
    suspend fun myTracks(): List<ProviderMatch> {
        if (!isReady()) return emptyList()
        val owner = ownerId() ?: return emptyList()
        val all = mutableListOf<VkAudio>()
        var offset = 0
        while (all.size < MAX_MY_TRACKS) {
            val (page, total) = throttled {
                val root = call(
                    "audio.get",
                    mapOf("owner_id" to owner, "count" to TRACKS_PAGE.toString(), "offset" to offset.toString()),
                ) ?: throw IllegalStateException("VK API HTTP error")
                val response = root.optJSONObject("response")
                val arr = response?.optJSONArray("items")
                (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it)?.let(::parse) } to
                    (response?.optInt("count", 0) ?: 0)
            }
            all += page
            offset += TRACKS_PAGE
            if (page.isEmpty() || offset >= total) break
        }
        val now = System.currentTimeMillis()
        return all.distinctBy { it.fullId }.map { audio ->
            audio.url?.let { recentUrls[audio.fullId] = it to now }
            ProviderMatch(
                provider = id,
                trackId = audio.fullId,
                title = audio.title,
                artist = audio.artist,
                durationMs = audio.durationSec * 1000L,
                confidence = 0.0,
                thumbnailUrl = audio.thumb,
            )
        }
    }

    /** Every track of [playlist], as playable matches (a missing stream URL is fetched on play). */
    suspend fun playlistTracks(playlist: VkPlaylist): List<ProviderMatch> {
        if (!isReady()) return emptyList()
        val items = throttled {
            val params = buildMap {
                put("owner_id", playlist.ownerId.toString())
                put("album_id", playlist.id.toString())
                put("count", MAX_PLAYLIST_TRACKS.toString())
                playlist.accessKey?.let { put("access_key", it) }
            }
            val root = call("audio.get", params) ?: throw IllegalStateException("VK API HTTP error")
            val arr = root.optJSONObject("response")?.optJSONArray("items")
            (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it)?.let(::parse) }
        }
        val now = System.currentTimeMillis()
        return items.distinctBy { it.fullId }.map { audio ->
            audio.url?.let { recentUrls[audio.fullId] = it to now }
            ProviderMatch(
                provider = id,
                trackId = audio.fullId,
                title = audio.title,
                artist = audio.artist,
                durationMs = audio.durationSec * 1000L,
                confidence = 0.0,
                // A track inside an album often has no cover of its own; the album's is the right one.
                thumbnailUrl = audio.thumb ?: playlist.coverUrl,
            )
        }
    }

    /**
     * Adds [playlist] to the user's VK music (what "Add to my music" does in VK). Returns the
     * address of the copy in the user's list, which is what removing it again needs.
     */
    suspend fun follow(playlist: VkPlaylist): Pair<Long, Long> {
        val me = myId() ?: 0L
        return throttled {
        val params = buildMap {
            put("owner_id", playlist.ownerId.toString())
            put("playlist_id", playlist.id.toString())
            playlist.accessKey?.let { put("access_key", it) }
        }
        val root = call("audio.followPlaylist", params) ?: throw IllegalStateException("VK API HTTP error")
        // The answer names the copy in the user's list: {playlist_id, owner_id} or a playlist object.
        val response = root.optJSONObject("response")
        val copyOwner = response?.optLong("owner_id", 0L)?.takeIf { it != 0L } ?: me
        val copyId = response?.optLong("playlist_id", 0L)?.takeIf { it != 0L }
            ?: response?.optLong("id", 0L)?.takeIf { it != 0L }
            ?: 0L
        copyOwner to copyId
        }
    }

    /**
     * Adds a track to the user's VK music ("My music"), as the "+" in VK does. [fullId] is
     * "owner_audio[_accessKey]"; returns the address (owner, id) of the added copy.
     */
    suspend fun addTrack(fullId: String): Pair<Long, Long> {
        val parts = fullId.split('_')
        require(parts.size >= 2) { "bad VK track id" }
        val me = myId() ?: 0L
        return throttled {
            val params = buildMap {
                put("owner_id", parts[0])
                put("audio_id", parts[1])
                parts.getOrNull(2)?.let { put("access_key", it) }
            }
            val root = call("audio.add", params) ?: throw IllegalStateException("VK API HTTP error")
            me to root.optLong("response", 0L)
        }
    }

    /** Removes a track from the user's VK music by the address of the user's copy. */
    suspend fun deleteTrack(ownerId: Long, audioId: Long) {
        throttled {
            call("audio.delete", mapOf("owner_id" to ownerId.toString(), "audio_id" to audioId.toString()))
                ?: throw IllegalStateException("VK API HTTP error")
        }
    }

    /** Removes the user's copy of an added album/playlist. */
    suspend fun unfollow(libraryOwnerId: Long, libraryId: Long) {
        throttled {
            call(
                "audio.deletePlaylist",
                mapOf("owner_id" to libraryOwnerId.toString(), "playlist_id" to libraryId.toString()),
            ) ?: throw IllegalStateException("VK API HTTP error")
        }
    }

    /** The signed-in user's VK id, or null when unknown. */
    suspend fun myId(): Long? = ownerId()?.toLongOrNull()

    /** The signed-in user's id: saved at login, else asked from VK once. */
    private suspend fun ownerId(): String? {
        (userId()?.takeIf { it.isNotBlank() } ?: knownOwnerId)?.let { return it }
        return runCatching {
            throttled {
                call("users.get", emptyMap())?.optJSONArray("response")?.optJSONObject(0)
                    ?.optLong("id", 0L)?.takeIf { it != 0L }?.toString()
            }
        }.getOrNull()?.also { knownOwnerId = it }
    }

    @Volatile
    private var knownOwnerId: String? = null

    private fun parsePlaylists(arr: org.json.JSONArray?): List<VkPlaylist> =
        (0 until (arr?.length() ?: 0)).mapNotNull { index ->
            val o = arr?.optJSONObject(index) ?: return@mapNotNull null
            // An album added to "my music" is a reference: its tracks live in the original.
            val original = o.optJSONObject("original")
            val ownerId = original?.optLong("owner_id", 0L)?.takeIf { it != 0L } ?: o.optLong("owner_id", 0L)
            val playlistId = original?.optLong("playlist_id", 0L)?.takeIf { it != 0L } ?: o.optLong("id", 0L)
            if (ownerId == 0L || playlistId == 0L) return@mapNotNull null
            val accessKey = (original?.optString("access_key") ?: o.optString("access_key")).takeIf { !it.isNullOrBlank() }
            val artists = o.optJSONArray("main_artists")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
            }.orEmpty()
            val cover = o.optJSONObject("photo")?.optString("photo_600")?.takeIf { it.isNotBlank() }
                ?: o.optJSONObject("photo")?.optString("photo_300")?.takeIf { it.isNotBlank() }
                ?: o.optJSONArray("thumbs")?.optJSONObject(0)?.let { t ->
                    t.optString("photo_600").ifBlank { t.optString("photo_300") }
                }?.takeIf { it.isNotBlank() }
            VkPlaylist(
                ownerId = ownerId,
                id = playlistId,
                accessKey = accessKey,
                title = o.optString("title"),
                artist = artists.joinToString(", "),
                count = o.optInt("count", 0),
                coverUrl = cover,
                year = o.optInt("year", 0).takeIf { it > 0 },
                isAlbum = isAlbum(o, ownerId, artists),
                libraryOwnerId = if (original != null) o.optLong("owner_id", 0L).takeIf { it != 0L } else null,
                libraryId = if (original != null) o.optLong("id", 0L).takeIf { it != 0L } else null,
            )
        }
            // VK repeats an album in its results now and then; one entry per album (a repeat also
            // crashed the list, whose rows are keyed by album).
            .distinctBy { it.key }

    /**
     * Album or playlist. VK marks albums with `album_type` (or `type` 1). A reference in the
     * user's list may lack both, so the owner decides: releases belong to VK's music catalog (a
     * negative, community owner) and carry artists and a year; playlists — the user's own or anyone
     * else's — are owned by people. An editorial VK playlist has a community owner but no year.
     */
    private fun isAlbum(o: JSONObject, ownerId: Long, artists: List<String>): Boolean {
        // Made by a person (the user or anyone else): a playlist, whatever else the answer says.
        if (ownerId > 0) return false
        val albumType = o.optString("album_type")
        if (albumType.isNotBlank() && !albumType.equals("playlist", ignoreCase = true)) return true
        if (o.optInt("type", 0) == 1) return true
        return artists.isNotEmpty() && o.optInt("year", 0) > 0
    }

    private val recentUrls = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    override suspend fun stream(query: AudioQuery, match: ProviderMatch): AudioStream? {
        val now = System.currentTimeMillis()
        val url = recentUrls[match.trackId]?.takeIf { now - it.second < URL_TTL_MS }?.first
            ?: runCatching {
                throttled {
                    val root = call("audio.getById", mapOf("audios" to match.trackId))
                    val arr = root?.optJSONArray("response")
                    arr?.optJSONObject(0)?.let(::parse)?.url
                }
            }.onFailure { Timber.tag("AudioRace").d("vk stream ✘ %s: %s", match.trackId, it.message) }
                .getOrNull()
                ?.also { recentUrls[match.trackId] = it to now }
            ?: return null
        return AudioStream(
            // VK's HLS is AES-128 encrypted MPEG-TS: fetched whole, decrypted and demuxed to plain MP3.
            uri = if (url.contains(".m3u8")) HlsConcatDataSource.wrap(url, whole = true) else url,
            mimeType = "audio/mpeg",
            codecs = "mp3",
            bitrate = 320_000,
            sampleRate = 44_100,
            expiresAtMs = now + URL_TTL_MS,
        )
    }

    companion object {
        /**
         * Asks VK for one track with [token], so "VK finds nothing" stops being a guess.
         *
         * The usual cause is the token itself: VK only serves the audio API to the Kate Mobile
         * client, and only to a token from the login+password flow — the one taken from VK's own
         * web page (implicit grant) can read the profile but is refused by audio.* with error 15.
         */
        suspend fun checkAudioAccess(token: String): VkAudioAccess = withContext(Dispatchers.IO) {
            if (token.isBlank()) return@withContext VkAudioAccess.NoToken
            val http = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val body = FormBody.Builder()
                .add("q", "test")
                .add("count", "1")
                .add("access_token", token)
                .add("v", API_VERSION)
                .build()
            val request = Request.Builder()
                .url("https://api.vk.com/method/audio.search")
                .header("User-Agent", CLIENT_USER_AGENT)
                .post(body)
                .build()
            runCatching {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful && text.isBlank()) {
                        return@use VkAudioAccess.Failed(null, "HTTP ${response.code}")
                    }
                    val root = JSONObject(text)
                    root.optJSONObject("error")?.let { err ->
                        return@use VkAudioAccess.Failed(
                            err.optInt("error_code"),
                            err.optString("error_msg").ifBlank { "unknown error" },
                        )
                    }
                    VkAudioAccess.Ok(root.optJSONObject("response")?.optInt("count", 0) ?: 0)
                }
            }.getOrElse { VkAudioAccess.Failed(null, it.message ?: it.javaClass.simpleName) }
                .also { Timber.tag("VkAuth").i("audio access check: %s", it) }
        }

        /** The API version the official Android client speaks; its token and its version go together. */
        const val API_VERSION = "5.116"

        /**
         * VK's official Android client. Kate Mobile no longer gets a music token (VK hands its
         * "refreshed" token back unchanged, and every audio method answers error 3); a token issued
         * to the official client is served the audio API directly, with no extra exchange.
         */
        const val OAUTH_CLIENT_ID = "2274003"
        const val OAUTH_REDIRECT = "https://oauth.vk.com/blank.html"
        const val OAUTH_URL = "https://oauth.vk.com/authorize?client_id=$OAUTH_CLIENT_ID" +
            "&display=mobile&redirect_uri=$OAUTH_REDIRECT&scope=all&response_type=token&v=$API_VERSION"

        /** Requests must look like the client the token belongs to, or VK refuses them. */
        const val CLIENT_USER_AGENT =
            "VKAndroidApp/5.52-4543 (Android 5.1.1; SDK 22; x86_64; unknown Android SDK built for x86_64; en; 320x240)"

        private const val URL_TTL_MS = 60 * 60 * 1000L
        private const val SEARCH_PAGE = 200
        private const val PLAYLIST_PAGE = 100
        private const val MAX_PLAYLISTS = 1000
        private const val MAX_PLAYLIST_TRACKS = 1000
        private const val TRACKS_PAGE = 500
        private const val MAX_MY_TRACKS = 5000
        private const val MIN_CALL_SPACING_MS = 350L
        private const val TOO_MANY_REQUESTS = 6

        private val M3U8_TO_MP3 = Regex("/[0-9a-f]+(/audios)?/([0-9a-f]+)/index\\.m3u8")

        /** VK hands out HLS playlists for most tracks; the same file exists as a plain MP3 next to it. */
        fun toMp3Url(url: String): String =
            if (url.contains("index.m3u8")) M3U8_TO_MP3.replace(url) { m -> "${m.groupValues[1]}/${m.groupValues[2]}.mp3" } else url
    }
}

/** Outcome of [VkAudioProvider.checkAudioAccess]. */
sealed interface VkAudioAccess {
    /** VK answered; [total] is how many tracks it says it has for the test query. */
    data class Ok(val total: Int) : VkAudioAccess
    data class Failed(val code: Int?, val message: String) : VkAudioAccess
    data object NoToken : VkAudioAccess
}

/** A VK album or playlist; [ownerId]/[id]/[accessKey] address its tracks. */
data class VkPlaylist(
    val ownerId: Long,
    val id: Long,
    val accessKey: String?,
    val title: String,
    val artist: String,
    val count: Int,
    val coverUrl: String?,
    val year: Int?,
    val isAlbum: Boolean,
    /** Where this album sits in the user's own list when it was added there (its copy, not the original). */
    val libraryOwnerId: Long? = null,
    val libraryId: Long? = null,
) {
    /** Stable key used in navigation and caches. */
    val key: String get() = "${ownerId}_$id"
}
