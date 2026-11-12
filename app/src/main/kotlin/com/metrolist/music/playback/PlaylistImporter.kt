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
import com.metrolist.music.resolver.AudioFallbackEngine
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.ProviderMatch
import com.metrolist.music.resolver.SourceSearch
import com.metrolist.music.resolver.providers.SoundCloudAudioProvider
import com.metrolist.music.utils.FuzzyTrackMatch
import com.metrolist.music.utils.PlaylistImportParser
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyTrack
import com.metrolist.yandex.YandexIds
import com.metrolist.yandex.YandexMusic
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
        /** The second, slower pass over what the first one missed: (checked, of how many); null before it. */
        val deep: Pair<Int, Int>? = null,
        /** Imports waiting for this one (a SoundCloud profile brings several playlists). */
        val queued: Int = 0,
    )

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    private fun jobFile(context: Context) = File(context.filesDir, "playlist_import.json")

    fun isRunning(context: Context): Boolean = jobFile(context).exists()

    private fun queueFile(context: Context) = File(context.filesDir, "playlist_import_queue.json")

    /** One import waiting in line: a playlist [name] filled from [link], into [targetPlaylistId] if set. */
    data class Queued(val name: String, val link: String, val targetPlaylistId: String? = null)

    private fun readQueue(context: Context): List<Queued> = runCatching {
        val a = JSONArray(queueFile(context).readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Queued(o.getString("name"), o.getString("link"), o.optString("target").takeIf { it.isNotBlank() })
        }
    }.getOrDefault(emptyList())

    private fun writeQueue(context: Context, queue: List<Queued>) {
        val file = queueFile(context)
        if (queue.isEmpty()) {
            file.delete()
            return
        }
        file.writeText(JSONArray(queue.map { q -> JSONObject().put("name", q.name).put("link", q.link).put("target", q.targetPlaylistId ?: "") }).toString())
    }

    /** Several imports one after another; they join the line when one is already running. */
    fun enqueue(context: Context, imports: List<Queued>) {
        if (imports.isEmpty()) return
        val queue = readQueue(context) + imports
        if (isRunning(context)) {
            writeQueue(context, queue)
            return
        }
        writeQueue(context, queue.drop(1))
        val first = queue.first()
        start(context, first.name, PlaylistImportParser.Parsed(emptyList(), listOf(first.link)), first.targetPlaylistId)
    }

    /** Turns the next waiting import into the job; false when the line is empty. */
    fun startNextQueued(context: Context): Boolean {
        val queue = readQueue(context)
        val next = queue.firstOrNull() ?: return false
        writeQueue(context, queue.drop(1))
        cancelled = false
        val parsed = PlaylistImportParser.Parsed(emptyList(), listOf(next.link))
        jobFile(context).writeText(jobOf(next.name, parsed, next.targetPlaylistId, emptyList()).toString())
        return true
    }

    /** Clears a finished import from the screen. */
    fun reset() {
        if (_progress.value?.finished == true) _progress.value = null
    }

    @Volatile
    private var cancelled = false

    /**
     * Stops the import now: the job is forgotten (also one left over from an older version, which
     * otherwise came back every time the screen was opened). What was added so far stays.
     */
    fun cancel(context: Context) {
        cancelled = true
        jobFile(context).delete()
        queueFile(context).delete()
        _progress.value = null
        context.stopService(Intent(context, PlaylistImportService::class.java))
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
        cancelled = false
        jobFile(context).writeText(jobOf(name, parsed, targetPlaylistId, localSongIds).toString())
        _progress.value = Progress(name, parsed.entries.size + parsed.links.size + localSongIds.size, 0, 0, emptyList(), targetPlaylistId, false)
        ContextCompat.startForegroundService(context, Intent(context, PlaylistImportService::class.java))
    }

    private fun jobOf(name: String, parsed: PlaylistImportParser.Parsed, targetPlaylistId: String?, localSongIds: List<String>) =
        JSONObject().apply {
            put("name", name)
            targetPlaylistId?.let { put("playlistId", it) }
            put("local", JSONArray(localSongIds))
            put("links", JSONArray(parsed.links))
            put("entries", JSONArray(parsed.entries.map(::entryToJson)))
            put("index", 0)
            put("found", 0)
            put("notFound", JSONArray())
        }

    /** Runs (or resumes) the saved job; called by the service. */
    suspend fun run(context: Context, database: MusicDatabase, onProgress: (Progress) -> Unit) {
        val file = jobFile(context)
        val job = runCatching { JSONObject(file.readText()) }.getOrNull() ?: run { file.delete(); return }
        val mapper = SpotifyYouTubeMapper(database)
        Catalog.ensureSearchable(Catalog.source ?: MetadataSource.SPOTIFY)
        // No name given: the playlist or album behind the first link names it (else "Import <date>").
        val name = job.getString("name").ifBlank {
            val links = job.optJSONArray("links")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val resolved = links.firstNotNullOfOrNull { link -> runCatching { linkTitle(link) }.getOrNull()?.takeIf { it.isNotBlank() } }
                ?: context.getString(
                    com.metrolist.music.R.string.import_default_name,
                    java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("d MMM")),
                )
            job.put("name", resolved)
            file.writeText(job.toString())
            resolved
        }

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
        // The uploads behind lines from a SoundCloud link: added as they are when the catalog hasn't the track.
        val originals = HashMap<String, ProviderMatch>()
        job.optJSONObject("sc")?.let { o -> o.keys().forEach { key -> o.optJSONObject(key)?.let { originals[key] = matchFromJson(it) } } }

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
                runCatching { expandLink(link, direct, entries, originals) }.onFailure { Timber.w(it, "PlaylistImporter: link %s", link) }
                addTracks(database, mapper, playlistId, direct, added)
                job.put("found", job.optInt("found") + direct.size)
            }
            job.put("entries", JSONArray(entries.map(::entryToJson)))
            job.put("sc", JSONObject().apply { originals.forEach { (key, match) -> put(key, matchToJson(match)) } })
            job.put("linksDone", true)
            file.writeText(job.toString())
        }

        var index = job.optInt("index")
        var found = job.optInt("found")
        val notFound = job.optJSONArray("notFound")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toMutableList()
        // The lines the first pass missed, for the second one.
        val retry = job.optJSONArray("retry")?.let { a -> (0 until a.length()).map { entryFromJson(a.getJSONObject(it)) } }.orEmpty().toMutableList()
        var deepIndex = job.optInt("deepIndex", -1)
        fun publish(finished: Boolean) {
            val deep = if (deepIndex >= 0) deepIndex.coerceAtMost(retry.size) to retry.size else null
            val p = Progress(name, entries.size, index, found, notFound.toList(), playlistId, finished, deep, readQueue(context).size)
            _progress.value = p
            onProgress(p)
        }
        publish(false)

        val gate = Semaphore(CONCURRENT_LOOKUPS)
        while (index < entries.size && !cancelled) {
            val batch = entries.subList(index, (index + BATCH).coerceAtMost(entries.size))
            val results = coroutineScope {
                batch.map { entry -> async { gate.withPermit { lookupWithRetry(entry) } } }.awaitAll()
            }
            // In the list's order: the catalog track, else the SoundCloud upload itself, else "not found".
            val ids = mutableListOf<String>()
            batch.forEachIndexed { i, entry ->
                val track = results[i]
                val original = if (track == null) originals[keyOf(entry)] else null
                when {
                    track != null -> {
                        found++
                        if (added.add(track.id)) ids += mapper.persistWithoutResolving(track).id
                    }
                    original != null -> {
                        found++
                        if (added.add(SourceSearch.catalogIdOf(original))) {
                            ids += com.metrolist.music.ui.component.SelectedTracks.songIdsOf(database, listOf(original))
                        }
                    }
                    else -> {
                        notFound += entry.label
                        retry += entry
                    }
                }
            }
            addSongIds(database, playlistId, ids)
            index += batch.size
            if (cancelled) return
            job.put("index", index).put("found", found).put("notFound", JSONArray(notFound))
                .put("retry", JSONArray(retry.map(::entryToJson)))
            file.writeText(job.toString())
            publish(false)
        }
        if (cancelled) return

        // Second pass: what the quick strict search missed gets a slower, wider one — more ways to
        // write the query, both catalogs, similarity instead of exact words. One line at a time
        // (with a short pause) so a long list does not run into the catalog's rate limit.
        if (deepIndex < 0) deepIndex = 0
        publish(false)
        while (deepIndex < retry.size && !cancelled) {
            val entry = retry[deepIndex]
            val track = runCatching { kotlinx.coroutines.withTimeout(DEEP_TIMEOUT_MS) { deepLookup(entry) } }
                .onFailure { Timber.w(it, "PlaylistImporter: deep lookup %s", entry.label) }
                .getOrNull()
            if (track != null) {
                val before = added.size
                addTracks(database, mapper, playlistId, listOf(track), added)
                if (added.size > before) found++
                notFound.remove(entry.label)
            }
            deepIndex++
            if (cancelled) return
            job.put("deepIndex", deepIndex).put("found", found).put("notFound", JSONArray(notFound))
            file.writeText(job.toString())
            publish(false)
            delay(DEEP_PAUSE_MS)
        }
        if (cancelled) return
        publish(true)
        file.delete()
        startNextQueued(context)
    }

    private fun keyOf(e: PlaylistImportParser.Entry) = e.label.lowercase()

    private fun matchToJson(m: ProviderMatch) = JSONObject()
        .put("id", m.trackId).put("t", m.title).put("a", m.artist).put("ms", m.durationMs ?: 0L).put("img", m.thumbnailUrl ?: "")

    private fun matchFromJson(o: JSONObject) = ProviderMatch(
        provider = AudioProviderId.SOUNDCLOUD,
        trackId = o.getString("id"),
        title = o.optString("t"),
        artist = o.optString("a"),
        durationMs = o.optLong("ms").takeIf { it > 0 },
        confidence = 1.0,
        thumbnailUrl = o.optString("img").takeIf { it.isNotBlank() },
    )

    private fun entryToJson(e: PlaylistImportParser.Entry) =
        JSONObject().put("a", e.artist ?: "").put("t", e.title).put("d", e.durationSec ?: 0)

    private fun entryFromJson(o: JSONObject) =
        PlaylistImportParser.Entry(o.optString("a").takeIf { it.isNotBlank() }, o.getString("t"), o.optInt("d").takeIf { it > 0 })

    private fun candidates(tracks: Collection<SpotifyTrack>) =
        tracks.map { t -> FuzzyTrackMatch.Candidate(t.id, t.name, t.artists.map { it.name }, t.durationMs / 1000) }

    /**
     * The wide search for one line: several spellings of the query (guests moved out of the title,
     * year and track number dropped, title alone, artist alone for typos) in the library's catalog
     * and then the other one, the closest track by [FuzzyTrackMatch].
     */
    private suspend fun deepLookup(entry: PlaylistImportParser.Entry): SpotifyTrack? {
        val titles = FuzzyTrackMatch.titleVariants(entry.title)
        val core = titles.last()
        val artist = entry.artist?.trim().orEmpty()
        val queries = buildList {
            if (artist.isNotEmpty()) {
                add("$artist $core")
                titles.dropLast(1).lastOrNull()?.let { add("$artist $it") }
                add("$core $artist")
            }
            add(core)
            if (artist.isNotEmpty()) add(artist)
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val primary = Catalog.source ?: MetadataSource.SPOTIFY
        val catalogs = buildList {
            add(primary)
            MetadataSource.entries.filter { it != primary }.forEach { other ->
                if (runCatching { Catalog.ensureSearchable(other) }.getOrDefault(false)) add(other)
            }
        }
        for (catalog in catalogs) {
            val pool = LinkedHashMap<String, SpotifyTrack>()
            for (query in queries) {
                val limit = if (query == artist) 30 else 15
                val items = runCatching { Catalog.searchIn(catalog, query, listOf("track"), limit).getOrThrow().tracks?.items.orEmpty() }
                    .getOrElse { delay(1_500L); emptyList() }
                items.forEach { pool.putIfAbsent(it.id, it) }
                // A near-exact hit ends the search; a weaker one waits for what the other spellings bring.
                val best = FuzzyTrackMatch.best(entry.artist, entry.title, entry.durationSec, candidates(pool.values))
                if (best != null && best.titleScore >= 0.97 && best.artistScore >= 0.97) return pool[best.id]
            }
            FuzzyTrackMatch.best(entry.artist, entry.title, entry.durationSec, candidates(pool.values))?.let { return pool[it.id] }
        }
        return null
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
        repeat(2) {
            // A search that hangs (slow network, a throttled catalog) must not hold up the list.
            val result = runCatching { kotlinx.coroutines.withTimeout(LOOKUP_TIMEOUT_MS) { lookup(entry) } }
            result.getOrNull()?.let { return it.takeIf { t -> t.id.isNotBlank() } }
            if (result.isSuccess) return null
            delay(1_000L)
        }
        return null
    }

    /** Null = searched, no match; throws = could not search. */
    private suspend fun lookup(entry: PlaylistImportParser.Entry): SpotifyTrack? {
        lookupOnce(entry, listOfNotNull(entry.artist, entry.title).joinToString(" "))?.let { return it }
        // File and video names carry noise: "(Official Video)", "[HQ]", "feat. …", "prod. …".
        val cleanTitle = cleanForSearch(entry.title)
        val cleanArtist = entry.artist?.let(::cleanForSearch)
        if (cleanTitle == entry.title && cleanArtist == entry.artist) return null
        val cleaned = entry.copy(artist = cleanArtist, title = cleanTitle)
        return lookupOnce(cleaned, listOfNotNull(cleanArtist, cleanTitle).joinToString(" "))
    }

    private val NOISE = Regex(
        "[(\\[][^)\\]]*(official|video|audio|lyric|hq|hd|4k|mv|клип|prod|feat|ft\\.|remaster|visuali)[^)\\]]*[)\\]]" +
            "|\\s(feat|ft)\\.?\\s.*$|\\s+prod\\.?\\s.*$",
        RegexOption.IGNORE_CASE,
    )

    private fun cleanForSearch(s: String): String =
        NOISE.replace(s, " ").replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifBlank { s }

    private suspend fun lookupOnce(entry: PlaylistImportParser.Entry, query: String): SpotifyTrack? {
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
        originals: MutableMap<String, ProviderMatch>,
    ) {
        if ("soundcloud.com/" in link) {
            val soundCloud = AudioFallbackEngine.provider(AudioProviderId.SOUNDCLOUD) as? SoundCloudAudioProvider ?: return
            soundCloud.importTracks(link, MAX_LINK_TRACKS).orEmpty().forEach { t ->
                val entry = PlaylistImportParser.Entry(t.artist, t.title, t.durationSec)
                entries += entry
                t.source?.let { originals[keyOf(entry)] = it }
            }
            return
        }
        if (YandexIds.isLink(link)) {
            val id = YandexMusic.idOfLink(link).getOrThrow() ?: return
            if (YandexIds.parseLink(link) is YandexIds.Link.Album) {
                Catalog.album(id).getOrNull()?.tracks?.items?.let(direct::addAll)
            } else {
                readAllPlaylistTracks(id, direct)
            }
            return
        }
        val spotifyId = SPOTIFY_LINK.find(link)
        if (spotifyId != null) {
            val (kind, id) = spotifyId.destructured
            if (kind == "album") {
                Catalog.album(id).getOrNull()?.tracks?.items?.let(direct::addAll)
            } else {
                readAllPlaylistTracks(id, direct)
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

    private suspend fun readAllPlaylistTracks(id: String, direct: MutableList<SpotifyTrack>) {
        var offset = 0
        while (offset < MAX_LINK_TRACKS) {
            val page = Catalog.playlistTracks(id, 100, offset).getOrNull() ?: break
            direct += page.items.mapNotNull { it.track?.takeIf { t -> !t.isLocal } }
            offset += 100
            if (page.items.isEmpty() || offset >= page.total) break
        }
    }

    /** The name of the playlist or album a link points at, as its own service calls it; null when unknown. */
    suspend fun linkTitle(link: String): String? {
        if (YandexIds.isLink(link)) {
            val id = YandexMusic.idOfLink(link).getOrNull() ?: return null
            return if (YandexIds.parseLink(link) is YandexIds.Link.Album) {
                Catalog.album(id).getOrNull()?.name
            } else {
                Catalog.playlist(id).getOrNull()?.name
            }
        }
        SPOTIFY_LINK.find(link)?.let { m ->
            val (kind, id) = m.destructured
            return if (kind == "album") Catalog.album(id).getOrNull()?.name else Catalog.playlist(id).getOrNull()?.name
        }
        if ("soundcloud.com/" in link) return null
        val listId = Regex("[?&]list=([A-Za-z0-9_-]+)").find(link)?.groupValues?.get(1) ?: return null
        return YouTube.playlist(listId).getOrNull()?.playlist?.title
    }

    private val SPOTIFY_LINK = Regex("open\\.spotify\\.com/(?:intl-[a-z]+/)?(playlist|album)/([A-Za-z0-9]+)")

    private const val CONCURRENT_LOOKUPS = 6
    private const val BATCH = 30
    private const val LOOKUP_TIMEOUT_MS = 12_000L
    private const val SEARCH_LIMIT = 8
    private const val MAX_LINK_TRACKS = 5000
    private const val DEEP_TIMEOUT_MS = 45_000L
    private const val DEEP_PAUSE_MS = 250L
}
