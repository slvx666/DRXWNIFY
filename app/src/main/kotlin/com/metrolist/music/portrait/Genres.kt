/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.portrait

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Genres of an artist, by name. Neither catalog gives useful ones (Spotify's API in the app has
 * none, Yandex only "rock" or "electronics"), so they come from Last.fm's listener tags, then from
 * MusicBrainz; tags that aren't genres ("seen live", "female vocalists", countries, years) are
 * dropped and spellings merged. Kept on disk for a month.
 */
object Genres {
    /** Last.fm API key of the Drxwnify application (public by nature: it is in every APK). */
    private const val LASTFM_KEY = "710106bd28502d7d62fb5493c67033f9"
    private const val TTL_MS = 30L * 24 * 60 * 60_000
    private const val MISS_TTL_MS = 7L * 24 * 60 * 60_000
    private const val MAX_TAGS = 5

    private class Entry(val genres: List<String>, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val _version = MutableStateFlow(0)

    /** Bumped when genres arrive, so screens showing them can redraw. */
    val version: StateFlow<Int> = _version

    private val fetchMutex = Mutex()
    private var file: File? = null
    private var lastMusicBrainzAt = 0L

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        val f = File(context.filesDir, "artist_genres.json")
        file = f
        runCatching {
            if (!f.exists()) return@runCatching
            val o = JSONObject(f.readText())
            o.keys().forEach { key ->
                val e = o.getJSONObject(key)
                val list = e.optJSONArray("g")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                cache[key] = Entry(list, e.optLong("t"))
            }
        }.onFailure { Timber.w(it, "Genres: cache unreadable") }
    }

    private fun keyOf(artist: String) = artist.trim().lowercase()

    /** What is known now, without the network (empty when not looked up yet or none found). */
    fun cached(artist: String): List<String> = cache[keyOf(artist)]?.genres.orEmpty()

    /** Genres of [artist], looked up when not known or too old. */
    suspend fun of(artist: String): List<String> {
        ensure(listOf(artist))
        return cached(artist)
    }

    /** Looks up every artist of [artists] that isn't known yet (one at a time: the services limit the rate). */
    suspend fun ensure(artists: Collection<String>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val missing = artists.map { it.trim() }.filter { it.isNotBlank() }.distinctBy(::keyOf).filter { name ->
            val e = cache[keyOf(name)]
            e == null || now - e.at > (if (e.genres.isEmpty()) MISS_TTL_MS else TTL_MS)
        }
        if (missing.isEmpty()) return@withContext
        fetchMutex.withLock {
            var changed = false
            for (name in missing) {
                val e = cache[keyOf(name)]
                if (e != null && System.currentTimeMillis() - e.at < (if (e.genres.isEmpty()) MISS_TTL_MS else TTL_MS)) continue
                val lastFm = runCatching { fromLastFm(name) }.getOrNull()
                val musicBrainz = if (lastFm.isNullOrEmpty()) runCatching { fromMusicBrainz(name) }.getOrNull() else null
                // Neither answered (no network): not remembered as "no genres", asked again later.
                if (lastFm == null && musicBrainz == null) continue
                val found = lastFm?.takeIf { it.isNotEmpty() } ?: musicBrainz.orEmpty()
                cache[keyOf(name)] = Entry(found, System.currentTimeMillis())
                changed = true
            }
            if (changed) {
                save()
                _version.value++
            }
        }
    }

    private fun fromLastFm(artist: String): List<String>? {
        val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrl().newBuilder()
            .addQueryParameter("method", "artist.gettoptags")
            .addQueryParameter("artist", artist)
            .addQueryParameter("autocorrect", "1")
            .addQueryParameter("api_key", LASTFM_KEY)
            .addQueryParameter("format", "json")
            .build()
        val body = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.string()
        } ?: return null
        val tags = JSONObject(body).optJSONObject("toptags")?.optJSONArray("tag") ?: return emptyList()
        return pick((0 until tags.length()).mapNotNull { i ->
            tags.optJSONObject(i)?.let { it.optString("name") to it.optInt("count") }
        }, artist, minCount = 8)
    }

    private suspend fun fromMusicBrainz(artist: String): List<String>? {
        // MusicBrainz asks for at most one request a second.
        val wait = 1_100L - (System.currentTimeMillis() - lastMusicBrainzAt)
        if (wait > 0) delay(wait)
        lastMusicBrainzAt = System.currentTimeMillis()
        val url = "https://musicbrainz.org/ws/2/artist/".toHttpUrl().newBuilder()
            .addQueryParameter("query", "artist:\"${artist.replace("\"", "")}\"")
            .addQueryParameter("limit", "1")
            .addQueryParameter("fmt", "json")
            .build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "Drxwnify/2.0 ( https://drxwnify.pages.dev )")
            .build()
        val body = client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.string()
        } ?: return null
        val first = JSONObject(body).optJSONArray("artists")?.optJSONObject(0) ?: return emptyList()
        if (first.optInt("score") < 90) return emptyList()
        val tags = first.optJSONArray("tags") ?: JSONArray()
        return pick((0 until tags.length()).mapNotNull { i ->
            tags.optJSONObject(i)?.let { it.optString("name") to it.optInt("count") }
        }, artist, minCount = 1)
    }

    /** The genre tags among [tags] (name to weight), merged and in display form, strongest first. */
    private fun pick(tags: List<Pair<String, Int>>, artist: String, minCount: Int): List<String> {
        val own = artist.trim().lowercase()
        return tags
            .filter { (_, count) -> count >= minCount }
            .sortedByDescending { it.second }
            .mapNotNull { (name, _) -> normalize(name, own) }
            .distinct()
            .take(MAX_TAGS)
    }

    private val NOT_GENRES = listOf(
        "seen live", "favorite", "favourite", "vocalist", "female", "male", "love", "awesome", "beautiful",
        "best", "my ", "albums i own", "spotify", "under 2000", "check out", "amazing", "cool", "good",
        "sexy", "cover", "soundtrack of my", "listen", "artist", "band", "singer", "songwriter", "russian",
        "american", "british", "german", "japanese", "french", "swedish", "finnish", "norwegian", "polish",
        "ukrainian", "canadian", "australian", "uk", "usa", "russia", "moscow", "london", "tiktok", "meme",
    )

    private val GENRE_WORDS = listOf(
        "rock", "metal", "pop", "core", "wave", "gaze", "hop", "rap", "jazz", "techno", "house", "trance",
        "step", "bass", "punk", "folk", "soul", "funk", "blues", "ambient", "electro", "drum", "dnb",
        "industrial", "noise", "grunge", "emo", "indie", "alternative", "psych", "prog", "post-", "black",
        "death", "doom", "sludge", "stoner", "thrash", "grind", "garage", "disco", "synth", "idm", "glitch",
        "lo-fi", "lofi", "chill", "trip", "breakbeat", "breaks", "jungle", "footwork", "juke", "dub",
        "reggae", "ska", "r&b", "rnb", "classical", "orchestral", "soundtrack", "chanson", "k-pop",
        "j-pop", "phonk", "drill", "trap", "grime", "hyperpop", "plunderphonics", "vapor", "witch house",
        "minimal", "experimental", "avant", "country", "bluegrass", "latin", "salsa", "cumbia",
        "reggaeton", "afro", "bossa", "slowcore", "math", "screamo", "gothic", "goth", "ebm", "hardstyle",
        "gabber", "hardcore", "shoegaze", "dream", "darkwave", "coldwave", "electronic", "dance", "edm",
        "downtempo", "trip-hop", "acoustic", "singer-songwriter", "new age", "world", "gospel", "opera",
        "neoclassical", "art pop", "bedroom", "cloud rap", "emo rap", "jersey club", "bossa nova",
        "nu jazz", "fusion", "swing", "big band", "chiptune", "8-bit", "vgm", "anime", "city pop",
        "krautrock", "zeuhl", "ethereal", "dark ambient", "drone", "musique concrete", "breakcore",
        "speedcore", "digital hardcore", "witch", "horrorcore", "crunk", "g-funk", "boom bap",
    )

    private val ALIASES = mapOf(
        "hip hop" to "hip-hop", "hiphop" to "hip-hop", "rnb" to "r&b", "rhythm and blues" to "r&b",
        "drum n bass" to "drum and bass", "drum & bass" to "drum and bass", "dnb" to "drum and bass",
        "drum'n'bass" to "drum and bass", "lofi" to "lo-fi", "lo fi" to "lo-fi", "electronica" to "electronic",
        "post rock" to "post-rock", "post punk" to "post-punk", "post metal" to "post-metal",
        "post hardcore" to "post-hardcore", "trip hop" to "trip-hop", "nu-metal" to "nu metal",
        "alt rock" to "alternative rock", "alternative" to "alternative rock", "dream-pop" to "dream pop",
        "synth pop" to "synthpop", "synth-pop" to "synthpop", "edm" to "electronic dance",
        "black-metal" to "black metal", "death-metal" to "death metal", "witchhouse" to "witch house",
        "shoegazer" to "shoegaze", "blackgaze" to "blackgaze", "rap" to "hip-hop",
    )

    private val UPPER = setOf("idm", "ebm", "edm", "r&b", "uk", "dnb", "vgm", "8-bit")

    private fun normalize(raw: String, artist: String): String? {
        var tag = raw.trim().lowercase().replace(Regex("\\s+"), " ")
        if (tag.isBlank() || tag == artist || tag.length > 30) return null
        if (Regex("^\\d{2,4}s?$").matches(tag)) return null
        if (NOT_GENRES.any { it in tag }) return null
        tag = ALIASES[tag] ?: tag
        if (GENRE_WORDS.none { it in tag }) return null
        return tag.split(' ').joinToString(" ") { word ->
            if (word in UPPER) word.uppercase()
            else word.split('-').joinToString("-") { part -> if (part in UPPER) part.uppercase() else part.replaceFirstChar { it.uppercase() } }
        }
    }

    /** Words that make a subgenre of the genre after them ("Symphonic Deathcore" is Deathcore). */
    private val MODIFIERS = setOf(
        "progressive", "symphonic", "technical", "brutal", "blackened", "melodic", "atmospheric", "modern",
        "epic", "slam", "orchestral", "cinematic", "raw", "old", "school", "old-school", "dissonant", "mathy",
        "experimental", "avant-garde",
    )

    /** The genre [tag] belongs to, for shares and profile tags: its leading modifiers dropped. */
    fun family(tag: String): String {
        val words = tag.split(' ')
        var i = 0
        while (i < words.size - 1 && words[i].lowercase() in MODIFIERS) i++
        return words.drop(i).joinToString(" ")
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            val o = JSONObject()
            cache.forEach { (k, e) -> o.put(k, JSONObject().put("g", JSONArray(e.genres)).put("t", e.at)) }
            f.writeText(o.toString())
        }
    }
}
