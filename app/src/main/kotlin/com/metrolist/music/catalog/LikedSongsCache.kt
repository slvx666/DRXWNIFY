/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.catalog

import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The whole Liked Songs list of the connected account, kept in memory and on disk. Opening the
 * screen used to fetch every page again (80 requests for 4000 tracks), each time: now the last list
 * shows at once and the account is only asked whether it changed.
 */
object LikedSongsCache {
    /** Younger than this, the list is shown without asking the account at all. */
    const val FRESH_MS = 10 * 60_000L

    @Serializable
    private data class Snapshot(val total: Int, val tracks: List<SpotifyTrack>)

    class Cached(val tracks: List<SpotifyTrack>, val total: Int, val ageMs: Long)

    private class Entry(val account: String, val at: Long, val snapshot: Snapshot)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Volatile
    private var memory: Entry? = null

    @Volatile
    private var dir: File? = null

    fun setDir(cacheDir: File) {
        dir = File(cacheDir, "library_cache").also { it.mkdirs() }
    }

    private fun fileOf(account: String) = dir?.let { File(it, "liked_${account.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json") }

    @Synchronized
    fun get(account: String): Cached? {
        val entry = memory?.takeIf { it.account == account } ?: runCatching {
            val file = fileOf(account)?.takeIf { it.exists() } ?: return null
            Entry(account, file.lastModified(), json.decodeFromString(Snapshot.serializer(), file.readText()))
                .also { memory = it }
        }.getOrNull() ?: return null
        return Cached(entry.snapshot.tracks, entry.snapshot.total, System.currentTimeMillis() - entry.at)
    }

    @Synchronized
    fun put(account: String, tracks: List<SpotifyTrack>, total: Int) {
        val snapshot = Snapshot(total, tracks)
        memory = Entry(account, System.currentTimeMillis(), snapshot)
        runCatching { fileOf(account)?.writeText(json.encodeToString(Snapshot.serializer(), snapshot)) }
    }

    /** The account said nothing changed: the list counts as fresh again. */
    @Synchronized
    fun touch(account: String) {
        val entry = memory?.takeIf { it.account == account } ?: return
        memory = Entry(account, System.currentTimeMillis(), entry.snapshot)
        runCatching { fileOf(account)?.setLastModified(System.currentTimeMillis()) }
    }

    /** A track was liked somewhere in the app: the next opening asks the account (one request). */
    @Synchronized
    fun markStale() {
        memory = memory?.let { Entry(it.account, 0L, it.snapshot) }
        dir?.listFiles { f -> f.name.startsWith("liked_") }?.forEach { it.setLastModified(1000L) }
    }

    /** A track was unliked: it leaves the kept list right away. */
    @Synchronized
    fun remove(trackId: String) {
        // Not read this run: the copy on disk is checked with the account on the next opening.
        val entry = memory ?: return markStale()
        val left = entry.snapshot.tracks.filterNot { it.id == trackId }
        if (left.size == entry.snapshot.tracks.size) return
        put(entry.account, left, (entry.snapshot.total - 1).coerceAtLeast(left.size))
    }
}
