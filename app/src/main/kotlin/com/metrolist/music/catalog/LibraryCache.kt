/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.catalog

import com.metrolist.spotify.models.SpotifyLibraryEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The account library as it was last loaded, per filter — for the whole process and on disk. The
 * Library screen used to start empty and download everything again each time it was opened; now it
 * shows this at once and refreshes quietly when the copy is older than [FRESH_MS].
 */
object LibraryCache {
    const val FRESH_MS = 10 * 60_000L

    private data class Entry(val at: Long, val items: List<SpotifyLibraryEntry>)

    private val memory = ConcurrentHashMap<String, Entry>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = ListSerializer(SpotifyLibraryEntry.serializer())

    @Volatile
    private var dir: File? = null

    fun setDir(cacheDir: File) {
        dir = File(cacheDir, "library_cache").also { it.mkdirs() }
    }

    private fun keyOf(filter: String, account: String) = "${account}_$filter"

    private fun fileOf(key: String) = dir?.let { File(it, key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json") }

    /** Items and their age (ms), or null when this library was never loaded. */
    fun get(filter: String, account: String): Pair<List<SpotifyLibraryEntry>, Long>? {
        val key = keyOf(filter, account)
        val entry = memory[key] ?: runCatching {
            val file = fileOf(key)?.takeIf { it.exists() } ?: return null
            Entry(file.lastModified(), json.decodeFromString(serializer, file.readText())).also { memory[key] = it }
        }.getOrNull() ?: return null
        return entry.items to (System.currentTimeMillis() - entry.at)
    }

    fun put(filter: String, account: String, items: List<SpotifyLibraryEntry>) {
        val key = keyOf(filter, account)
        memory[key] = Entry(System.currentTimeMillis(), items)
        runCatching { fileOf(key)?.writeText(json.encodeToString(serializer, items)) }
    }

    /** Something in the library changed (an album saved…): the next opening refreshes it behind the list. */
    fun markStale() {
        memory.replaceAll { _, e -> e.copy(at = 0L) }
        dir?.listFiles()?.forEach { it.setLastModified(1000L) }
    }

    fun clear() {
        memory.clear()
        dir?.listFiles()?.forEach { it.delete() }
    }
}
