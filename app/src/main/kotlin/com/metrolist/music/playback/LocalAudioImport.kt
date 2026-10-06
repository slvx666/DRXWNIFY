/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime

/**
 * The user's own audio files (picked in the import screen) as songs of the library: they play from
 * the file itself ("local:" ids, like the device scan), with the title, artist, album and cover read
 * from the file's tags — or "Artist - Title" from its name when it has none.
 */
object LocalAudioImport {
    /** Song ids, in the order of [uris]; files that can't be read are left out. */
    suspend fun importFiles(context: Context, database: MusicDatabase, uris: List<Uri>): List<String> =
        withContext(Dispatchers.IO) {
            uris.mapNotNull { uri -> runCatching { importOne(context, database, uri) }.onFailure { Timber.w(it, "LocalAudioImport: %s", uri) }.getOrNull() }
        }

    private suspend fun importOne(context: Context, database: MusicDatabase, uri: Uri): String {
        // Without a lasting grant the file would stop playing after the next restart.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val songId = "local:doc:" + sha1(uri.toString()).take(24)
        database.getSongByIdBlocking(songId)?.let { return songId }

        val fileName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.substringBeforeLast('.')
            .orEmpty()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val (nameArtist, nameTitle) = fileName.split(" - ", limit = 2).let { parts ->
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null to fileName.trim()
            }
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: nameTitle.ifBlank { "Audio" }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
                ?: nameArtist
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
            val durationSec = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000
            val cover = retriever.embeddedPicture?.let { bytes ->
                val dir = File(context.cacheDir, "artwork").also { it.mkdirs() }
                File(dir, songId.replace(':', '_') + ".jpg").also { it.writeBytes(bytes) }.let { Uri.fromFile(it).toString() }
            }
            val song = SongEntity(
                id = songId,
                title = title,
                duration = durationSec.toInt(),
                thumbnailUrl = cover,
                albumName = album,
                isLocal = true,
                localPath = uri.toString(),
                inLibrary = LocalDateTime.now(),
            )
            database.query {
                upsert(song)
                if (!artist.isNullOrBlank()) {
                    val artistId = artistByName(artist)?.id ?: ArtistEntity(id = ArtistEntity.generateArtistId(), name = artist)
                        .also { insert(it) }.id
                    insert(SongArtistMap(songId = songId, artistId = artistId, position = 0))
                }
            }
        } finally {
            runCatching { retriever.release() }
        }
        return songId
    }

    private fun sha1(text: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
