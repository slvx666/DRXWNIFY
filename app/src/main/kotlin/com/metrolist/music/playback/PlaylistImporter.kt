/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.metrolist.innertube.YouTube
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.constants.MetadataSource
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.utils.PlaylistImportParser
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Builds a local playlist from an imported list. Every line is looked up in the catalog exactly as
 * the search screen would (so tracks come with covers, albums, artists) and the closest match that
 * clears the strict title/artist/length gate is taken; lines without such a match are reported,
 * never replaced by a guess. Nothing is downloaded.
 *
 * A channel export can hold thousands of tracks, so the import:
 *  - runs in [PlaylistImportService] (a foreground service with a progress notification), not tied
 *    to the screen — leaving the app no longer stops it;
 *  - creates the playlist first (it shows in the library at once) and adds tracks in batches;
 *  - keeps its position in a file, so if Android kills the app it goes on where it stopped.
 */
object PlaylistImporter {
    data class Progress(
        val name: String,
        val total: Int,
        val done: Int,
        val found: Int,
        val notFound: List<String>,
        val playlistId: String?,
        val finished: Boolean,
    )

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    private fun jobFile(context: Context) = File(context.filesDir, "playlist_import.json")

    fun isRunning(context: Context): Boolean = jobFile(context).exists()

    /** Clears a finished import from the screen. */
    fun reset() {
        if (_progress.value?.finished == true) _progress.value = null
    }

    /**
     * Saves the job and hands it to the service. [targetPlaylistId]: an existing playlist to add to
     * instead of a new one. [localSongIds]: the user's own audio files (already in the database,
     * see [LocalAudioImport]) that go into the playlist as they are.
     */
    fun start(
        context: Context,
        name: String,
        parsed: PlaylistImportParser.Parsed,
        targetPlaylistId: String? = null,
        localSongIds: List<String> = emptyList(),
    ) {
        val job = JSONObject().apply {
            put("name", name)
            targetPlaylistId?.let { put("playlistId", it) }
            put("local", JSONArray(localSongIds))
            put("links", JSONArray(parsed.links))
            put("entries", JSONArray(parsed.entries.map { e ->
                JSONObject().put("a", e.artist ?: "").put("t", e.title).put("d", e.durationSec ?: 0)
            }))
            put("index", 0)
            put("found", 0)
            put("notFound", JSONArray())
        }
        jobFile(context).writeText(job.toString())
        _progress.value = Progress(name, parsed.entries.size + parsed.links.size + localSongIds.size, 0, 0, emptyList(), targetPlaylistId, false)
        ContextCompat.startForegroundService(context, Intent(context, PlaylistImportService::class.java))
    }

    /** Runs (or resumes) the saved job; called by the service. */
    suspend fun run(context: Context, database: MusicDatabase, onProgress: (Progress) -> Unit) {
        val file = jobFile(context)
        val job = runCatching { JSONObject(file.readText()) }.getOrNull() ?: run { file.delete(); return }
        val mapper = SpotifyYouTubeMapper(database)
        Catalog.ensureSearchable(Catalog.source ?: MetadataSource.SPOTIFY)
        val name = job.getString("name")

        // The playlist exists from the first moment, so it is in the library while it fills.
        val playlistId = job.optString("playlistId").takeIf { it.isNotBlank() } ?: run {
            val playlist = PlaylistEntity(name = name, bookmarkedAt = java.time.LocalDateTime.now(), isEditable = true)
            database.query { insert(playlist) }
            job.put("playlistId", playlist.id)
            file.writeText(job.toString())
            playlist.id
        }

        val entries = job.getJSONArray("entries").let { a ->
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                PlaylistImportParser.Entry(o.optString("a").takeIf { it.isNotBlank() }, o.getString("t"), o.optInt("d").takeIf { it > 0 })
            }
        }.toMutableList()
        val added = HashSet<String>()

        // The user's own files first: nothing to look up.
        job.optJSONArray("local")?.let { a -> (0 until a.length()).map { a.getString(it) } }?.takeIf { it.isNotEmpty() }?.let { local ->
            addSongIds(database, playlistId, local)
            job.put("found", job.optInt("found") + local.size)
            job.remove("local")
            file.writeText(job.toString())
        }

        // Links once: Spotify lists go in as they are, a YouTube playlist becomes lines.
        if (!job.optBoolean("linksDone")) {
            val links = job.optJSONArray("links")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            for (link in links) {
                val direct = mutableListOf<SpotifyTrack>()
                runCatching { expandLink(link, direct, entries) }.onFailure { Timber.w(it, "PlaylistImporter: link %s", link) }
                addTracks(database, mapper, playlistId, direct, added)
                job.put("found", job.optInt("found") + direct.size)
            }
            job.put("entries", JSONArray(entries.map { e -> JSONObject().put("a", e.artist ?: "").put("t", e.title).put("d", e.durationSec ?: 0) }))
            job.put("linksDone", true)
            file.writeText(job.toString())
        }

        var index = job.optInt("index")
        var found = job.optInt("found")
        val notFound = job.optJSONArray("notFound")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toMutableList()
        fun publish(finished: Boolean) {
            val p = Progress(name, entries.size, index, found, notFound.toList(), playlistId, finished)
            _progress.value = p
            onProgress(p)
        }
        publish(false)

        val gate = Semaphore(CONCURRENT_LOOKUPS)
        while (index < entries.size) {
            val batch = entries.subList(index, (index + BATCH).coerceAtMost(entries.size))
            val results = coroutineScope {
                batch.map { entry -> async { gate.withPermit { lookupWithRetry(entry) } } }.awaitAll()
            }
            val tracks = results.filterNotNull()
            batch.forEachIndexed { i, entry -> if (results[i] == null) notFound += entry.label }
            addTracks(database, mapper, playlistId, tracks, added)
            found += tracks.size
            index += batch.size
            job.put("index", index).put("found", found).put("notFound", JSONArray(notFound))
            file.writeText(job.toString())
            publish(false)
        }
        publish(true)
        file.delete()
    }

    private suspend fun addTracks(
        database: MusicDatabase,
        mapper: SpotifyYouTubeMapper,
        playlistId: String,
        tracks: List<SpotifyTrack>,
        added: MutableSet<String>,
    ) {
        val fresh = tracks.filter { added.add(it.id) }
        if (fresh.isEmpty()) return
        addSongIds(database, playlistId, fresh.map { mapper.persistWithoutResolving(it).id })
    }

    /** Adds [ids] to the end of the playlist, skipping what it already holds (adding to an existing one). */
    private suspend fun addSongIds(database: MusicDatabase, playlistId: String, ids: List<String>) {
        val present = database.playlistSongs(playlistId).firstOrNull().orEmpty().mapTo(HashSet()) { it.map.songId }
        val missing = ids.distinct().filter { it !in present }
        if (missing.isEmpty()) return
        database.playlist(playlistId).firstOrNull()?.let { database.addSongToPlaylist(it, missing) }
    }

    /** A failed request (rate limit, a blip) is not "not found": tried again a few times. */
    private suspend fun lookupWithRetry(entry: PlaylistImportParser.Entry): SpotifyTrack? {
        repeat(3) { attempt ->
            val result = runCatching { lookup(entry) }
            result.getOrNull()?.let { return it.takeIf { t -> t.id.isNotBlank() } }
            if (result.isSuccess) return null
            delay(1_500L * (attempt + 1))
        }
        return null
    }

    /** Null = searched, no match; throws = could not search. */
    private suspend fun lookup(entry: PlaylistImportParser.Entry): SpotifyTrack? {
        val query = listOfNotNull(entry.artist, entry.title).joinToString(" ")
        val results = Catalog.search(query, listOf("track"), SEARCH_LIMIT).getOrThrow().tracks?.items.orEmpty()
        if (results.isEmpty()) return null
        val byId = results.associateBy { it.id }
        val match = SpotifyMapper.selectBestMatch(
            spotifyTitle = entry.title,
            spotifyPrimaryArtist = entry.artist.orEmpty(),
            spotifyArtistsAll = entry.artist.orEmpty(),
            spotifyDurationMs = (entry.durationSec ?: 0) * 1000,
            candidates = results.map {
                SpotifyMapper.Candidate(it.id, it.name, it.artists.joinToString(", ") { a -> a.name }, it.durationMs / 1000)
            },
        ) as? SpotifyMapper.MatchResult.Matched ?: return null
        return byId[match.id]
    }

    private suspend fun expandLink(
        link: String,
        direct: MutableList<SpotifyTrack>,
        entries: MutableList<PlaylistImportParser.Entry>,
    ) {
        val spotifyId = Regex("open\\.spotify\\.com/(?:intl-[a-z]+/)?(playlist|album)/([A-Za-z0-9]+)").find(link)
        if (spotifyId != null) {
            val (kind, id) = spotifyId.destructured
            if (kind == "album") {
                Catalog.album(id).getOrNull()?.tracks?.items?.let(direct::addAll)
            } else {
                var offset = 0
                while (offset < MAX_LINK_TRACKS) {
                    val page = Catalog.playlistTracks(id, 100, offset).getOrNull() ?: break
                    direct += page.items.mapNotNull { it.track?.takeIf { t -> !t.isLocal } }
                    offset += 100
                    if (page.items.isEmpty() || offset >= page.total) break
                }
            }
            return
        }
        val listId = Regex("[?&]list=([A-Za-z0-9_-]+)").find(link)?.groupValues?.get(1) ?: return
        val page = YouTube.playlist(listId).getOrNull() ?: return
        val songs = page.songs.toMutableList()
        var continuation = page.songsContinuation
        while (continuation != null && songs.size < MAX_LINK_TRACKS) {
            val more = YouTube.playlistContinuation(continuation).getOrNull() ?: break
            songs += more.songs
            continuation = more.continuation
        }
        entries += songs.map { PlaylistImportParser.Entry(it.artists.firstOrNull()?.name, it.title, it.duration) }
    }

    private const val CONCURRENT_LOOKUPS = 3
    private const val BATCH = 24
    private const val SEARCH_LIMIT = 8
    private const val MAX_LINK_TRACKS = 5000
}
