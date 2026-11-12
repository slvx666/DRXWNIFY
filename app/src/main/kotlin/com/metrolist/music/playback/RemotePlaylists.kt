/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.catalog.CrossCatalog
import com.metrolist.music.catalog.VkCatalog
import com.metrolist.music.constants.MetadataSource
import com.metrolist.music.db.MusicDatabase
import com.metrolist.spotify.Spotify
import com.metrolist.yandex.YandexIds
import com.metrolist.yandex.YandexMusic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * The app's playlists created in a music account too, slowly and in the background: first the
 * playlist lives in the app (it plays at once), then the same playlist is made in the main account
 * (Spotify, Yandex Music or VK) and filled a small batch at a time with pauses — there is no hurry,
 * and a burst of writes is what gets an account limited.
 *
 * Every job is kept on disk and goes on after a restart. The steps are CREATE → FILL → DONE; the
 * account's playlist id is saved the moment it exists and the position after every batch that went
 * through, so a job cut short never makes a second playlist nor adds a batch twice.
 *
 * Also keeps which of the app's playlists came from an import (files, links, another account):
 * those are offered for creating in the main account when one is linked later.
 */
object RemotePlaylists {
    enum class Step { CREATE, FILL, DONE, FAILED }

    data class PushJob(
        val localId: String,
        val target: MetadataSource,
        val title: String,
        val step: Step = Step.CREATE,
        val remoteId: String? = null,
        /** How many of the playlist's tracks were handled (added or found missing). */
        val cursor: Int = 0,
        val total: Int = 0,
        /** Tracks the account's catalog didn't have ("artist – title"). */
        val missing: List<String> = emptyList(),
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var database: MusicDatabase? = null

    private val _jobs = MutableStateFlow<List<PushJob>>(emptyList())
    val jobs: StateFlow<List<PushJob>> = _jobs

    private val _localImports = MutableStateFlow(0)

    /** Imported playlists not created in the main account yet (for the offer on the account's page). */
    val localImports: StateFlow<Int> = _localImports

    private var worker: kotlinx.coroutines.Job? = null

    private fun jobsFile() = appContext?.let { File(it.filesDir, "remote_playlists.json") }

    private fun importsPrefs() = appContext?.getSharedPreferences("imported_playlists", Context.MODE_PRIVATE)

    fun init(context: Context, db: MusicDatabase) {
        appContext = context.applicationContext
        database = db
        _jobs.value = readJobs()
        scope.launch {
            Catalog.awaitAccounts()
            refreshImports()
            kickWorker()
        }
        // The main account changed: what is offered is counted for the new one.
        scope.launch { Catalog.state.collect { refreshImports() } }
    }

    // ── imported playlists ──

    /** Marks an app playlist as made by an import (offered for creating in the main account later). */
    fun markImported(playlistId: String) {
        importsPrefs()?.edit()?.putBoolean(playlistId, true)?.apply()
        scope.launch { refreshImports() }
    }

    /** The imported playlists still in the app, by id → name. */
    suspend fun importedPlaylists(): List<Pair<String, String>> {
        val db = database ?: return emptyList()
        val ids = importsPrefs()?.all?.keys.orEmpty()
        return ids.mapNotNull { id -> db.playlist(id).firstOrNull()?.let { id to it.playlist.name } }
    }

    /** Whether [localId] was created (or is being created) in [target] already. */
    fun isPushed(localId: String, target: MetadataSource): Boolean =
        _jobs.value.any { it.localId == localId && it.target == target && it.step != Step.FAILED }

    private suspend fun refreshImports() {
        val main = Catalog.source
        _localImports.value = if (main == null || !canCreate(main)) 0 else importedPlaylists().count { !isPushed(it.first, main) }
    }

    // ── jobs ──

    /** Whether playlists can be created in [target] from here. */
    fun canCreate(target: MetadataSource): Boolean = when (target) {
        MetadataSource.SPOTIFY -> Catalog.state.value.spotifyConnected
        MetadataSource.YANDEX -> YandexMusic.isAuthenticated()
        MetadataSource.VK -> VkCatalog.isAuthenticated
    }

    /** Queues the app playlists [localIds] for creating in [target] (the main account by default). */
    fun push(localIds: List<String>, target: MetadataSource? = Catalog.source) {
        val t = target ?: return
        scope.launch {
            val db = database ?: return@launch
            mutex.withLock {
                val fresh = localIds.filter { id -> !isPushed(id, t) }.mapNotNull { id ->
                    val playlist = db.playlist(id).firstOrNull() ?: return@mapNotNull null
                    PushJob(localId = id, target = t, title = playlist.playlist.name)
                }
                // A failed one queued again starts over.
                _jobs.value = _jobs.value.filterNot { j -> j.target == t && j.step == Step.FAILED && j.localId in localIds } + fresh
                writeJobs(_jobs.value)
            }
            refreshImports()
            kickWorker()
        }
    }

    /** Forgets finished jobs (the playlists stay where they are). */
    fun clearFinished() {
        scope.launch {
            mutex.withLock {
                _jobs.value = _jobs.value.filter { it.step == Step.CREATE || it.step == Step.FILL }
                writeJobs(_jobs.value)
            }
        }
    }

    private fun kickWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val next = _jobs.value.firstOrNull { it.step == Step.CREATE || it.step == Step.FILL } ?: break
                if (!canCreate(next.target)) {
                    // The account is gone (signed out): the job waits for it to come back.
                    break
                }
                runCatching { runJob(next) }.onFailure { e ->
                    Timber.w(e, "RemotePlaylists: %s → %s failed", next.title, next.target)
                    update(next.localId, next.target) { it.copy(step = Step.FAILED, error = e.message ?: e.javaClass.simpleName) }
                }
                delay(BETWEEN_JOBS_MS)
            }
            refreshImports()
        }
    }

    private suspend fun runJob(start: PushJob) {
        val db = database ?: return
        var job = start
        val songs = db.playlistSongs(job.localId).first().sortedBy { it.map.position }
        if (job.step == Step.CREATE) {
            val remoteId = createIn(job.target, job.title).getOrThrow()
            job = update(job.localId, job.target) { it.copy(step = Step.FILL, remoteId = remoteId, total = songs.size) } ?: return
        }
        val remoteId = job.remoteId ?: throw IllegalStateException("No playlist in the account")
        var cursor = job.cursor
        val missing = job.missing.toMutableList()
        while (cursor < songs.size) {
            val batch = songs.subList(cursor, (cursor + BATCH).coerceAtMost(songs.size))
            val ids = mutableListOf<String>()
            for (s in batch) {
                val song = s.song
                val catalogId = LikeTarget.catalogId(db, song.id)
                    // A VK upload is a VK catalog track already (its id names it).
                    ?: com.metrolist.music.resolver.FallbackIds.catalogIdOf(song.id)?.takeIf { it.startsWith(VkCatalog.TRACK_PREFIX) }
                val found = catalogId?.let {
                    CrossCatalog.trackFor(
                        target = job.target,
                        id = it,
                        title = song.song.title,
                        artists = song.artists.map { a -> a.name },
                        durationSec = song.song.duration,
                        liked = false,
                        mark = false,
                        album = song.song.albumName,
                    )
                } ?: CrossCatalog.trackIn(job.target, song.song.title, song.artists.map { it.name }, song.song.duration, song.song.albumName)
                if (found != null) ids += found else missing += listOfNotNull(song.artists.firstOrNull()?.name, song.song.title).joinToString(" – ")
                delay(LOOKUP_PAUSE_MS)
            }
            addTo(job.target, remoteId, ids.distinct()).getOrThrow()
            cursor += batch.size
            job = update(job.localId, job.target) { it.copy(cursor = cursor, missing = missing.toList(), total = songs.size) } ?: return
            delay(BETWEEN_BATCHES_MS)
        }
        update(job.localId, job.target) { it.copy(step = Step.DONE, cursor = songs.size, missing = missing.toList()) }
        Catalog.invalidateCaches()
    }

    private suspend fun createIn(target: MetadataSource, title: String): Result<String> = when (target) {
        MetadataSource.SPOTIFY -> {
            com.metrolist.music.utils.SpotifyTokenManager.ensureAuthenticated()
            Spotify.createPlaylist(title, "")
        }
        MetadataSource.YANDEX -> YandexMusic.createPlaylist(title)
        MetadataSource.VK -> VkCatalog.createPlaylist(title)
    }

    private suspend fun addTo(target: MetadataSource, playlistId: String, trackIds: List<String>): Result<Unit> {
        if (trackIds.isEmpty()) return Result.success(Unit)
        return when (target) {
            MetadataSource.SPOTIFY -> runCatching {
                trackIds.chunked(100).forEach { chunk ->
                    Spotify.addTracksToPlaylist(playlistId, chunk.map { "spotify:track:$it" }).getOrThrow()
                }
            }
            MetadataSource.YANDEX -> runCatching {
                // Yandex wants each track's album too.
                val full = YandexMusic.tracks(trackIds.map { YandexIds.raw(it) }).getOrThrow()
                val pairs = full.map { t -> YandexIds.raw(t.id) to t.album?.id?.takeIf { it.isNotBlank() }?.let(YandexIds::raw) }
                YandexMusic.addToPlaylist(playlistId, pairs).getOrThrow()
            }
            MetadataSource.VK -> VkCatalog.addToPlaylist(playlistId, trackIds)
        }
    }

    private suspend fun update(localId: String, target: MetadataSource, change: (PushJob) -> PushJob): PushJob? = mutex.withLock {
        var updated: PushJob? = null
        _jobs.value = _jobs.value.map { j ->
            if (j.localId == localId && j.target == target) change(j).also { updated = it } else j
        }
        writeJobs(_jobs.value)
        updated
    }

    private fun readJobs(): List<PushJob> = runCatching {
        val file = jobsFile()?.takeIf { it.exists() } ?: return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            PushJob(
                localId = o.getString("local"),
                target = MetadataSource.valueOf(o.getString("target")),
                title = o.optString("title"),
                step = runCatching { Step.valueOf(o.getString("step")) }.getOrDefault(Step.CREATE),
                remoteId = o.optString("remote").takeIf { it.isNotBlank() },
                cursor = o.optInt("cursor"),
                total = o.optInt("total"),
                missing = o.optJSONArray("missing")?.let { m -> (0 until m.length()).map { m.getString(it) } }.orEmpty(),
                error = o.optString("error").takeIf { it.isNotBlank() },
            )
        }
    }.getOrDefault(emptyList())

    private fun writeJobs(jobs: List<PushJob>) {
        val file = jobsFile() ?: return
        runCatching {
            file.writeText(
                JSONArray(
                    jobs.map { j ->
                        JSONObject()
                            .put("local", j.localId).put("target", j.target.name).put("title", j.title)
                            .put("step", j.step.name).put("remote", j.remoteId ?: "")
                            .put("cursor", j.cursor).put("total", j.total)
                            .put("missing", JSONArray(j.missing)).put("error", j.error ?: "")
                    },
                ).toString(),
            )
        }
    }

    private const val BATCH = 20
    private const val LOOKUP_PAUSE_MS = 400L
    private const val BETWEEN_BATCHES_MS = 4_000L
    private const val BETWEEN_JOBS_MS = 6_000L
}
