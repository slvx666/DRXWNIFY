/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver.providers

import com.metrolist.music.playback.datasource.HlsConcatDataSource
import com.metrolist.music.resolver.AudioProvider
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.AudioQuery
import com.metrolist.music.resolver.AudioStream
import com.metrolist.music.resolver.ProviderMatch
import com.metrolist.spotify.SpotifyMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * SoundCloud: underground, remixes and rare releases that never reach the big stores. Limitations:
 * uploads are user-made (strict matching rejects re-uploads with other titles/lengths), Go+ tracks are
 * 30 s previews and are skipped, quality is lossy (~128–160 kbps).
 *
 * Uses SoundCloud's public web API with the client id the web player itself uses (scraped from
 * soundcloud.com and cached; refreshed automatically when it rotates).
 */
class SoundCloudAudioProvider(
    private val loadClientId: () -> String?,
    private val saveClientId: (String) -> Unit,
) : AudioProvider {
    override val id = AudioProviderId.SOUNDCLOUD
    override val searchTimeoutMs = 9_000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val clientIdMutex = Mutex()

    @Volatile
    private var clientId: String? = null

    private class AuthException : Exception("SoundCloud client id rejected")

    private suspend fun currentClientId(forceRefresh: Boolean = false): String? = clientIdMutex.withLock {
        if (!forceRefresh) {
            clientId?.let { return it }
            loadClientId()?.takeIf { it.isNotBlank() }?.let { clientId = it; return it }
        }
        val scraped = runInterruptible(Dispatchers.IO) { scrapeClientId() }
        if (scraped != null) {
            clientId = scraped
            saveClientId(scraped)
        }
        scraped
    }

    /** The web player's bundles embed `client_id:"<32 chars>"`; newest bundles are listed last. */
    private fun scrapeClientId(): String? = runCatching {
        val html = get("https://soundcloud.com/") ?: return null
        val scripts = Regex("""<script[^>]+src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js)"""")
            .findAll(html).map { it.groupValues[1] }.toList().asReversed()
        val idPattern = Regex("""client_id\s*[:=]\s*"?([0-9a-zA-Z]{32})""")
        for (src in scripts) {
            val js = get(src) ?: continue
            idPattern.find(js)?.groupValues?.get(1)?.let { return it }
        }
        null
    }.onFailure { Timber.tag("AudioRace").d("soundcloud client id scrape failed: %s", it.message) }.getOrNull()

    private fun get(url: String): String? {
        val request = Request.Builder().url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw AuthException()
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    /** Runs an API call, refreshing the scraped client id once if SoundCloud rejects it. */
    private suspend fun <T> withClientId(block: (String) -> T): T? {
        val cid = currentClientId() ?: return null
        return try {
            runInterruptible(Dispatchers.IO) { block(cid) }
        } catch (_: AuthException) {
            val fresh = currentClientId(forceRefresh = true) ?: return null
            runCatching { runInterruptible(Dispatchers.IO) { block(fresh) } }.getOrNull()
        }
    }

    private data class ScTrack(
        val id: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val artwork: String?,
        val json: JSONObject,
    )

    private fun parseTrack(o: JSONObject): ScTrack? {
        val id = o.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        if (o.optString("kind", "track") != "track") return null
        val policy = o.optString("policy")
        // BLOCK = not available in the region; SNIP = Go+ only, the public stream is a 30 s preview.
        if (policy.equals("BLOCK", true) || policy.equals("SNIP", true)) return null
        if (!o.optBoolean("streamable", true)) return null
        val publisherArtist = o.optJSONObject("publisher_metadata")?.optString("artist").orEmpty()
        val uploader = o.optJSONObject("user")?.optString("username").orEmpty()
        return ScTrack(
            id = id,
            title = o.optString("title"),
            artist = publisherArtist.ifBlank { uploader },
            durationMs = o.optLong("full_duration", o.optLong("duration", 0L)),
            artwork = o.optString("artwork_url").takeIf { it.isNotBlank() && it != "null" },
            json = o,
        )
    }

    private suspend fun searchTracks(text: String): List<ScTrack> = withClientId { cid ->
        val url = "https://api-v2.soundcloud.com/search/tracks".toHttpUrl().newBuilder()
            .addQueryParameter("q", text)
            .addQueryParameter("client_id", cid)
            .addQueryParameter("limit", "25")
            .addQueryParameter("offset", "0")
            .addQueryParameter("app_locale", "en")
            .build().toString()
        val body = get(url) ?: return@withClientId emptyList()
        val collection = JSONObject(body).optJSONArray("collection") ?: JSONArray()
        (0 until collection.length()).mapNotNull { i -> collection.optJSONObject(i)?.let(::parseTrack) }
    }.orEmpty()

    override suspend fun searchFree(text: String, limit: Int): List<ProviderMatch> {
        if (text.isBlank()) return emptyList()
        return searchTracks(text).filter { it.hasOpenTranscoding() }.take(limit).map { track ->
            cachedTracks[track.id] = track.json
            ProviderMatch(
                provider = id,
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                durationMs = track.durationMs,
                confidence = 0.0,
                thumbnailUrl = track.artwork,
            )
        }
    }

    /**
     * Search + verification. Label releases on SoundCloud are frequently served ONLY as DRM-encrypted
     * HLS (their "progressive"/"hls" entries answer 404), so a title match alone is worthless: the
     * best matching uploads are checked for a real, unencrypted stream and the first playable one wins.
     */
    override suspend fun search(query: AudioQuery): ProviderMatch? {
        val text = ProviderGate.searchText(query)
        if (text.isBlank()) return null
        var tracks = searchTracks(text).filter { it.hasOpenTranscoding() }
        var ranked = rank(query, tracks)
        if (ranked.isEmpty()) {
            // "Artist Title" found nothing usable: retry with the bare title (uploads often mistag the artist
            // in the title field, which the gate still checks).
            tracks = searchTracks(com.metrolist.spotify.SpotifyMapper.spotifyTitleCore(query.title))
                .filter { it.hasOpenTranscoding() }
            ranked = rank(query, tracks)
        }
        for (chosen in ranked) {
            val track = tracks.first { it.id == chosen.id }
            cachedTracks[track.id] = track.json
            val stream = resolveStream(track.json) ?: continue
            recentStreams[track.id] = stream
            return ProviderMatch(
                provider = id,
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                durationMs = track.durationMs,
                confidence = chosen.score,
                thumbnailUrl = track.artwork,
            )
        }
        return null
    }

    private fun rank(query: AudioQuery, tracks: List<ScTrack>): List<SpotifyMapper.MatchResult.Matched> {
        val candidates = tracks.map { t ->
            SpotifyMapper.Candidate(
                id = t.id,
                // SoundCloud uploads are often titled "Artist - Title" with the uploader as user;
                // the gate accepts the artist found in either field.
                title = t.title,
                artist = t.artist,
                durationSec = (t.durationMs / 1000).toInt().takeIf { it > 0 },
                thumbnailUrl = t.artwork,
            )
        }
        // Official uploads (uploader / publisher = the artist) are tried before re-uploads.
        val artistKey = normalizeName(query.primaryArtist)
        val (official, others) = candidates.partition { c ->
            artistKey.isNotEmpty() && normalizeName(c.artist) == artistKey
        }
        return (ProviderGate.ranked(query, official) + ProviderGate.ranked(query, others)).take(4)
    }

    private fun normalizeName(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun ScTrack.hasOpenTranscoding(): Boolean {
        val arr = json.optJSONObject("media")?.optJSONArray("transcodings") ?: return false
        return (0 until arr.length()).any { i ->
            val t = arr.optJSONObject(i) ?: return@any false
            val protocol = t.optJSONObject("format")?.optString("protocol")
            !t.optBoolean("snipped", false) && (protocol == "progressive" || protocol == "hls")
        }
    }

    private val cachedTracks = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val recentStreams = java.util.concurrent.ConcurrentHashMap<String, AudioStream>()

    override suspend fun stream(query: AudioQuery, match: ProviderMatch): AudioStream? {
        recentStreams[match.trackId]?.takeIf { it.expiresAtMs > System.currentTimeMillis() + 60_000L }?.let { return it }
        val trackJson = cachedTracks[match.trackId] ?: withClientId { cid ->
            get("https://api-v2.soundcloud.com/tracks/${match.trackId}?client_id=$cid")?.let(::JSONObject)
        }?.also { cachedTracks[match.trackId] = it } ?: return null
        return resolveStream(trackJson)?.also { recentStreams[match.trackId] = it }
    }

    private suspend fun resolveStream(trackJson: JSONObject): AudioStream? {
        val transcodings = trackJson.optJSONObject("media")?.optJSONArray("transcodings") ?: return null
        val auth = trackJson.optString("track_authorization").takeIf { it.isNotBlank() }
        val options = (0 until transcodings.length()).mapNotNull { transcodings.optJSONObject(it) }
            .filter { !it.optBoolean("snipped", false) }
            .mapNotNull { t ->
                val format = t.optJSONObject("format") ?: return@mapNotNull null
                val protocol = format.optString("protocol")
                val mime = format.optString("mime_type").substringBefore(';').trim()
                // "*-encrypted-hls" variants are DRM (Widevine/PlayReady/FairPlay) — unusable.
                val rank = when {
                    // AAC 160k (the format SoundCloud serves for almost everything now) first; the MP3
                    // variants mostly answer 404 today but are kept as a fallback.
                    protocol == "hls" && mime == "audio/mp4" -> 0
                    protocol == "progressive" && mime == "audio/mpeg" -> 1
                    protocol == "hls" && mime == "audio/mpeg" -> 2
                    protocol == "hls" && mime == "audio/ogg" -> 3
                    else -> return@mapNotNull null
                }
                Triple(rank, t, mime)
            }
            // Within a rank prefer the higher bitrate (aac_160k before aac_96k).
            .sortedWith(compareBy<Triple<Int, JSONObject, String>> { it.first }.thenBy { if ("96" in it.second.optString("preset")) 1 else 0 })

        for ((_, transcoding, mime) in options) {
            val url = transcoding.optString("url").takeIf { it.isNotBlank() } ?: continue
            val protocol = transcoding.optJSONObject("format")?.optString("protocol")
            val streamUrl = withClientId { cid ->
                val full = url.toHttpUrl().newBuilder()
                    .addQueryParameter("client_id", cid)
                    .apply { if (auth != null) addQueryParameter("track_authorization", auth) }
                    .build().toString()
                get(full)?.let { JSONObject(it).optString("url").takeIf { u -> u.isNotBlank() } }
            } ?: continue
            val isHls = protocol == "hls"
            // HLS fMP4 AAC is transmuxed to ADTS by HlsConcatDataSource; HLS MP3 is concatenated.
            val (outMime, codecs, bitrate) = when {
                mime == "audio/mpeg" -> Triple("audio/mpeg", "mp3", 128_000)
                mime == "audio/mp4" -> Triple(if (isHls) "audio/aac" else "audio/mp4", "mp4a.40.2", if ("96k" in streamUrl) 96_000 else 160_000)
                else -> Triple("audio/ogg", "opus", 64_000)
            }
            return AudioStream(
                uri = if (isHls) HlsConcatDataSource.wrap(streamUrl) else streamUrl,
                mimeType = outMime,
                codecs = codecs,
                bitrate = bitrate,
                sampleRate = 44_100,
                // Signed CDN urls stay valid for a while; re-resolve after ~20 minutes to be safe.
                expiresAtMs = System.currentTimeMillis() + 20 * 60 * 1000L,
            )
        }
        return null
    }

    private companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }
}
