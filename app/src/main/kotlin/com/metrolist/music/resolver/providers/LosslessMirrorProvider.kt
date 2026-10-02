/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver.providers

import android.util.Base64
import com.metrolist.music.resolver.AudioProvider
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.AudioQuery
import com.metrolist.music.resolver.AudioStream
import com.metrolist.music.resolver.ProviderMatch
import com.metrolist.music.utils.RemoteConfig
import com.metrolist.spotify.SpotifyMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Lossless FLAC from public "hifi-api" mirrors (community servers in front of a lossless catalog).
 * Which mirrors exist changes all the time, so none are built in: the list comes from
 * [RemoteConfig.Config.losslessMirrors], and with no mirrors listed this source simply stays idle.
 *
 * The protocol is read defensively (it has changed between server versions): a search returns
 * `items` somewhere in its JSON; a track answers either a base64 `manifest` holding `urls`, or an
 * `OriginalTrackUrl`.
 */
class LosslessMirrorProvider : AudioProvider {
    override val id = AudioProviderId.LOSSLESS
    override val searchTimeoutMs = 9_000L

    override fun isReady(): Boolean = RemoteConfig.config.value.losslessMirrors.isNotEmpty()

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .build()

    /** The mirror that answered last, tried first next time. */
    @Volatile
    private var lastGood: String? = null

    private val durations = ConcurrentHashMap<String, Int>()

    private fun mirrors(): List<String> {
        val all = RemoteConfig.config.value.losslessMirrors
        val good = lastGood?.takeIf { it in all }
        return listOfNotNull(good) + all.filter { it != good }
    }

    /** GET [path] on the first mirror that answers with JSON. */
    private fun get(path: String, params: Map<String, String>): Any? {
        for (base in mirrors()) {
            val url = "$base$path".toHttpUrlOrNull()?.newBuilder()?.apply {
                params.forEach { (k, v) -> addQueryParameter(k, v) }
            }?.build() ?: continue
            val text = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
            }.onFailure { Timber.tag("AudioRace").d("lossless %s ✘ %s", base, it.message) }.getOrNull() ?: continue
            val json = runCatching { if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text) }.getOrNull()
                ?: continue
            lastGood = base
            return json
        }
        return null
    }

    override suspend fun search(query: AudioQuery): ProviderMatch? = runInterruptible(Dispatchers.IO) {
        if (!isReady()) return@runInterruptible null
        val root = get("/search/", mapOf("s" to ProviderGate.searchText(query))) ?: return@runInterruptible null
        val items = findItems(root)
        if (items.isEmpty()) return@runInterruptible null
        val parsed = items.mapNotNull { o ->
            val trackId = o.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val artist = o.optJSONObject("artist")?.optString("name")
                ?: o.optJSONArray("artists")?.optJSONObject(0)?.optString("name")
                ?: ""
            Triple(trackId, o, artist)
        }
        // Same recording by ISRC is certain; otherwise the usual strict title/artist/length gate.
        query.isrc?.let { isrc ->
            parsed.firstOrNull { (_, o, _) -> o.optString("isrc").equals(isrc, ignoreCase = true) }?.let { (trackId, o, artist) ->
                durations[trackId] = o.optInt("duration")
                return@runInterruptible ProviderMatch(id, trackId, o.optString("title"), artist, o.optInt("duration") * 1000L, 1.0, qualityKbps = 1411)
            }
        }
        val chosen = ProviderGate.ranked(
            query,
            parsed.map { (trackId, o, artist) ->
                SpotifyMapper.Candidate(trackId, o.optString("title"), artist, o.optInt("duration").takeIf { it > 0 })
            },
            limit = 1,
        ).firstOrNull() ?: return@runInterruptible null
        val (trackId, o, artist) = parsed.first { it.first == chosen.id }
        durations[trackId] = o.optInt("duration")
        ProviderMatch(id, trackId, o.optString("title"), artist, o.optInt("duration") * 1000L, chosen.score, qualityKbps = 1411)
    }

    override suspend fun stream(query: AudioQuery, match: ProviderMatch): AudioStream? = runInterruptible(Dispatchers.IO) {
        val root = get("/track/", mapOf("id" to match.trackId, "quality" to "LOSSLESS")) ?: return@runInterruptible null
        val (url, mime) = streamUrl(root) ?: return@runInterruptible null
        // FLAC is variable: the real average is size over length.
        val size = runCatching {
            http.newCall(Request.Builder().url(url).head().build()).execute().use { it.header("Content-Length")?.toLongOrNull() }
        }.getOrNull()
        val seconds = durations[match.trackId]?.takeIf { it > 0 } ?: (query.durationMs / 1000).toInt()
        val bitrate = if (size != null && seconds > 0) (size * 8 / seconds).toInt() else 0
        AudioStream(
            uri = url,
            mimeType = mime,
            codecs = if (mime.contains("flac")) "flac" else "",
            bitrate = bitrate,
            sampleRate = 44_100,
            expiresAtMs = System.currentTimeMillis() + 20 * 60_000L,
            contentLength = size,
        )
    }

    /** The `items` array wherever this server version puts it (`items`, `data.items`, `[ {items} ]`). */
    private fun findItems(root: Any): List<JSONObject> {
        fun fromObject(o: JSONObject): JSONArray? =
            o.optJSONArray("items") ?: o.optJSONObject("data")?.let(::fromObject)
                ?: o.optJSONObject("tracks")?.let(::fromObject)
        val array = when (root) {
            is JSONObject -> fromObject(root)
            is JSONArray -> (0 until root.length()).firstNotNullOfOrNull { root.optJSONObject(it)?.let(::fromObject) }
            else -> null
        } ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    /** The FLAC url: from a base64 manifest (`urls`), or an `OriginalTrackUrl` field. */
    private fun streamUrl(root: Any): Pair<String, String>? {
        val objects = when (root) {
            is JSONObject -> listOfNotNull(root, root.optJSONObject("data"))
            is JSONArray -> (0 until root.length()).mapNotNull { root.optJSONObject(it) }
            else -> emptyList()
        }
        for (o in objects) {
            o.optString("OriginalTrackUrl").takeIf { it.startsWith("http") }?.let { return it to "audio/flac" }
            val manifest = o.optString("manifest").takeIf { it.isNotBlank() } ?: continue
            val decoded = runCatching { String(Base64.decode(manifest, Base64.DEFAULT)) }.getOrNull() ?: continue
            val json = runCatching { JSONObject(decoded) }.getOrNull() ?: continue // DASH (xml): not handled
            val url = json.optJSONArray("urls")?.optString(0)?.takeIf { it.startsWith("http") } ?: continue
            return url to (json.optString("mimeType").takeIf { it.isNotBlank() } ?: "audio/flac")
        }
        return null
    }
}
