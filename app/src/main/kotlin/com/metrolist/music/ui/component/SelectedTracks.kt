/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.content.Context
import android.widget.Toast
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.DownloadUtil
import com.metrolist.music.utils.shareDownloadedFiles
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the selection bar does with ticked tracks. Sending and deleting only concern the downloaded
 * ones (their media ids); runs on its own scope so closing the screen doesn't cut it short.
 */
object SelectedTracks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Sends the downloaded files among [mediaIds] ([mediaIds] = a suspend lookup, done off the UI). */
    fun share(context: Context, downloadUtil: DownloadUtil, mediaIds: suspend () -> List<String>) {
        val appContext = context.applicationContext
        scope.launch {
            val selected = withContext(Dispatchers.IO) { mediaIds() }
            val ids = downloadedIds(downloadUtil, selected)
            timber.log.Timber.i("share: %d of %d selected id(s) are downloaded", ids.size, selected.size)
            if (ids.isEmpty()) {
                Toast.makeText(appContext, R.string.nothing_selected_downloaded, Toast.LENGTH_SHORT).show()
                return@launch
            }
            shareDownloadedFiles(appContext, downloadUtil.downloadExporter, ids)
        }
    }

    fun delete(context: Context, downloadUtil: DownloadUtil, mediaIds: suspend () -> List<String>) {
        val appContext = context.applicationContext
        scope.launch {
            val selected = withContext(Dispatchers.IO) { mediaIds() }
            val ids = (selected + downloadedIds(downloadUtil, selected)).distinct()
            downloadUtil.removeDownloads(ids)
            Toast.makeText(appContext, R.string.downloads_removed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * "Send" for the ticked tracks: the share sheet with what fits them — to a chat (catalog tracks
     * and videos), as files (the downloaded ones), to a playlist, and as a link when it's one track.
     * [songIds] writes the tracks' song rows and returns their ids (null: only files can go);
     * [mediaIds] gives the ids their downloads are looked up by.
     */
    fun openShareSheet(
        context: Context,
        database: MusicDatabase,
        downloadUtil: DownloadUtil,
        count: Int,
        songIds: (suspend () -> List<String>)?,
        mediaIds: suspend () -> List<String>,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val ids = songIds?.let { lookup -> withContext(Dispatchers.IO) { runCatching { lookup() }.getOrDefault(emptyList()) } }.orEmpty()
            val songs = if (ids.isEmpty()) {
                emptyList()
            } else {
                withContext(Dispatchers.IO) {
                    val byId = runCatching { database.getSongsByIds(ids) }.getOrDefault(emptyList()).associateBy { it.id }
                    ids.mapNotNull { byId[it] }
                }
            }
            val metas = songs.map { it.toMediaMetadata() }
            val single = metas.singleOrNull()?.takeIf { count == 1 }
            val music = metas.mapNotNull { meta ->
                val by = meta.artists.joinToString { it.name }
                com.metrolist.music.friends.ChatMusic.ofMedia(meta.id, meta.title, by, meta.thumbnailUrl, meta.duration * 1000L)
            }
            val title = single?.let { meta ->
                val by = meta.artists.joinToString { it.name }
                if (by.isNotBlank()) "$by — ${meta.title}" else meta.title
            } ?: appContext.resources.getQuantityString(R.plurals.n_song, count, count)
            ShareSheet.open(
                title = title,
                music = music,
                linkText = single?.let { com.metrolist.music.ui.menu.shareLinkOf(it, null) },
                files = { downloadedIds(downloadUtil, mediaIds()) },
                mediaId = single?.id,
                playlistSongIds = ids.takeIf { it.isNotEmpty() }?.let { list -> { list } },
            )
        }
    }

    /** The ids the downloads of [ids] sit under (each one's own, or its other id), as the rows see them. */
    private suspend fun downloadedIds(downloadUtil: DownloadUtil, ids: List<String>): List<String> =
        ids.mapNotNull { downloadUtil.downloadedIdOf(it) }.distinct()

    /** Writes the song rows of catalog [tracks] (as their menu would) and returns their song ids. */
    suspend fun songIdsOf(
        database: MusicDatabase,
        mapper: com.metrolist.music.playback.SpotifyYouTubeMapper,
        tracks: List<SpotifyTrack>,
    ): List<String> = withContext(Dispatchers.IO) {
        insertSongs(database, tracks.map { mapper.quickMetadata(it) })
    }

    /** The same for tracks found by the source search (VK and the like). */
    suspend fun songIdsOf(
        database: MusicDatabase,
        matches: List<com.metrolist.music.resolver.ProviderMatch>,
    ): List<String> = withContext(Dispatchers.IO) {
        com.metrolist.music.resolver.SourceSearch.remember(matches)
        insertSongs(
            database,
            matches.map { match ->
                com.metrolist.music.resolver.SourceSearch.mediaItemOf(match)
                com.metrolist.music.resolver.SourceSearch.metadataOf(match)
            },
        )
    }

    private suspend fun insertSongs(database: MusicDatabase, songs: List<com.metrolist.music.models.MediaMetadata>): List<String> {
        database.withTransaction { songs.forEach { insert(it) } }
        return songs.map { it.id }.distinct()
    }

    /** The download ids of catalog [tracks]: their fallback id and their matched YouTube video. */
    suspend fun mediaIdsOf(database: MusicDatabase, tracks: List<SpotifyTrack>): List<String> {
        val youtube = tracks.chunked(400).flatMap { chunk ->
            runCatching { database.getSpotifyMatchesBySpotifyIds(chunk.map { it.id }) }.getOrElse { emptyList() }
        }.associate { it.spotifyId to it.youtubeId }
        return tracks.flatMap { listOfNotNull(com.metrolist.music.resolver.FallbackIds.of(it.id), youtube[it.id]) }
    }
}
