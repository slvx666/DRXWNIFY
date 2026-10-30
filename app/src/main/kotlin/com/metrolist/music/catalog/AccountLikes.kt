/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.catalog

import android.content.Context
import com.metrolist.music.resolver.VkMusic
import com.metrolist.music.utils.SpotifyTokenManager
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyLibraryEntry
import com.metrolist.spotify.models.SpotifyPaging
import com.metrolist.spotify.models.SpotifySavedTrack
import com.metrolist.yandex.YandexMusic
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * What the listener liked and followed in their linked accounts — Spotify, Yandex Music, VK ("My
 * music") — counted like the likes and follows made in the app: for the newcomer threshold and the
 * taste the recommendations are built from. Kept on the device per account, so a source that doesn't
 * answer this time (no VPN, an expired VK token) still counts with what it gave last time.
 */
object AccountLikes {
    /** A liked track: its title and its first artists (catalog id when there is one, and name). */
    data class Track(val title: String, val artists: List<Pair<String?, String>>) {
        val artist: String get() = artists.firstOrNull()?.second.orEmpty()
    }

    data class Likes(val tracks: List<Track>, val artists: List<String>) {
        operator fun plus(other: Likes) = Likes(tracks + other.tracks, artists + other.artists)
    }

    private const val FILE = "account_likes.json"

    /** Enough for the taste (and far past the newcomer threshold) without paging a huge library. */
    private const val MAX_TRACKS = 300
    private const val PAGE = 50
    private const val FRESH_MS = 30 * 60_000L
    private const val SOURCE_TIMEOUT_MS = 20_000L

    private val mutex = Mutex()
    private var cached: Pair<Long, Likes>? = null

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun file() = appContext?.let { File(it.filesDir, FILE) }

    private fun saved(): JSONObject = runCatching { JSONObject(file()!!.readText()) }.getOrDefault(JSONObject())

    /** What the accounts gave last time, without asking them (for when there is no time to wait). */
    fun lastKnown(): Likes {
        cached?.let { return it.second }
        val saved = saved()
        return saved.keys().asSequence().mapNotNull { saved.optJSONObject(it)?.let(::fromJson) }
            .fold(Likes(emptyList(), emptyList())) { a, b -> a + b }
    }

    /** Likes and follows of every linked account (asked again at most every [FRESH_MS]). */
    suspend fun all(): Likes = mutex.withLock {
        cached?.takeIf { System.currentTimeMillis() - it.first < FRESH_MS }?.let { return@withLock it.second }
        Catalog.awaitAccounts()
        val saved = saved()
        val state = Catalog.state.value
        val sources = buildMap<String, suspend () -> Likes?> {
            if (state.spotifyConnected) put("spotify", ::spotify)
            if (state.yandexConnected) put("yandex", ::yandex)
            if (VkMusic.isReady) put("vk", ::vk)
        }
        val fresh = coroutineScope {
            sources.mapValues { (name, fetch) ->
                async {
                    withTimeoutOrNull(SOURCE_TIMEOUT_MS) { runCatching { fetch() }.getOrNull() }
                        .also { if (it == null) Timber.i("AccountLikes: %s didn't answer, last copy kept", name) }
                }
            }.mapValues { it.value.await() }
        }
        // An account no longer linked drops out; one that didn't answer keeps its last copy.
        val out = JSONObject()
        var all = Likes(emptyList(), emptyList())
        for (name in sources.keys) {
            val likes = fresh[name] ?: saved.optJSONObject(name)?.let(::fromJson) ?: continue
            out.put(name, toJson(likes))
            all += likes
        }
        runCatching { file()?.writeText(out.toString()) }
        Timber.i("AccountLikes: %d tracks, %d artists from %s", all.tracks.size, all.artists.size, sources.keys)
        cached = System.currentTimeMillis() to all
        all
    }

    /** Asked again next time (after a like or a follow made here). */
    fun markStale() {
        cached = null
    }

    private suspend fun spotify(): Likes? {
        if (!SpotifyTokenManager.ensureAuthenticated()) return null
        val tracks = pages { offset -> Spotify.likedSongs(PAGE, offset) } ?: return null
        val artists = Spotify.myLibrary("Artists", PAGE, 0).getOrNull()?.let { first ->
            libraryArtists(first) { offset -> Spotify.myLibrary("Artists", PAGE, offset) }
        }.orEmpty()
        return Likes(tracks, artists)
    }

    private suspend fun yandex(): Likes? {
        if (!YandexMusic.isAuthenticated()) return null
        val tracks = pages { offset -> YandexMusic.likedSongs(PAGE, offset) } ?: return null
        val artists = YandexMusic.likedArtists().getOrNull().orEmpty().map { it.first.name }
        return Likes(tracks, artists)
    }

    /** VK has no likes as such: the tracks in "My music" are what the listener chose to keep. */
    private suspend fun vk(): Likes? {
        val tracks = VkMusic.myTracks().getOrNull() ?: return null
        return Likes(tracks.take(MAX_TRACKS).map { Track(it.title, listOf(null to it.artist)) }, emptyList())
    }

    private suspend fun pages(fetch: suspend (Int) -> Result<SpotifyPaging<SpotifySavedTrack>>): List<Track>? {
        val out = mutableListOf<Track>()
        var offset = 0
        while (offset < MAX_TRACKS) {
            val page = fetch(offset).getOrElse { if (offset == 0) return null else break }
            page.items.forEach { saved ->
                val t = saved.track
                out += Track(t.name, t.artists.take(2).map { it.id?.takeIf { id -> id.isNotBlank() } to it.name })
            }
            offset += PAGE
            if (page.items.isEmpty() || offset >= page.total) break
        }
        return out
    }

    private suspend fun libraryArtists(
        first: SpotifyPaging<SpotifyLibraryEntry>,
        fetch: suspend (Int) -> Result<SpotifyPaging<SpotifyLibraryEntry>>,
    ): List<String> {
        val out = first.items.filter { it.kind == SpotifyLibraryEntry.Kind.ARTIST }.map { it.name }.toMutableList()
        var offset = PAGE
        while (offset < first.total && offset < MAX_TRACKS) {
            val page = fetch(offset).getOrNull() ?: break
            out += page.items.filter { it.kind == SpotifyLibraryEntry.Kind.ARTIST }.map { it.name }
            if (page.items.isEmpty()) break
            offset += PAGE
        }
        return out
    }

    private fun toJson(likes: Likes) = JSONObject()
        .put("t", JSONArray(likes.tracks.map { t ->
            JSONObject().put("n", t.title).put("a", JSONArray(t.artists.map { (id, name) -> JSONObject().put("i", id ?: "").put("n", name) }))
        }))
        .put("ar", JSONArray(likes.artists))

    private fun fromJson(o: JSONObject): Likes {
        val t = o.optJSONArray("t") ?: JSONArray()
        val ar = o.optJSONArray("ar") ?: JSONArray()
        return Likes(
            tracks = (0 until t.length()).mapNotNull { i ->
                t.optJSONObject(i)?.let { x ->
                    val a = x.optJSONArray("a") ?: JSONArray()
                    Track(
                        x.optString("n"),
                        (0 until a.length()).mapNotNull { j -> a.optJSONObject(j) }.map { it.optString("i").ifBlank { null } to it.optString("n") },
                    )
                }
            },
            artists = (0 until ar.length()).map { ar.optString(it) }.filter { it.isNotBlank() },
        )
    }

    /** "artist|title" folded, to count a track liked in two places once. */
    fun trackKey(artist: String, title: String): String =
        artist.lowercase().filter { it.isLetterOrDigit() } + "|" +
            title.lowercase().substringBefore(" (").substringBefore(" - ").filter { it.isLetterOrDigit() }

    fun artistKey(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }
}
