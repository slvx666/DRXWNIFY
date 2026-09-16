/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.datastore.preferences.core.edit
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.metrolist.music.constants.DownloadFolderUriKey
import com.metrolist.music.constants.ExportFolderHintsKey
import com.metrolist.music.constants.ExportedSongIdsKey
import com.metrolist.music.constants.PendingExportSongIdsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.di.ApplicationScope
import com.metrolist.music.di.DownloadCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide set of song ids whose download has finished transferring bytes but is still being
 * assembled/transcoded into the final tagged file. The player observes this so its download button
 * keeps showing progress until the track has fully "landed" in the user's folder.
 */
object DownloadExportState {
    private val _exporting = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val exporting: kotlinx.coroutines.flow.StateFlow<Set<String>> = _exporting

    /** Tracks whose file actually exists in the user's folder — what "downloaded" means to the user. */
    private val _exported = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val exported: kotlinx.coroutines.flow.StateFlow<Set<String>> = _exported

    /** Tracks whose export failed (bad/incomplete cache, no write access…). */
    private val _failed = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val failed: kotlinx.coroutines.flow.StateFlow<Set<String>> = _failed

    fun begin(songId: String) { _exporting.value = _exporting.value + songId }
    fun end(songId: String) { _exporting.value = _exporting.value - songId }
    fun markExported(songId: String) {
        _exported.value = _exported.value + songId
        _failed.value = _failed.value - songId
    }
    fun markExported(songIds: Set<String>) { _exported.value = _exported.value + songIds }
    fun markFailed(songId: String) { _failed.value = _failed.value + songId }
    fun forget(songId: String) {
        _exported.value = _exported.value - songId
        _failed.value = _failed.value - songId
    }
}

/**
 * Assembles a completed Media3 download (stored as SimpleCache blocks in the app sandbox)
 * into a single, standalone media file the user can share or open with other apps.
 *
 * The cached stream (AAC-in-mp4 or Opus-in-webm) is transcoded to a tagged **.mp3** with embedded
 * cover art, so every exported file is a universally recognised music file (Telegram, car head
 * units, etc. all treat .mp3 as a track, unlike .webm). If FFmpeg is unavailable or fails, the
 * original container is written as a last resort so the user still gets *a* file.
 *
 * The file is written either into a user-selected SAF tree ([DownloadFolderUriKey]) or,
 * when none is set, into MediaStore under Music/Meld (API 29+).
 */
@Singleton
class DownloadExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    @DownloadCache private val downloadCache: SimpleCache,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /**
     * Two tracks may be transcoded at once (FFmpeg is the slow part of a batch); creating the
     * destination file stays serialized so two exports can't claim the same name.
     */
    private val exportPermits = Semaphore(2)
    private val destinationMutex = Any()

    init {
        // Remember what is already on disk, and finish exports that a previous run didn't get to
        // (the app being killed used to leave tracks counted as downloaded but with no file).
        scope.launch {
            DownloadExportState.markExported(context.dataStore.get(ExportedSongIdsKey, emptySet()))
            delay(RETRY_DELAY_MS)
            val pending = context.dataStore.get(PendingExportSongIdsKey, emptySet())
            if (pending.isNotEmpty()) {
                Timber.d("DownloadExporter: retrying %d unfinished export(s)", pending.size)
                for (songId in pending) export(songId)
            }
        }
    }

    /** Lightweight client for fetching cover-art thumbnails. */
    private val httpClient by lazy { OkHttpClient() }

    /**
     * Exports the fully-cached track [songId] to the configured destination.
     *
     * @return [Result.success] with the output [Uri] on success, [Result.failure] otherwise.
     *   Never throws — all failures are wrapped.
     */
    suspend fun export(songId: String): Result<Uri> = withContext(Dispatchers.IO) {
        exportPermits.withPermit {
            if (isExported(songId)) {
                DownloadExportState.markExported(songId)
                Timber.d("DownloadExporter: %s already exported, skipping", songId)
                return@withPermit Result.failure(AlreadyExportedException(songId))
            }
            // Publish "processing" so the UI can keep showing progress after the byte-download
            // finishes and until the tagged file actually lands in the user's folder.
            DownloadExportState.begin(songId)
            markPending(songId)
            try {
                val playlistFolder = folderHint(songId)
                runCatching { exportInternal(songId, playlistFolder) }
                    .onSuccess { markExported(songId) }
                    .onFailure {
                        // Left in the pending set so the next app start tries again.
                        DownloadExportState.markFailed(songId)
                        Timber.e(it, "DownloadExporter: export failed for %s", songId)
                    }
            } finally {
                DownloadExportState.end(songId)
            }
        }
    }

    /**
     * Removes [songId] from the exported set so a later re-download exports it again.
     * Call from the download-removed callback.
     */
    suspend fun forgetExported(songId: String) {
        DownloadExportState.forget(songId)
        context.dataStore.edit { prefs ->
            prefs[ExportedSongIdsKey]?.let { current ->
                if (songId in current) prefs[ExportedSongIdsKey] = current - songId
            }
        }
    }

    private suspend fun isExported(songId: String): Boolean =
        context.dataStore.get(ExportedSongIdsKey, emptySet()).contains(songId)

    private suspend fun markExported(songId: String) {
        context.dataStore.edit { prefs ->
            prefs[ExportedSongIdsKey] = (prefs[ExportedSongIdsKey] ?: emptySet()) + songId
            prefs[PendingExportSongIdsKey] = (prefs[PendingExportSongIdsKey] ?: emptySet()) - songId
            prefs[ExportFolderHintsKey] = (prefs[ExportFolderHintsKey] ?: emptySet())
                .filterNot { it.substringBefore(HINT_SEPARATOR) == songId }.toSet()
        }
        DownloadExportState.markExported(songId)
    }

    private suspend fun markPending(songId: String) {
        context.dataStore.edit { prefs ->
            prefs[PendingExportSongIdsKey] = (prefs[PendingExportSongIdsKey] ?: emptySet()) + songId
        }
    }

    /** Folder this track must go into (a playlist name), instead of the artist/album tree. */
    private suspend fun folderHint(songId: String): String? =
        context.dataStore.data.first()[ExportFolderHintsKey]
            ?.firstOrNull { it.substringBefore(HINT_SEPARATOR) == songId }
            ?.substringAfter(HINT_SEPARATOR)
            ?.takeIf { it.isNotBlank() }

    /** Thrown (as a [Result.failure]) when a track was already exported; not an error. */
    class AlreadyExportedException(songId: String) : Exception("Already exported: $songId")

    private fun exportInternal(songId: String, playlistFolder: String?): Uri {
        val song = database.getSongByIdBlocking(songId)
            ?: error("Song not found in database: $songId")
        val format = song.format
            ?: error("No FormatEntity for $songId — cannot determine container")

        val rawTitle = song.song.title
        val mimeType = format.mimeType.substringBefore(';').trim()
        val contentLength = format.contentLength

        val (srcExt, srcMime) = when {
            mimeType.startsWith("audio/mp4") -> "m4a" to "audio/mp4"
            mimeType.startsWith("audio/webm") -> "webm" to "audio/webm"
            // Qobuz-fallback sources: FLAC (CD/Hi-Res) or MP3 (lossy tier). FFmpeg reads both and the
            // transcode cascade below turns them into the tagged MP3 the user's library expects.
            mimeType.startsWith("audio/flac") || mimeType.startsWith("audio/x-flac") -> "flac" to "audio/flac"
            mimeType.startsWith("audio/mpeg") || mimeType.startsWith("audio/mp3") -> "mp3" to "audio/mpeg"
            // SoundCloud Opus streams (concatenated Ogg segments).
            mimeType.startsWith("audio/ogg") || mimeType.startsWith("audio/opus") -> "ogg" to "audio/ogg"
            // SoundCloud HLS AAC, transmuxed to ADTS.
            mimeType.startsWith("audio/aac") || mimeType.startsWith("audio/aacp") -> "aac" to "audio/aac"
            else -> error("Unsupported mimeType for export: $mimeType")
        }

        // Resolve artist + track title. Prefer structured artists from the DB; when the track
        // has none (typically a plain YouTube video), fall back to parsing "Artist - Title" out
        // of the video title after stripping YouTube promo noise ("(Official Music Video)" etc).
        // Spotify-sourced track known this session? Then Spotify is the source of truth for title,
        // artists, album and release type — YouTube uploads often carry a label/channel as "artist".
        val spotifyTrack = com.metrolist.music.playback.SpotifyMetadataRegistry.get(songId)
        val spotifyArtistNames = spotifyTrack?.artists?.map { it.name.trim() }?.filter { it.isNotEmpty() }.orEmpty()

        val artistNames = spotifyArtistNames.ifEmpty { song.orderedArtists.map { it.name } }
        val dbPrimaryArtist = artistNames.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val cleanedTitle = spotifyTrack?.name?.takeIf { it.isNotBlank() } ?: cleanTrackTitle(rawTitle)
        val (parsedArtist, parsedTitle) = parseArtistAndTitle(cleanedTitle)

        val primaryArtist: String
        val title: String
        if (spotifyTrack != null && dbPrimaryArtist != null) {
            primaryArtist = dbPrimaryArtist
            title = cleanedTitle
        } else if (dbPrimaryArtist != null) {
            primaryArtist = dbPrimaryArtist
            // Drop a leading "Artist - " only when it matches the known artist, so we don't
            // accidentally truncate a real title that happens to contain a dash.
            title = if (parsedArtist != null && parsedArtist.equals(dbPrimaryArtist, ignoreCase = true)) {
                parsedTitle
            } else {
                cleanedTitle
            }
        } else {
            primaryArtist = parsedArtist ?: "Unknown Artist"
            title = parsedTitle
        }

        // Artist-first folder layout:
        //   <PrimaryArtist>/<AlbumName>/  when the track is part of a real (multi-track) album
        //   <PrimaryArtist>/              when it's a standalone / single-track release
        //   Various[/<AlbumName>]/        when >2 artists or artist == "Various Artists"
        // Featured co-artists (DB only) go into the file name via "feat.", never the folder.
        val featured = artistNames.drop(1).mapNotNull { it.trim().takeIf { n -> n.isNotEmpty() } }
        val artistString =
            if (featured.isEmpty()) primaryArtist
            else "$primaryArtist feat. ${featured.joinToString(", ")}"
        val albumName = spotifyTrack?.album?.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: song.song.albumName?.trim()?.takeIf { it.isNotEmpty() }
        val spotifyAlbumType = spotifyTrack?.album?.albumType?.lowercase()

        Timber.d(
            "DownloadExporter: naming %s raw='%s' -> artist='%s' title='%s' (dbArtist=%b)",
            songId, rawTitle, artistString, title, dbPrimaryArtist != null,
        )

        // Decide album vs. standalone. Prefer the album's metadata track count (from the
        // already-loaded album relation — no extra DB call). Only when that count is unknown
        // do we fall back to the title-equality heuristic (YouTube Music names a single's
        // album after the track itself), so a real multi-track album that contains a track
        // sharing the album's name still lands in the album folder.
        val albumSongCount = song.album?.songCount
        val isRealAlbum = albumName != null && when {
            spotifyAlbumType == "single" -> false
            spotifyAlbumType != null -> true // album / ep / compilation
            albumSongCount != null && albumSongCount > 1 -> true
            albumSongCount != null -> false // metadata says it's a 1-track release
            else -> !albumName.equals(title, ignoreCase = true)
        }

        Timber.d(
            "DownloadExporter: album %s albumName='%s' songCount=%s isRealAlbum=%b",
            songId, albumName, albumSongCount?.toString() ?: "null", isRealAlbum,
        )

        // No dedicated album-artist column exists, so "Various" is inferred from the track's
        // own artists rather than an album-level credit.
        // For Spotify tracks the album's own artist decides the folder, so every track of one album
        // lands together (a feat.-heavy track no longer splits off into "Various").
        val spotifyAlbumArtist = spotifyTrack?.album?.artists?.firstOrNull()?.name?.trim()?.takeIf { it.isNotEmpty() }
        val isVarious = spotifyAlbumArtist?.equals("Various Artists", ignoreCase = true)
            ?: (artistNames.size > 2 || primaryArtist.equals("Various Artists", ignoreCase = true))
        val topFolder = if (isVarious) "Various" else (spotifyAlbumArtist ?: primaryArtist)
        // A playlist download keeps all of its tracks together in one folder named after it.
        val relativeSegments = if (playlistFolder != null) {
            listOf(sanitizeFileName(playlistFolder))
        } else {
            buildList {
                add(sanitizeFileName(topFolder))
                if (isRealAlbum && albumName != null) add(sanitizeFileName(albumName))
            }
        }
        val baseName = sanitizeFileName("$artistString - $title")

        // Preflight: confirm the whole resource is in the download cache before we touch it.
        // This distinguishes "not cached" from "cached but read broke" in the log.
        val cachedBytes = downloadCache.getCachedBytes(songId, 0, contentLength.coerceAtLeast(1))
        Timber.d(
            "DownloadExporter: preflight %s expected=%d cached=%d",
            songId, contentLength, cachedBytes,
        )
        if (!downloadCache.isCached(songId, 0, contentLength.coerceAtLeast(1))) {
            Timber.w("Not fully cached: %s, cached=%d", songId, cachedBytes)
            error("Resource not fully cached for $songId (cached=$cachedBytes, expected=$contentLength)")
        }

        val tempDir = context.cacheDir
        val tempInput = File.createTempFile("mld_in_", ".$srcExt", tempDir)
        var tempCover: File? = null
        val tempOutputs = mutableListOf<File>()
        try {
            // 1) Assemble the cached resource into a single temp file.
            val bytesRead = readCacheToFile(songId, tempInput)
            if (contentLength > 0 && bytesRead != contentLength) {
                error("Size mismatch for $songId: read $bytesRead, expected $contentLength")
            }

            // 2) Fetch + downscale the cover art (best effort).
            tempCover = fetchCoverJpeg(song.song.thumbnailUrl, tempDir)
            if (tempCover == null) {
                Timber.w("DownloadExporter: no cover art for %s, file will be saved without artwork", songId)
            }

            // 3) FFmpeg transcode cascade, embedding cover + tags each time:
            //    MP3 (most universally recognised as music) -> M4A/AAC -> original container.
            // Preferring MP3 but degrading to M4A means the user still gets a Telegram-recognised
            // music file even if this FFmpeg build lacks the LAME (MP3) encoder; only a total
            // FFmpeg failure (native lib won't load) leaves the raw .webm/.m4a.
            val sourceFile: File
            val finalExt: String
            val finalMime: String

            val mp3Out = File.createTempFile("mld_out_", ".mp3", tempDir).also { tempOutputs.add(it) }
            val m4aOut by lazy { File.createTempFile("mld_out_", ".m4a", tempDir).also { tempOutputs.add(it) } }

            if (runFfmpeg(tempInput, tempCover, mp3Out, OutputFormat.MP3, title, artistString, albumName, songId)) {
                Timber.i("DownloadExporter: %s -> .mp3 (from %s, cover=%b)", songId, srcMime, tempCover != null)
                sourceFile = mp3Out; finalExt = "mp3"; finalMime = "audio/mpeg"
            } else if (runFfmpeg(tempInput, tempCover, m4aOut, OutputFormat.M4A, title, artistString, albumName, songId)) {
                Timber.w("DownloadExporter: %s MP3 encode unavailable, falling back to .m4a", songId)
                sourceFile = m4aOut; finalExt = "m4a"; finalMime = "audio/mp4"
            } else {
                Timber.w("DownloadExporter: FFmpeg failed for %s, saving original .%s without artwork", songId, srcExt)
                sourceFile = tempInput; finalExt = srcExt; finalMime = srcMime
            }

            // 4) Write the result into the destination folder (SAF / MediaStore) — unchanged writer.
            val downloadFolderUri = context.dataStore.get(DownloadFolderUriKey, "")
            val fileName = "$baseName.$finalExt"
            // Creating the destination entry is serialized so two parallel exports can't pick
            // the same file name; the actual copy below runs concurrently.
            val target = synchronized(destinationMutex) {
                openTarget(downloadFolderUri, relativeSegments, fileName, finalMime)
            }
            try {
                sourceFile.inputStream().use { input -> input.copyTo(target.output) }
                target.output.flush()
                target.output.close()
            } catch (t: Throwable) {
                runCatching { target.output.close() }
                target.discard()
                throw t
            }
            target.finalize()
            Timber.i(
                "DownloadExporter: exported %s -> %s/%s (%d bytes) at %s",
                songId, relativeSegments.joinToString("/"), fileName, sourceFile.length(), target.uri,
            )
            return target.uri
        } finally {
            runCatching { tempInput.delete() }
            tempOutputs.forEach { out -> runCatching { out.delete() } }
            runCatching { tempCover?.delete() }
        }
    }

    /** Streams the fully-cached resource [songId] into [dest], returning the number of bytes written. */
    private fun readCacheToFile(songId: String, dest: File): Long {
        // The upstream data source never fetches from the network: its createDataSource()
        // succeeds, but open()/read() throw — so a cache miss surfaces here instead of a fetch.
        val cacheDataSource = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(CacheMissDataSource.FACTORY)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
            .createDataSource()
        var total = 0L
        try {
            cacheDataSource.open(DataSpec(Uri.parse(songId)))
            dest.outputStream().use { out ->
                val buffer = ByteArray(1 shl 20) // 1 MiB
                while (true) {
                    val read = cacheDataSource.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    out.write(buffer, 0, read)
                    total += read
                }
            }
        } finally {
            runCatching { cacheDataSource.close() }
        }
        return total
    }

    /** Downloads [url], downscales it to [MAX_COVER_PX] and writes a JPEG temp file, or null on any failure. */
    private fun fetchCoverJpeg(url: String?, dir: File): File? {
        if (url.isNullOrBlank()) return null
        return try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                val scaled = scaleBitmap(decoded, MAX_COVER_PX)
                val file = File.createTempFile("mld_cover_", ".jpg", dir)
                file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, it) }
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
                file
            }
        } catch (t: Throwable) {
            Timber.w(t, "DownloadExporter: cover fetch failed for %s", url)
            null
        }
    }

    private fun scaleBitmap(src: Bitmap, maxPx: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxPx) return src
        val scale = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * Runs FFmpeg to produce [output] as a tagged .m4a. Any failure — including native libraries
     * that won't load (UnsatisfiedLinkError / ExceptionInInitializerError) — returns false so the
     * caller can gracefully fall back to saving the original container.
     */
    /** Output container/codec the exporter targets, in preference order. */
    private enum class OutputFormat { MP3, M4A }

    private fun runFfmpeg(
        input: File,
        cover: File?,
        output: File,
        format: OutputFormat,
        title: String,
        artist: String,
        album: String?,
        songId: String,
    ): Boolean {
        return try {
            val args = buildList {
                add("-y")
                add("-i"); add(input.absolutePath)
                if (cover != null) { add("-i"); add(cover.absolutePath) }
                add("-map"); add("0:a:0")
                if (cover != null) { add("-map"); add("1:v:0") }
                when (format) {
                    // MP3 via LAME at CBR 320k — a real, universally recognised music file.
                    OutputFormat.MP3 -> { add("-c:a"); add("libmp3lame"); add("-b:a"); add("320k") }
                    // AAC at 192k — the native encoder, always present; container is mp4/.m4a.
                    OutputFormat.M4A -> { add("-c:a"); add("aac"); add("-b:a"); add("192k") }
                }
                if (cover != null) {
                    add("-c:v"); add("mjpeg")
                    add("-disposition:v"); add("attached_pic")
                    add("-metadata:s:v"); add("title=Album cover")
                    add("-metadata:s:v"); add("comment=Cover (front)")
                }
                if (format == OutputFormat.MP3) { add("-id3v2_version"); add("3") }
                add("-metadata"); add("title=$title")
                add("-metadata"); add("artist=$artist")
                if (album != null) { add("-metadata"); add("album=$album") }
                if (format == OutputFormat.M4A) { add("-movflags"); add("+faststart") }
                add(output.absolutePath)
            }.toTypedArray()

            Timber.d("DownloadExporter: ffmpeg %s args=%s", songId, args.joinToString(" "))
            val session = FFmpegKit.executeWithArguments(args)
            val rc = session.returnCode
            val success = ReturnCode.isSuccess(rc) && output.exists() && output.length() > 0
            if (!success) {
                Timber.w(
                    "DownloadExporter: ffmpeg rc=%s for %s\n%s",
                    rc, songId, session.allLogsAsString,
                )
            }
            success
        } catch (t: Throwable) {
            Timber.w(t, "DownloadExporter: ffmpeg threw for %s", songId)
            false
        }
    }

    /**
     * Opens the destination stream, either through SAF (a user-selected tree) or MediaStore.
     * [relativeSegments] are the sub-folders to create under the root (e.g. ["Drake", "Views"]).
     * The returned [ExportTarget] carries a [finalize] and a [discard] hook so the caller can
     * commit or roll back the pending file.
     */
    private fun openTarget(
        downloadFolderUri: String,
        relativeSegments: List<String>,
        fileName: String,
        outMime: String,
    ): ExportTarget {
        val resolver = context.contentResolver

        if (downloadFolderUri.isNotEmpty()) {
            val treeUri = Uri.parse(downloadFolderUri)
            var parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
            for (segment in relativeSegments) {
                parentDocId = findOrCreateChildDir(resolver, treeUri, parentDocId, segment)
            }
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            val uniqueName = uniqueSafName(resolver, treeUri, parentDocId, fileName)
            val docUri = DocumentsContract.createDocument(resolver, parentDocUri, outMime, uniqueName)
                ?: error("SAF createDocument returned null for $uniqueName")
            val output = resolver.openOutputStream(docUri)
                ?: error("Could not open output stream for $docUri")
            return ExportTarget(
                uri = docUri,
                output = output,
                finalize = {},
                discard = { runCatching { DocumentsContract.deleteDocument(resolver, docUri) } },
            )
        }

        // Fallback: MediaStore into Music/Meld/<segments>. Requires the scoped-storage API (29+).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("No download folder set and MediaStore export requires API 29+")
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = (listOf("Music", "Meld") + relativeSegments).joinToString("/") + "/"
        val uniqueName = uniqueMediaStoreName(resolver, collection, relativePath, fileName)
        val pending = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, uniqueName)
            put(MediaStore.Audio.Media.MIME_TYPE, outMime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val itemUri = resolver.insert(collection, pending)
            ?: error("MediaStore insert returned null for $uniqueName")
        val output = resolver.openOutputStream(itemUri)
            ?: error("Could not open output stream for $itemUri")
        return ExportTarget(
            uri = itemUri,
            output = output,
            finalize = {
                val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                resolver.update(itemUri, done, null, null)
            },
            discard = { runCatching { resolver.delete(itemUri, null, null) } },
        )
    }

    /** Returns the document id of the child directory [name] under [parentDocId], creating it if absent. */
    private fun findOrCreateChildDir(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        name: String,
    ): String {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == name &&
                    c.getString(mimeIdx) == DocumentsContract.Document.MIME_TYPE_DIR
                ) {
                    return c.getString(idIdx)
                }
            }
        }
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val newDir = DocumentsContract.createDocument(
            resolver, parentDocUri, DocumentsContract.Document.MIME_TYPE_DIR, name,
        ) ?: error("Could not create folder $name")
        return DocumentsContract.getDocumentId(newDir)
    }

    /** Adds a " (2)", " (3)"… suffix to [desired] if a file with that name already exists in the SAF folder. */
    private fun uniqueSafName(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        desired: String,
    ): String {
        val existing = HashSet<String>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (c.moveToNext()) existing.add(c.getString(nameIdx))
        }
        return uniqueName(desired, existing)
    }

    /** Adds a " (2)", " (3)"… suffix if a file with [desired] name already exists in [relativePath]. */
    private fun uniqueMediaStoreName(
        resolver: android.content.ContentResolver,
        collection: Uri,
        relativePath: String,
        desired: String,
    ): String {
        val existing = HashSet<String>()
        runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
                "${MediaStore.Audio.Media.RELATIVE_PATH}=?",
                arrayOf(relativePath),
                null,
            )?.use { c ->
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                while (c.moveToNext()) existing.add(c.getString(nameIdx))
            }
        }
        return uniqueName(desired, existing)
    }

    private fun uniqueName(desired: String, existing: Set<String>): String {
        if (desired !in existing) return desired
        val dot = desired.lastIndexOf('.')
        val base = if (dot > 0) desired.substring(0, dot) else desired
        val extPart = if (dot > 0) desired.substring(dot) else ""
        var n = 2
        while ("$base ($n)$extPart" in existing) n++
        return "$base ($n)$extPart"
    }

    private class ExportTarget(
        val uri: Uri,
        val output: OutputStream,
        val finalize: () -> Unit,
        val discard: () -> Unit,
    )

    /**
     * Upstream [DataSource] used by the export [CacheDataSource]. Construction always
     * succeeds (so [CacheDataSource.Factory.createDataSource] doesn't throw), but any
     * attempt to actually fetch — [open] or [read] — throws. When the resource is fully
     * cached the upstream is never opened, so export proceeds without touching the network.
     */
    private class CacheMissDataSource : DataSource {
        override fun addTransferListener(transferListener: TransferListener) {}

        override fun open(dataSpec: DataSpec): Long =
            throw IllegalStateException("Cache miss — resource not fully cached")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            throw IllegalStateException("Cache miss — resource not fully cached")

        override fun getUri(): Uri? = null

        override fun close() {}

        companion object {
            val FACTORY = DataSource.Factory { CacheMissDataSource() }
        }
    }

    /**
     * Strips common YouTube promo noise from a video title: bracketed tags like
     * "(Official Music Video)" / "[Lyric Video]", and trailing "| … playthrough" style
     * junk. Returns the cleaned title (falls back to the trimmed original if it empties out).
     */
    private fun cleanTrackTitle(raw: String): String {
        var s = raw
        // Drop a promotional tail after " | " or " // " when it contains junk keywords.
        Regex("\\s[|/]{1,2}\\s").find(s)?.let { m ->
            val tail = s.substring(m.range.last + 1)
            if (JUNK_REGEX.containsMatchIn(tail)) s = s.substring(0, m.range.first)
        }
        // Remove (...) / [...] groups that are promotional.
        s = Regex("[(\\[][^()\\[\\]]*[)\\]]").replace(s) { mr ->
            if (JUNK_REGEX.containsMatchIn(mr.value)) "" else mr.value
        }
        s = s.replace(Regex("\\s+"), " ").trim().trim('"', '\'', '-', '–', '—', ' ')
        return s.ifEmpty { raw.trim() }
    }

    /**
     * Splits a cleaned title into "Artist" and "Title" on the first " - " (or en/em dash).
     * Returns (null, wholeTitle) when there is no such separator.
     */
    private fun parseArtistAndTitle(cleaned: String): Pair<String?, String> {
        val m = Regex("\\s[-–—]\\s").find(cleaned)
        if (m != null && m.range.first > 0) {
            val artist = cleaned.substring(0, m.range.first).trim().trim('"', '\'')
            val title = cleaned.substring(m.range.last + 1).trim().trim('"', '\'')
            if (artist.isNotEmpty() && title.isNotEmpty()) return artist to title
        }
        return null to cleaned.trim().trim('"', '\'')
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.map { c -> if (c in ILLEGAL_FILENAME_CHARS) '_' else c }
            .joinToString("")
            .trim()
        return cleaned.ifEmpty { "audio" }
    }

    companion object {
        /** Separator inside an [ExportFolderHintsKey] entry ("songId\\folder"). */
        private const val HINT_SEPARATOR = '\u001F'

        /** Give the app a moment to start before retrying leftover exports. */
        private const val RETRY_DELAY_MS = 8_000L

        /**
         * Records that [songIds] belong to a playlist download and must all land in ONE folder
         * named [folderName], instead of the artist/album tree.
         */
        suspend fun rememberPlaylistFolder(context: Context, songIds: Collection<String>, folderName: String) {
            if (songIds.isEmpty() || folderName.isBlank()) return
            context.dataStore.edit { prefs ->
                val existing = (prefs[ExportFolderHintsKey] ?: emptySet())
                    .filterNot { it.substringBefore(HINT_SEPARATOR) in songIds }
                prefs[ExportFolderHintsKey] = (existing + songIds.map { "$it$HINT_SEPARATOR$folderName" }).toSet()
            }
        }

        private val ILLEGAL_FILENAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        // YouTube promo tags used to detect/strip noise from video titles. Case-insensitive.
        private val JUNK_REGEX = Regex(
            "(?i)(official|music\\s*video|lyric|lyrics|visuali[sz]er|\\baudio\\b|remaster|" +
                "playthrough|short\\s*film|premiere|teaser|trailer|out\\s*now|\\bm/?v\\b|" +
                "\\bhd\\b|\\b4k\\b|\\b8k\\b|explicit)"
        )

        /** Longest edge (px) the embedded cover art is downscaled to before JPEG compression. */
        private const val MAX_COVER_PX = 600
        private const val COVER_JPEG_QUALITY = 85
    }
}
