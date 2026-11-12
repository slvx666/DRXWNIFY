/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.catalog.Catalog
import com.metrolist.music.catalog.CrossCatalog
import com.metrolist.music.constants.MetadataSource
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.spotify.models.SpotifyLibraryEntry
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Bringing playlists, albums and tracks over from another linked account to the main one:
 *  - a playlist is copied into the app (it plays at once) and then created in the main account
 *    in the background ([RemotePlaylists]), where that account allows it;
 *  - an album is found in the main account's catalog and saved to its library;
 *  - a track is found there and liked.
 * Slowly, one item at a time: the accounts' limits matter more than speed.
 */
object AccountTransfer {
    data class Progress(
        val from: MetadataSource,
        val to: MetadataSource,
        val total: Int,
        val done: Int = 0,
        /** What the main account's catalog didn't have ("artist – title"). */
        val missing: List<String> = emptyList(),
        val finished: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    val isRunning: Boolean get() = _progress.value?.finished == false

    fun clear() {
        if (_progress.value?.finished == true) _progress.value = null
    }

    fun start(
        database: MusicDatabase,
        from: MetadataSource,
        playlists: List<SpotifyLibraryEntry>,
        albums: List<SpotifyLibraryEntry>,
        tracks: List<SpotifyTrack>,
    ) {
        val to = Catalog.source ?: return
        if (isRunning) return
        _progress.value = Progress(from, to, playlists.size + albums.size + tracks.size)
        scope.launch {
            val missing = mutableListOf<String>()
            val localIds = mutableListOf<String>()
            fun step() {
                _progress.value = _progress.value?.let { it.copy(done = it.done + 1, missing = missing.toList()) }
            }
            for (entry in playlists) {
                runCatching { copyPlaylist(database, entry) }
                    .onSuccess { localIds += it }
                    .onFailure { Timber.w(it, "AccountTransfer: playlist %s", entry.name); missing += entry.name }
                step()
            }
            // The copies are created in the main account in the background, where it allows it.
            if (localIds.isNotEmpty()) {
                localIds.forEach { RemotePlaylists.markImported(it) }
                if (RemotePlaylists.canCreate(to)) RemotePlaylists.push(localIds, to)
            }
            for (entry in albums) {
                runCatching {
                    val id = if (Catalog.sourceOf(entry.id) == to) entry.id else CrossCatalog.albumIn(to, entry.name, entry.creator)
                    if (id == null) {
                        missing += listOfNotNull(entry.creator, entry.name).joinToString(" – ")
                    } else {
                        Catalog.saveAlbum(id).getOrThrow()
                    }
                }.onFailure { Timber.w(it, "AccountTransfer: album %s", entry.name); missing += entry.name }
                step()
                delay(PAUSE_MS)
            }
            for (track in tracks) {
                runCatching {
                    val id = CrossCatalog.trackFor(
                        target = to,
                        id = track.id,
                        title = track.name,
                        artists = track.artists.map { it.name },
                        durationSec = track.durationMs / 1000,
                        liked = true,
                        album = track.album?.name,
                    )
                    if (id == null) {
                        missing += listOfNotNull(track.artists.firstOrNull()?.name, track.name).joinToString(" – ")
                    } else {
                        Catalog.saveTrack(id).getOrThrow()
                    }
                }.onFailure { Timber.w(it, "AccountTransfer: track %s", track.name); missing += track.name }
                step()
                delay(PAUSE_MS)
            }
            Catalog.invalidateCaches()
            _progress.value = _progress.value?.copy(finished = true, missing = missing.toList())
        }
    }

    /** The playlist [entry] of another account copied into the app; returns the app playlist's id. */
    private suspend fun copyPlaylist(database: MusicDatabase, entry: SpotifyLibraryEntry): String {
        val tracks = mutableListOf<SpotifyTrack>()
        var offset = 0
        while (offset < MAX_TRACKS) {
            val page = Catalog.playlistTracks(entry.id, 100, offset).getOrThrow()
            tracks += page.items.mapNotNull { it.track?.takeIf { t -> !t.isLocal && t.id.isNotBlank() } }
            offset += 100
            if (page.items.isEmpty() || offset >= page.total) break
        }
        val playlist = PlaylistEntity(name = entry.name, bookmarkedAt = java.time.LocalDateTime.now(), isEditable = true)
        database.withTransaction { insert(playlist) }
        val mapper = SpotifyYouTubeMapper(database)
        val ids = tracks.map { mapper.persistWithoutResolving(it).id }.distinct()
        if (ids.isNotEmpty()) database.playlist(playlist.id).firstOrNull()?.let { database.addSongToPlaylist(it, ids) }
        return playlist.id
    }

    private const val PAUSE_MS = 700L
    private const val MAX_TRACKS = 5000
}
