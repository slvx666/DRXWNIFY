/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.genre

import android.content.Context
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.portrait.Genres
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * "Getting to know a genre": the 15 tracks that describe it best, for a tap on a genre tag.
 *
 * Taken from Last.fm, where listeners tag what they hear: the genre's most played tracks, checked
 * against the genre's own top artists (a meme tag puts a pop song first in "deathcore" — its
 * artist isn't among deathcore's artists, so it goes). One track per artist first, so fifteen
 * tracks show fifteen sides of the genre; a second one only when there are too few artists. Each
 * is looked up in the catalog for its cover and to play; the list is kept for a week.
 */
object GenrePlaylists {
    const val SIZE = 15
    private const val TTL_MS = 7L * 24 * 60 * 60_000

    @Serializable
    private data class Saved(val at: Long, val tracks: List<SpotifyTrack>)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val memory = ConcurrentHashMap<String, Saved>()
    private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "genre_playlists").also { it.mkdirs() }
    }

    private fun fileOf(tag: String) = dir?.let { File(it, tag.replace(Regex("[^a-z0-9]+"), "_") + ".json") }

    /** The genre's tracks: kept ones when fresh, else built (a few seconds). */
    suspend fun tracks(genre: String, force: Boolean = false): Result<List<SpotifyTrack>> = withContext(Dispatchers.IO) {
        val tag = lastFmTag(genre)
        val now = System.currentTimeMillis()
        if (!force) {
            val saved = memory[tag] ?: runCatching {
                fileOf(tag)?.takeIf { it.exists() }?.readText()?.let { json.decodeFromString(Saved.serializer(), it) }
            }.getOrNull()?.also { memory[tag] = it }
            if (saved != null && now - saved.at < TTL_MS && saved.tracks.isNotEmpty()) return@withContext Result.success(saved.tracks)
        }
        runCatching { build(tag, genre) }
            .onSuccess { tracks ->
                if (tracks.isNotEmpty()) {
                    val saved = Saved(now, tracks)
                    memory[tag] = saved
                    runCatching { fileOf(tag)?.writeText(json.encodeToString(Saved.serializer(), saved)) }
                }
            }
            .onFailure { Timber.w(it, "GenrePlaylists: '$genre' failed") }
    }

    @Serializable
    private data class Artists(val at: Long, val names: List<String>)

    private val artistsMemory = ConcurrentHashMap<String, Artists>()

    /**
     * The genre's most tagged artists on Last.fm (up to 250), strongest first: one request per
     * genre, kept for a week. The home page's genre rows keep their picks inside the genre with it.
     */
    suspend fun topArtistNamesOf(genre: String): List<String> = withContext(Dispatchers.IO) {
        val tag = lastFmTag(genre)
        val now = System.currentTimeMillis()
        val file = dir?.let { File(it, "artists_" + tag.replace(Regex("[^a-z0-9]+"), "_") + ".json") }
        val kept = artistsMemory[tag] ?: runCatching {
            file?.takeIf { it.exists() }?.readText()?.let { json.decodeFromString(Artists.serializer(), it) }
        }.getOrNull()?.also { artistsMemory[tag] = it }
        if (kept != null && now - kept.at < TTL_MS && kept.names.isNotEmpty()) return@withContext kept.names
        val names = runCatching {
            lastFm("tag.gettopartists", tag, 250, "topartists", "artist").mapNotNull { it.optString("name").takeIf(String::isNotBlank) }
        }.getOrElse { return@withContext kept?.names.orEmpty() }
        if (names.isNotEmpty()) {
            val fresh = Artists(now, names)
            artistsMemory[tag] = fresh
            runCatching { file?.writeText(json.encodeToString(Artists.serializer(), fresh)) }
        }
        names
    }

    /** [topArtistNamesOf] as normalized names (letters and digits, lower case), for quick checks. */
    suspend fun topArtistsOf(genre: String): Set<String> = topArtistNamesOf(genre).mapTo(HashSet(), ::norm)

    /** The tag Last.fm knows the genre by (the app shows them in its own spelling). */
    private fun lastFmTag(genre: String): String = when (val g = genre.trim().lowercase()) {
        "r&b" -> "rnb"
        "electronic dance" -> "edm"
        "drum and bass" -> "drum and bass"
        else -> g
    }

    private data class Candidate(val artist: String, val title: String)

    private suspend fun build(tag: String, genre: String): List<SpotifyTrack> = coroutineScope {
        if (!Catalog.ensureAuthenticated()) throw IllegalStateException("catalog unavailable")
        val topTracks = async { lastFm("tag.gettoptracks", tag, 150, "tracks", "track") }
        val artistsOfGenre = topArtistsOf(genre)
        val family = Genres.family(genre).lowercase()

        fun belongs(artist: String): Boolean =
            norm(artist) in artistsOfGenre ||
                Genres.cached(artist).any { Genres.family(it).lowercase() == family || it.equals(genre, ignoreCase = true) }

        val ranked = topTracks.await().mapNotNull { o ->
            val title = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = o.optJSONObject("artist")?.optString("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Candidate(artist, title)
        }.filter { belongs(it.artist) }
            .distinctBy { norm(it.artist) + "|" + norm(it.title.substringBefore(" (")) }

        // One per artist in Last.fm's order, then the second ones.
        val seen = HashMap<String, Int>()
        val firstPass = ArrayList<Candidate>()
        val secondPass = ArrayList<Candidate>()
        ranked.forEach { c ->
            val n = seen.merge(norm(c.artist), 1, Int::plus)!!
            when (n) {
                1 -> firstPass += c
                2 -> secondPass += c
            }
        }
        val order = firstPass + secondPass

        // Looked up in the catalog a few at a time, in order, until there are enough.
        val gate = Semaphore(5)
        val found = ArrayList<SpotifyTrack>()
        var index = 0
        while (found.size < SIZE && index < order.size) {
            val batch = order.subList(index, minOf(order.size, index + (SIZE - found.size) + 3))
            index += batch.size
            batch.map { c -> async { gate.withPermit { lookUp(c) } } }.awaitAll()
                .filterNotNull()
                .forEach { t -> if (found.size < SIZE && found.none { it.id == t.id }) found += t }
        }
        found
    }

    private suspend fun lookUp(c: Candidate): SpotifyTrack? = runCatching {
        val title = norm(c.title.substringBefore(" (").substringBefore(" - "))
        Catalog.search("${c.artist} ${c.title}", types = listOf("track"), limit = 5).getOrNull()?.tracks?.items
            ?.firstOrNull { t ->
                val artistOk = t.artists.any { a -> norm(a.name).let { it == norm(c.artist) || (it.length >= 3 && (it in norm(c.artist) || norm(c.artist) in it)) } }
                val candidate = norm(t.name.substringBefore(" (").substringBefore(" - "))
                artistOk && t.id.isNotEmpty() && !t.isLocal && (candidate == title || candidate.startsWith(title) || title.startsWith(candidate))
            }
    }.getOrNull()

    private fun lastFm(method: String, tag: String, limit: Int, root: String, item: String): List<JSONObject> {
        val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrl().newBuilder()
            .addQueryParameter("method", method)
            .addQueryParameter("tag", tag)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("api_key", Genres.LASTFM_KEY)
            .addQueryParameter("format", "json")
            .build()
        val body = Genres.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("Last.fm ${r.code}")
            r.body?.string()
        } ?: throw java.io.IOException("Last.fm: empty answer")
        val list = JSONObject(body).optJSONObject(root)?.optJSONArray(item) ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
    }

    private fun norm(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
}
