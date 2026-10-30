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

    private suspend fun searchTracks(text: String, limit: Int = 25): List<ScTrack> = withClientId { cid ->
        // SoundCloud answers at most 50 per request, so more than that is paged through.
        val wanted = limit.coerceIn(1, 200)
        val out = mutableListOf<ScTrack>()
        var offset = 0
        while (out.size < wanted) {
            val page = (wanted - out.size).coerceAtMost(50)
            val url = "https://api-v2.soundcloud.com/search/tracks".toHttpUrl().newBuilder()
                .addQueryParameter("q", text)
                .addQueryParameter("client_id", cid)
                .addQueryParameter("limit", page.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("app_locale", "en")
                .build().toString()
            val body = get(url) ?: break
            val collection = JSONObject(body).optJSONArray("collection") ?: JSONArray()
            if (collection.length() == 0) break
            out += (0 until collection.length()).mapNotNull { i -> collection.optJSONObject(i)?.let(::parseTrack) }
            offset += collection.length()
            if (collection.length() < page) break
        }
        out
    }.orEmpty()

    override suspend fun searchFree(text: String, limit: Int): List<ProviderMatch> {
        if (text.isBlank()) return emptyList()
        return searchTracks(text, limit).filter { it.hasOpenTranscoding() }.take(limit).map { track ->
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
        val (official, others) = candidates
            .filterNot { isEditOf(query.title, it.title) }
            .partition { c -> artistKey.isNotEmpty() && normalizeName(c.artist) == artistKey }
        // Label releases are DRM-only here, so what is left of a popular track is mostly fans'
        // re-uploads and edits. A re-upload must name the artist as a whole word (not "my" inside
        // "mystery", not an uploader called "futurebeats" for Future), be practically the same
        // length, and never counts as a sure match: an exact match elsewhere always wins over it.
        val reuploads = others.filter { namesArtist(query, it) }
        val reuploadMatches = ProviderGate.ranked(query, reuploads, maxDurationGapS = REUPLOAD_DURATION_TOLERANCE_S)
            .map { it.copy(score = minOf(it.score, REUPLOAD_MAX_CONFIDENCE)) }
        return (ProviderGate.ranked(query, official) + reuploadMatches).take(4)
    }

    private fun normalizeName(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun words(s: String): List<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** The artist as whole words in the upload's title or uploader name (or its transliteration). */
    private fun namesArtist(query: AudioQuery, c: SpotifyMapper.Candidate): Boolean {
        val titleWords = words(c.title)
        val uploaderKey = normalizeName(c.artist)
        val named = query.artists.any { artist ->
            val key = normalizeName(artist)
            val artistWords = words(artist)
            key.isNotEmpty() && (
                key == uploaderKey ||
                    (artistWords.isNotEmpty() && titleWords.windowed(artistWords.size).any { it == artistWords })
                )
        }
        if (named) return true
        val tokens = SpotifyMapper.artistTokens(query.artists.joinToString(", "))
        val nonLatin = tokens.none { t -> t.any { it in 'a'..'z' || it in '0'..'9' } }
        return nonLatin && SpotifyMapper.artistTransliterationMatches(c.title, c.artist, tokens)
    }

    /** An edit / flip / someone's "version" of the song that the catalog title doesn't name. */
    private fun isEditOf(catalogTitle: String, uploadTitle: String): Boolean {
        val upload = EDIT_MARKERS.findAll(uploadTitle.lowercase()).map { it.value }.toSet() - SAME_RECORDING
        if (upload.isEmpty()) return false
        val own = EDIT_MARKERS.findAll(catalogTitle.lowercase()).map { it.value }.toSet()
        return (upload - own).isNotEmpty()
    }

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

    // ── Import ──────────────────────────────────────────────────────────────────────────────────

    /** A user's playlist or album, for picking what to bring over. */
    data class ImportSet(val title: String, val url: String, val trackCount: Int)

    /** A public profile: its likes are read separately, by [importTracks] on the profile link. */
    data class ImportProfile(
        val name: String,
        val url: String,
        val avatar: String?,
        val likes: Int,
        val sets: List<ImportSet>,
    )

    /**
     * One track to bring over: what the catalog is searched by, and the SoundCloud upload itself
     * ([source], null when it can't play here: Go+ only, blocked, DRM) for when the catalog hasn't it.
     */
    data class ImportTrack(val artist: String?, val title: String, val durationSec: Int?, val source: ProviderMatch?)

    /** The profile behind [link] (any link into it, a short on.soundcloud.com one too); null if there is none. */
    suspend fun importProfile(link: String): ImportProfile? = withClientId { cid ->
        val user = resolve(canonical(link).profileUrl(), cid)?.takeIf { it.optString("kind") == "user" } ?: return@withClientId null
        val id = user.opt("id")?.toString() ?: return@withClientId null
        // Playlists and albums come apart; likes of other people's playlists are left out.
        val sets = listOf("playlists_without_albums", "albums").flatMap { kind ->
            pages("https://api-v2.soundcloud.com/users/$id/$kind?limit=200", cid, MAX_SETS)
        }.mapNotNull { p ->
            val url = p.optString("permalink_url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ImportSet(p.optString("title").ifBlank { url.substringAfterLast('/') }, url, p.optInt("track_count"))
        }.filter { it.trackCount > 0 }
        ImportProfile(
            name = user.optString("username"),
            url = user.optString("permalink_url"),
            avatar = user.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" },
            likes = user.optInt("likes_count"),
            sets = sets,
        )
    }

    /**
     * The tracks behind [link]: a playlist or album, one track, or a profile — its likes then. Null
     * when SoundCloud can't be reached or has nothing there; hidden likes give an empty list.
     */
    suspend fun importTracks(link: String, max: Int): List<ImportTrack>? = withClientId { cid ->
        val url = canonical(link)
        val likes = Regex("^https://soundcloud\\.com/[^/]+/?(likes/?)?$").matches(url)
        val item = resolve(if (likes) url.profileUrl() else url, cid) ?: return@withClientId null
        val tracks = when (item.optString("kind")) {
            "user" -> pages("https://api-v2.soundcloud.com/users/${item.opt("id")}/track_likes?limit=200", cid, max)
                .mapNotNull { it.optJSONObject("track") }
            "playlist", "system-playlist" -> playlistTracks(item, cid)
            "track" -> listOf(item)
            else -> emptyList()
        }
        tracks.take(max).mapNotNull(::importTrackOf)
    }

    /** A playlist answers with its first tracks in full and the rest as bare ids, fetched 50 at a time. */
    private fun playlistTracks(playlist: JSONObject, cid: String): List<JSONObject> {
        val arr = playlist.optJSONArray("tracks") ?: return emptyList()
        val listed = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val full = HashMap<String, JSONObject>()
        listed.filter { it.has("title") }.forEach { full[it.opt("id").toString()] = it }
        listed.map { it.opt("id").toString() }.filter { it !in full }.chunked(50).forEach { ids ->
            val body = get("https://api-v2.soundcloud.com/tracks?ids=${ids.joinToString(",")}&client_id=$cid") ?: return@forEach
            val found = JSONArray(body)
            for (i in 0 until found.length()) found.optJSONObject(i)?.let { full[it.opt("id").toString()] = it }
        }
        return listed.mapNotNull { full[it.opt("id").toString()] }
    }

    /** Uploads are often titled "Artist - Title" under a label's or a fan's account. */
    private fun importTrackOf(o: JSONObject): ImportTrack? {
        val fullTitle = o.optString("title").trim().takeIf { it.isNotEmpty() } ?: return null
        val publisher = o.optJSONObject("publisher_metadata")?.optString("artist")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        val uploader = o.optJSONObject("user")?.optString("username")?.trim()?.takeIf { it.isNotEmpty() }
        val parts = fullTitle.split(TITLE_DASH, limit = 2)
        val (artist, title) = when {
            publisher != null && parts.size == 2 && normalizeName(parts[0]) == normalizeName(publisher) -> publisher to parts[1].trim()
            publisher != null -> publisher to fullTitle
            parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank() -> parts[0].trim() to parts[1].trim()
            else -> uploader to fullTitle
        }
        val durationMs = o.optLong("full_duration", o.optLong("duration", 0L))
        val source = parseTrack(o)?.takeIf { it.hasOpenTranscoding() }?.let { t ->
            cachedTracks[t.id] = t.json
            ProviderMatch(id, t.id, title, artist ?: t.artist, t.durationMs, 1.0, t.artwork?.replace("-large.", "-t500x500."))
        }
        return ImportTrack(artist, title, (durationMs / 1000).toInt().takeIf { it > 0 }, source)
    }

    private fun resolve(url: String, cid: String): JSONObject? {
        val api = "https://api-v2.soundcloud.com/resolve".toHttpUrl().newBuilder()
            .addQueryParameter("url", url)
            .addQueryParameter("client_id", cid)
            .build().toString()
        return get(api)?.let(::JSONObject)
    }

    /** A paged list ("collection" + "next_href", which comes without the client id). */
    private fun pages(first: String, cid: String, max: Int): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var next: String? = first
        while (next != null && out.size < max) {
            val page = get(next.toHttpUrl().newBuilder().setQueryParameter("client_id", cid).build().toString())
                ?.let(::JSONObject) ?: break
            val items = page.optJSONArray("collection") ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) items.optJSONObject(i)?.let(out::add)
            next = page.optString("next_href").takeIf { it.startsWith("https://") }
        }
        return out
    }

    /** The plain soundcloud.com form: app share links (on.soundcloud.com/…) redirect there, m. is the same site. */
    private fun canonical(link: String): String {
        var url = link.trim().replace("://m.soundcloud.com", "://soundcloud.com").replace("://www.soundcloud.com", "://soundcloud.com")
        if ("on.soundcloud.com/" in url) {
            val request = Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()
            url = runCatching { http.newCall(request).execute().use { it.request.url.toString() } }.getOrDefault(url)
        }
        return url.replace("http://", "https://").substringBefore('?').substringBefore('#').trimEnd('/')
    }

    /** soundcloud.com/<user>, whatever page of the profile the link points to. */
    private fun String.profileUrl(): String = Regex("^(https://soundcloud\\.com/[^/]+)").find(this)?.value ?: this

    private companion object {
        /** "Artist - Title" in an upload's title (hyphen or dash, with spaces around it). */
        val TITLE_DASH = Regex("\\s+[-–—]\\s+")

        /** Playlists and albums read for a profile, each kind. */
        const val MAX_SETS = 400

        /** Re-uploads must be this close in length: an edit is often only a few seconds off. */
        const val REUPLOAD_DURATION_TOLERANCE_S = 3

        /** Below [com.metrolist.music.resolver.ParallelAudioResolver.STRONG_MATCH] on purpose. */
        const val REUPLOAD_MAX_CONFIDENCE = 0.85

        /**
         * SoundCloud's own words for another cut of a song, on top of the remix / cover / sped-up
         * markers every source rejects ("S&M (FLOZONE FLIP)", "One Kiss (SYLS Edit)",
         * "Timeless (Elias Version)").
         */
        val EDIT_MARKERS = Regex(
            "\\b(flip|edit|re-?edit|rework|vip|remake|extended|club mix|bass ?boost(ed)?|phonk|" +
                "jersey( club)?|hardstyle|drill|amapiano|[\\p{L}\\p{N}]+ version)\\b",
        )

        /** "… version" words that still mean the very recording the catalog has. */
        val SAME_RECORDING = setOf("album version", "original version", "single version", "explicit version")

        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }
}
