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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Bandcamp: independent and underground artists publish there directly; most releases stream in full
 * for free (MP3 128 kbps), no account needed. Limitations: few major-label releases, and some artists
 * restrict streaming to previews (those have no stream and are skipped).
 *
 * Uses the public search endpoint of bandcamp.com and the track data embedded in each track page.
 */
class BandcampAudioProvider : AudioProvider {
    override val id = AudioProviderId.BANDCAMP
    override val searchTimeoutMs = 8_000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private data class TrackPage(val streamUrl: String?, val durationSec: Int?, val title: String, val artist: String)

    private val pages = ConcurrentHashMap<String, Pair<TrackPage, Long>>()

    override suspend fun searchFree(text: String, limit: Int): List<ProviderMatch> = runInterruptible(Dispatchers.IO) {
        if (text.isBlank()) return@runInterruptible emptyList()
        // The length and the stream URL live on the track page; loading 15 pages up front would make
        // the search crawl, so they are fetched when the track is actually played.
        autocomplete(text).take(limit).map { candidate ->
            ProviderMatch(
                provider = id,
                trackId = candidate.id,
                title = candidate.title,
                artist = candidate.artist,
                durationMs = null,
                confidence = 0.0,
                thumbnailUrl = candidate.thumbnailUrl,
            )
        }
    }

    /** Bandcamp's own search box endpoint: tracks only, title/artist/cover, no length. */
    private fun autocomplete(text: String): List<SpotifyMapper.Candidate> {
        val body = JSONObject()
            .put("search_text", text)
            .put("search_filter", "t")
            .put("full_page", false)
            .put("fan_id", JSONObject.NULL)
            .toString()
        val request = Request.Builder()
            .url("https://bandcamp.com/api/bcsearch_public_api/1/autocomplete_elastic")
            .header("User-Agent", UA)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val results = http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Bandcamp search HTTP ${r.code}")
            JSONObject(r.body?.string() ?: return emptyList())
                .optJSONObject("auto")?.optJSONArray("results")
        } ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("type") != "t") return@mapNotNull null
            val url = o.optString("item_url_path").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            SpotifyMapper.Candidate(
                id = url,
                title = o.optString("name"),
                artist = o.optString("band_name"),
                durationSec = null, // not in search results; checked on the track page
                thumbnailUrl = o.optString("img").takeIf { it.isNotBlank() },
            )
        }
    }

    override suspend fun search(query: AudioQuery): ProviderMatch? = runInterruptible(Dispatchers.IO) {
        val text = ProviderGate.searchText(query)
        if (text.isBlank()) return@runInterruptible null
        val candidates = autocomplete(text)
        // Title/artist gate first (no network), then the length check + stream on the track page.
        for (chosen in ProviderGate.ranked(query, candidates, limit = 3)) {
            val page = loadPage(chosen.id) ?: continue
            val wanted = (query.durationMs / 1000).toInt()
            val duration = page.durationSec
            if (wanted > 0 && duration != null && kotlin.math.abs(duration - wanted) > ProviderGate.UPLOAD_DURATION_TOLERANCE_S) continue
            if (page.streamUrl == null) continue
            return@runInterruptible ProviderMatch(
                provider = id,
                trackId = chosen.id,
                title = page.title,
                artist = page.artist,
                durationMs = duration?.times(1000L),
                confidence = chosen.score,
                thumbnailUrl = candidates.firstOrNull { it.id == chosen.id }?.thumbnailUrl,
            )
        }
        null
    }

    override suspend fun stream(query: AudioQuery, match: ProviderMatch): AudioStream? = runInterruptible(Dispatchers.IO) {
        val cached = pages[match.trackId]?.takeIf { System.currentTimeMillis() - it.second < PAGE_TTL_MS }?.first
        val page = cached ?: loadPage(match.trackId)
        val url = page?.streamUrl ?: return@runInterruptible null
        AudioStream(
            uri = url,
            mimeType = "audio/mpeg",
            codecs = "mp3",
            bitrate = 128_000,
            sampleRate = 44_100,
            expiresAtMs = System.currentTimeMillis() + PAGE_TTL_MS,
        )
    }

    private fun loadPage(url: String): TrackPage? {
        val html = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.string()
        } ?: return null
        val raw = TRALBUM.find(html)?.groupValues?.get(1) ?: return null
        val json = JSONObject(unescapeHtml(raw))
        val track = json.optJSONArray("trackinfo")?.optJSONObject(0) ?: return null
        val page = TrackPage(
            streamUrl = track.optJSONObject("file")?.optString("mp3-128")?.takeIf { it.startsWith("http") },
            durationSec = track.optDouble("duration", 0.0).toInt().takeIf { it > 0 },
            title = track.optString("title"),
            artist = json.optString("artist"),
        )
        pages[url] = page to System.currentTimeMillis()
        return page
    }

    companion object {
        private const val PAGE_TTL_MS = 30 * 60 * 1000L
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        private val TRALBUM = Regex("data-tralbum=\"([^\"]+)\"")

        fun unescapeHtml(s: String): String = s
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }
}
