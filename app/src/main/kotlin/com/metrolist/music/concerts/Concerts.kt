/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.concerts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * One gig in the poster. The geography is kept as separate pieces (region → locality → venue) so
 * more cities can be added later by only configuring sources; for now Saint Petersburg and the
 * Leningrad oblast.
 */
data class Concert(
    /** Stable across refreshes: the source id, or the merge key when several sources agree. */
    val id: String,
    val title: String,
    /** Start, epoch millis. */
    val start: Long,
    val venue: String,
    val locality: String,
    val address: String = "",
    /** The cheapest ticket in roubles; 0 = free, null = not stated. */
    val price: Int? = null,
    val genres: List<String> = emptyList(),
    val imageUrl: String? = null,
    val url: String,
    /** Every source that listed the gig. */
    val sources: List<String> = emptyList(),
    /** The venue's own announcement (VK / Telegram), when a source links it. */
    val altUrl: String? = null,
    /** False when the announcement gives no time (the start is then noon). */
    val timeKnown: Boolean = true,
) {
    val isFree get() = price == 0
    val date: LocalDate get() = java.time.Instant.ofEpochMilli(start).atZone(ZONE).toLocalDate()

    /** Broad genre families (Метал, Рок, Панк …) — what the filters and alerts work with. */
    val families: Set<String> get() = Genres.families(genres)

    /** Saint Petersburg or the oblast around it. */
    val region: String get() = if (locality.isBlank() || locality.startsWith("Санкт")) Genres.REGION_SPB else Genres.REGION_OBLAST

    /** The acts, as the title lists them. */
    val performers: List<String>
        get() = title.split(',', '/', '+', '•').map { it.trim() }.filter { it.length in 2..60 }.distinct()

    /** What a map app is asked for: the street address if known, else the venue by name. */
    val mapQuery: String
        get() = listOf(venue, address.ifBlank { null }, locality.ifBlank { "Санкт-Петербург" }).filterNotNull().joinToString(", ")

    companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    }
}

/**
 * The poster's data: adapters for the sources, one merged list, a cache and the on/off switch.
 *
 * Sources (see the research notes in the session): concertinfo.ru — rock/metal poster for all of
 * Russia with genres and prices, one page holds every upcoming gig; kovelt.ru — the underground
 * scene of Saint Petersburg, schema.org events per day. Both are read at most every few hours and
 * only for the facts (title, date, venue, price, genre, link back) — the tickets and the full
 * description stay on the site the gig came from.
 */
object Concerts {
    data class State(
        val concerts: List<Concert> = emptyList(),
        val loading: Boolean = false,
        val updatedAt: Long = 0L,
        val error: Boolean = false,
        /** Per source: how many gigs it gave last time, or null when it failed. */
        val sources: Map<String, Int?> = emptyMap(),
    )

    private const val PREFS = "concerts"
    private const val CACHE_FILE = "concerts_cache.json"
    private const val TTL_MS = 4 * 60 * 60 * 1000L

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val lock = Mutex()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ---- switch -------------------------------------------------------------------------------

    private val _enabled = MutableStateFlow<Boolean?>(null)

    /** Whether the poster (home section and alerts) is on. On by default. */
    fun enabledFlow(context: Context): StateFlow<Boolean?> {
        if (_enabled.value == null) _enabled.value = isEnabled(context)
        return _enabled
    }

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)

    fun setEnabled(context: Context, on: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
        _enabled.value = on
        if (on) ConcertAlerts.schedule(app) else ConcertAlerts.cancel(app)
    }

    // ---- loading ------------------------------------------------------------------------------

    /** Cache first, then the sources when the cache is older than [TTL_MS] (or [force]). */
    suspend fun refresh(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            val app = context.applicationContext
            val cache = File(app.filesDir, CACHE_FILE)
            if (_state.value.concerts.isEmpty() && cache.exists()) {
                runCatching { decode(cache.readText()) }.getOrNull()?.let { (list, at) ->
                    _state.value = _state.value.copy(concerts = list.upcoming(), updatedAt = at)
                }
            }
            if (!force && System.currentTimeMillis() - _state.value.updatedAt < TTL_MS && _state.value.concerts.isNotEmpty()) {
                return@withLock
            }
            _state.value = _state.value.copy(loading = true, error = false)
            val (merged, bySource) = fetchAll()
            if (merged == null) {
                _state.value = _state.value.copy(loading = false, error = _state.value.concerts.isEmpty(), sources = bySource)
                return@withLock
            }
            val now = System.currentTimeMillis()
            runCatching { cache.writeText(encode(merged, now)) }
            _state.value = State(concerts = merged.upcoming(), loading = false, updatedAt = now, sources = bySource)
        }
    }

    /** Synchronous variant for the alert receiver: fresh data when possible, else the cache. */
    suspend fun load(context: Context): List<Concert> {
        refresh(context)
        return _state.value.concerts
    }

    private fun List<Concert>.upcoming(): List<Concert> {
        val from = System.currentTimeMillis() - 3 * 60 * 60 * 1000L
        return filter { it.start >= from }.sortedBy { it.start }
    }

    /**
     * Every source at once. Concertinfo first in the merge (genres and prices are most reliable
     * there), then Ковельт (the underground, "free"), then the Telegram posters (what the others
     * miss: small venues' own announcements).
     */
    private suspend fun fetchAll(): Pair<List<Concert>?, Map<String, Int?>> = coroutineScope {
        val sources = listOf<Pair<String, ((String) -> String) -> List<Concert>>>(
            "Concertinfo" to Concertinfo::fetch,
            "Ковельт" to Kovelt::fetch,
            "Telegram" to TelegramPosters::fetch,
        )
        val results = sources.map { (name, fetch) ->
            name to async {
                val started = System.currentTimeMillis()
                runCatching { fetch(::get) }
                    .onSuccess { Timber.i("Concerts: %s gave %d gigs in %d ms", name, it.size, System.currentTimeMillis() - started) }
                    .onFailure { Timber.w("Concerts: %s failed: %s", name, com.metrolist.music.resolver.AudioDiagnostics.explain(it)) }
                    .getOrNull()
            }
        }.map { (name, job) -> name to job.await() }
        val bySource = results.associate { (name, list) -> name to list?.size }
        if (results.all { it.second == null }) return@coroutineScope null to bySource
        merge(results.flatMap { it.second.orEmpty() }) to bySource
    }

    private fun get(url: String): String =
        client.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) Drxwnify").build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} for $url")
            r.body?.string() ?: error("empty body for $url")
        }

    // ---- merging ------------------------------------------------------------------------------

    private fun norm(s: String) =
        s.lowercase(Locale.ROOT).replace('ё', 'е').filter { it.isLetterOrDigit() }

    /**
     * One record per gig: same day, same venue (or the same lead act at a venue whose names
     * differ), whatever the sources call it. The richer record wins field by field.
     */
    internal fun merge(all: List<Concert>): List<Concert> {
        val out = LinkedHashMap<String, Concert>()
        fun lead(c: Concert) = norm(c.title.split(',', '/', '+', '(').first())
        for (c in all.sortedBy { it.start }) {
            val venueKey = norm(c.venue)
            val lead = lead(c)
            val exact = "${c.date}|$venueKey|$lead"
            val key = out.entries.firstOrNull { (_, o) ->
                o.date == c.date && norm(o.venue) == venueKey && lead.length > 2 &&
                    (lead(o) == lead || norm(o.title).contains(lead) || norm(c.title).contains(lead(o)))
            }?.key
            if (key == null) {
                out[exact] = c
            } else {
                val o = out.getValue(key)
                out[key] = o.copy(
                    price = o.price ?: c.price,
                    genres = (o.genres + c.genres).distinct(),
                    imageUrl = o.imageUrl ?: c.imageUrl,
                    address = o.address.ifBlank { c.address },
                    sources = (o.sources + c.sources).distinct(),
                    altUrl = o.altUrl ?: c.altUrl ?: if (c.url != o.url) c.url else null,
                    timeKnown = o.timeKnown || c.timeKnown,
                    start = if (!o.timeKnown && c.timeKnown) c.start else o.start,
                    title = if (c.title.length > o.title.length && c.title.contains(o.title, true)) c.title else o.title,
                )
            }
        }
        return out.values.toList()
    }

    // ---- cache --------------------------------------------------------------------------------

    private fun encode(list: List<Concert>, at: Long): String {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id).put("t", c.title).put("s", c.start).put("v", c.venue).put("l", c.locality)
                    .put("a", c.address).put("p", c.price ?: JSONObject.NULL)
                    .put("g", JSONArray(c.genres)).put("i", c.imageUrl ?: JSONObject.NULL)
                    .put("u", c.url).put("src", JSONArray(c.sources))
                    .put("alt", c.altUrl ?: JSONObject.NULL).put("tk", c.timeKnown),
            )
        }
        return JSONObject().put("at", at).put("list", arr).toString()
    }

    private fun decode(text: String): Pair<List<Concert>, Long> {
        val root = JSONObject(text)
        val arr = root.getJSONArray("list")
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Concert(
                id = o.getString("id"), title = o.getString("t"), start = o.getLong("s"),
                venue = o.getString("v"), locality = o.optString("l"), address = o.optString("a"),
                price = if (o.isNull("p")) null else o.getInt("p"),
                genres = o.getJSONArray("g").let { g -> (0 until g.length()).map { g.getString(it) } },
                imageUrl = if (o.isNull("i")) null else o.getString("i"),
                url = o.getString("u"),
                altUrl = if (o.isNull("alt")) null else o.optString("alt").ifBlank { null },
                timeKnown = o.optBoolean("tk", true),
                sources = o.getJSONArray("src").let { g -> (0 until g.length()).map { g.getString(it) } },
            )
        }
        return list to root.optLong("at")
    }

    /** Genre families present in [list], in chip order. */
    fun genresOf(list: List<Concert>): List<String> = Genres.presentIn(list)

    fun venuesOf(list: List<Concert>): List<String> =
        list.groupingBy { it.venue }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    // ---- the home picks' taste ------------------------------------------------------------------

    data class Taste(val genres: Set<String>, val maxPrice: Int)

    private val _taste = MutableStateFlow<Taste?>(null)

    fun tasteFlow(context: Context): StateFlow<Taste?> {
        if (_taste.value == null) _taste.value = taste(context)
        return _taste
    }

    fun taste(context: Context): Taste {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Taste(p.getStringSet("taste", null) ?: Genres.DEFAULT_TASTE, p.getInt("taste_price", 500))
    }

    fun setTaste(context: Context, taste: Taste) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("taste", taste.genres).putInt("taste_price", taste.maxPrice).apply()
        _taste.value = taste
    }
}
