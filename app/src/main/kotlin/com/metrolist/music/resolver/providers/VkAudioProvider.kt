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
        val items = runCatching { fetch(text, limit.coerceIn(1, 300), requireUrl = false) }
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
        val raw = throttled {
            val root = call("audio.search", mapOf("q" to text, "count" to count.toString(), "auto_complete" to "1", "sort" to "2"))
                ?: throw IllegalStateException("VK API HTTP error")
            val arr = root.optJSONObject("response")?.optJSONArray("items")
            (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it)?.let(::parse) }
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
