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
import com.metrolist.spotify.SpotifyMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Audius: an open music network (electronic, phonk, hip-hop, the SoundCloud scene) with a public API —
 * full-length streams, no key and no account. Limitations: artists upload there themselves, so the
 * mainstream catalog is thin.
 */
class AudiusAudioProvider : AudioProvider {
    override val id = AudioProviderId.AUDIUS
    override val searchTimeoutMs = 7_000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var host: Pair<String, Long>? = null

    /** Audius lists its discovery nodes at api.audius.co; one is picked and reused for a while. */
    private fun discoveryHost(forceRefresh: Boolean = false): String {
        host?.takeIf { !forceRefresh && System.currentTimeMillis() - it.second < HOST_TTL_MS }?.let { return it.first }
        val picked = http.newCall(Request.Builder().url("https://api.audius.co").header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Audius hosts HTTP ${r.code}")
            val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("data")
            (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it)?.takeIf { h -> h.startsWith("https://") } }.randomOrNull()
        } ?: throw IllegalStateException("No Audius discovery node")
        host = picked to System.currentTimeMillis()
        return picked
    }

    override suspend fun searchFree(text: String, limit: Int): List<ProviderMatch> = runInterruptible(Dispatchers.IO) {
        if (text.isBlank()) return@runInterruptible emptyList()
        streamableTracks(text, limit).take(limit).map { t ->
            ProviderMatch(
                provider = id,
                trackId = t.optString("id"),
                title = t.optString("title"),
                artist = t.optJSONObject("user")?.optString("name").orEmpty(),
                durationMs = t.optInt("duration", 0) * 1000L,
                confidence = 0.0,
                thumbnailUrl = t.optJSONObject("artwork")?.optString("480x480"),
            )
        }
    }

    /** Search results that Audius says are actually streamable, in its own ranking. */
    private fun streamableTracks(text: String, limit: Int = 20): List<JSONObject> {
        val body = runCatching { searchOn(discoveryHost(), text, limit) }
            .recoverCatching { searchOn(discoveryHost(forceRefresh = true), text, limit) }
            .getOrThrow()
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val t = data.optJSONObject(i) ?: return@mapNotNull null
            if (t.optBoolean("is_streamable", true).not() || t.optBoolean("is_delete", false)) return@mapNotNull null
            t
        }
    }

    override suspend fun search(query: AudioQuery): ProviderMatch? = runInterruptible(Dispatchers.IO) {
        val text = ProviderGate.searchText(query)
        if (text.isBlank()) return@runInterruptible null
        val tracks = streamableTracks(text)
        val candidates = tracks.map { t ->
            SpotifyMapper.Candidate(
                id = t.optString("id"),
                title = t.optString("title"),
                artist = t.optJSONObject("user")?.optString("name").orEmpty(),
                durationSec = t.optInt("duration", 0).takeIf { it > 0 },
                thumbnailUrl = t.optJSONObject("artwork")?.optString("480x480"),
            )
        }
        val chosen = ProviderGate.ranked(query, candidates, limit = 1).firstOrNull() ?: return@runInterruptible null
        val track = tracks.first { it.optString("id") == chosen.id }
        ProviderMatch(
            provider = id,
            trackId = chosen.id,
            title = track.optString("title"),
            artist = track.optJSONObject("user")?.optString("name").orEmpty(),
            durationMs = track.optInt("duration", 0) * 1000L,
            confidence = chosen.score,
            thumbnailUrl = candidates.first { it.id == chosen.id }.thumbnailUrl,
        )
    }

    private fun searchOn(host: String, text: String, limit: Int = 20): String {
        val url = "$host/v1/tracks/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", text)
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .addQueryParameter("app_name", APP_NAME)
            .build()
        return http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Audius search HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    override suspend fun stream(query: AudioQuery, match: ProviderMatch): AudioStream? = runInterruptible(Dispatchers.IO) {
        // The stream endpoint redirects to a content node serving the MP3; OkHttp follows it on playback.
        val url = "${discoveryHost()}/v1/tracks/${match.trackId}/stream?app_name=$APP_NAME"
        val ok = http.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Range", "bytes=0-1").build())
            .execute().use { it.isSuccessful }
        if (!ok) return@runInterruptible null
        AudioStream(
            uri = url,
            mimeType = "audio/mpeg",
            codecs = "mp3",
            bitrate = 320_000,
            sampleRate = 44_100,
            expiresAtMs = System.currentTimeMillis() + HOST_TTL_MS,
        )
    }

    private companion object {
        const val APP_NAME = "Meld"
        const val HOST_TTL_MS = 30 * 60 * 1000L
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }
}
