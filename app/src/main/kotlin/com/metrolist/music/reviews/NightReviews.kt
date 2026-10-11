/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.reviews

import android.content.Context
import com.metrolist.music.catalog.Catalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The albums drxwnslvt reviews in the Telegram channel, as the channel's site lists them
 * (tgzip.drxwnslvt.workers.dev builds `data/reviews.json` from the posts). Only the facts are taken
 * — artist, album, year, rating, genres, post date — never the review text; the album itself is
 * found in the app's own catalog when it is opened.
 */
object NightReviews {
    data class Review(
        val id: String,
        /** Telegram message id of the post. */
        val messageId: Long,
        /** Post date, "yyyy-MM-dd". */
        val postDate: String,
        val artist: String,
        val album: String,
        val year: Int?,
        val rating: Double,
        val genres: List<String>,
        val country: String,
        val label: String,
        /** Listener popularity as the site states it (Last.fm-based). */
        val popularity: Int,
        val coverUrl: String?,
    )

    data class State(
        val reviews: List<Review> = emptyList(),
        val loading: Boolean = false,
        /** When the site last rebuilt the list from the channel, ISO time. */
        val generatedAt: String? = null,
        /** Reviews that appeared since the list was last opened. */
        val newIds: Set<String> = emptySet(),
        val error: Boolean = false,
    )

    private const val BASE = "https://tgzip.drxwnslvt.workers.dev/"
    private const val DATA_URL = BASE + "data/reviews.json"
    private const val CACHE_FILE = "night_reviews.json"
    private const val PREFS = "night_reviews"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val refreshLock = Mutex()

    /**
     * Shows what was fetched before at once, then asks the site again — every time the list is
     * opened, so a new post shows up without anything to press.
     */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val app = context.applicationContext
            val cache = File(app.filesDir, CACHE_FILE)
            if (_state.value.reviews.isEmpty() && cache.exists()) {
                runCatching { parse(cache.readText()) }.getOrNull()?.let { (list, at) ->
                    _state.value = _state.value.copy(reviews = list, generatedAt = at)
                }
            }
            _state.value = _state.value.copy(loading = true, error = false)
            val text = runCatching {
                client.newCall(Request.Builder().url("$DATA_URL?t=${System.currentTimeMillis()}").build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    r.body?.string() ?: error("empty")
                }
            }.onFailure { Timber.w(it, "NightReviews: fetch failed") }.getOrNull()
            val parsed = text?.let { runCatching { parse(it) }.getOrNull() }
            if (parsed == null) {
                _state.value = _state.value.copy(loading = false, error = _state.value.reviews.isEmpty())
                return@withLock
            }
            runCatching { cache.writeText(text) }
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getStringSet("seen", null)
            val ids = parsed.first.map { it.id }.toSet()
            // The first time nothing counts as new; afterwards whatever wasn't there before.
            val fresh = if (seen == null) emptySet() else ids - seen
            prefs.edit().putStringSet("seen", ids).apply()
            _state.value = State(
                reviews = parsed.first,
                loading = false,
                generatedAt = parsed.second,
                newIds = _state.value.newIds + fresh,
            )
        }
    }

    private fun parse(text: String): Pair<List<Review>, String?> {
        val root = JSONObject(text)
        val array = root.getJSONArray("reviews")
        val list = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val artist = o.optString("ar").trim()
            val album = o.optString("al").trim()
            if (artist.isEmpty() || album.isEmpty()) return@mapNotNull null
            Review(
                id = o.optString("id").ifBlank { "$artist|$album" },
                messageId = o.optLong("mid"),
                postDate = o.optString("d"),
                artist = artist,
                album = album,
                year = o.optInt("y").takeIf { it > 0 },
                rating = o.optDouble("r", 0.0).takeUnless { it.isNaN() } ?: 0.0,
                genres = o.optJSONArray("g")?.let { g -> (0 until g.length()).map { g.optString(it) }.filter { it.isNotBlank() } }.orEmpty(),
                country = o.optString("c"),
                label = o.optString("l").takeUnless { it == "[no label]" }.orEmpty(),
                popularity = o.optInt("pop"),
                coverUrl = o.optString("img").takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else BASE + it },
            )
        }
        return list to root.optString("generatedAt").takeIf { it.isNotBlank() }
    }

    /**
     * The catalog album (Spotify / Yandex Music id) of [review], found by artist + title. Remembered,
     * so an album opens at once the next time.
     */
    suspend fun findAlbum(context: Context, review: Review): String? = withContext(Dispatchers.IO) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("album:${review.id}", null)?.let { return@withContext it }
        val wantedTitle = normalize(review.album)
        val wantedArtist = normalize(review.artist)
        val queries = listOf("${review.artist} ${review.album}", review.album)
        for (query in queries) {
            val albums = runCatching { Catalog.search(query, listOf("album"), 20).getOrNull()?.albums?.items }
                .getOrNull().orEmpty()
            val best = albums
                .map { album ->
                    val title = normalize(album.name)
                    val artistOk = album.artists.any { normalize(it.name) == wantedArtist } ||
                        album.artists.any { normalize(it.name).contains(wantedArtist) || wantedArtist.contains(normalize(it.name)) }
                    val titleScore = when {
                        title == wantedTitle -> 3
                        title.startsWith(wantedTitle) || wantedTitle.startsWith(title) -> 2
                        title.contains(wantedTitle) || wantedTitle.contains(title) -> 1
                        else -> 0
                    }
                    val yearOk = review.year == null || album.releaseDate?.take(4)?.toIntOrNull() == review.year
                    Triple(album, if (artistOk) titleScore else 0, yearOk)
                }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Triple<*, Int, Boolean>> { it.second }.thenByDescending { it.third })
                .firstOrNull()?.first
            if (best != null && best.id.isNotBlank()) {
                prefs.edit().putString("album:${review.id}", best.id).apply()
                return@withContext best.id
            }
        }
        null
    }

    /** Lower case, accents and punctuation gone, "deluxe"/"remaster" notes dropped. */
    private fun normalize(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[(\\[].*?(deluxe|remaster|edition|version|expanded)[^)\\]]*[)\\]]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
