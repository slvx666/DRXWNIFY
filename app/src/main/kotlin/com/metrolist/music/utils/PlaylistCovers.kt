/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.metrolist.music.db.MusicDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The pictures picked as covers of the user's own playlists. They used to stay in the cache (gone
 * when the cache is cleared) behind an address carrying the app id (useless to a reinstalled app).
 * Now they live in files/playlist_covers, go into backups, and after a restore the playlists are
 * pointed at the restored files.
 */
object PlaylistCovers {
    private const val DIR = "playlist_covers"
    private const val OLD_CACHE_PREFIX = "playlist_cover_crop_"

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Where a newly picked cover is written. */
    fun newFile(context: Context): File = File(dir(context), "cover_${System.currentTimeMillis()}.jpg")

    fun uriOf(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)

    /** Every cover file worth keeping in a backup (the new folder and old ones still in the cache). */
    fun allFiles(context: Context): List<File> =
        (dir(context).listFiles()?.toList().orEmpty()) +
            (context.cacheDir.listFiles { f -> f.name.startsWith(OLD_CACHE_PREFIX) }?.toList().orEmpty())

    /**
     * Points every own-playlist cover at this app's copy of its file: after a restore (the address
     * named the old app) and for covers still in the cache (moved to the lasting folder first).
     */
    suspend fun relink(context: Context, database: MusicDatabase) = withContext(Dispatchers.IO) {
        runCatching {
            val playlists = database.playlistsByCreateDateAsc().first()
            for (playlist in playlists) {
                val url = playlist.playlist.thumbnailUrl ?: continue
                if (!url.startsWith("content://") || !url.contains(".FileProvider/")) continue
                val name = Uri.parse(url).lastPathSegment ?: continue
                val target = File(dir(context), name)
                if (!target.exists()) {
                    val inCache = File(context.cacheDir, name)
                    if (inCache.exists()) inCache.copyTo(target, overwrite = true) else continue
                }
                val fresh = uriOf(context, target).toString()
                if (fresh != url) database.query { update(playlist.playlist.copy(thumbnailUrl = fresh)) }
            }
        }
    }
}
