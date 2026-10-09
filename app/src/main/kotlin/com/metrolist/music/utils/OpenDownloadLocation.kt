/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import com.metrolist.music.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Mime type every file manager (DocumentsUI included) answers ACTION_VIEW for with a folder. */
private const val DIRECTORY_MIME = "vnd.android.document/directory"

/**
 * Opens the FOLDER a download was written to, so the file can be picked up from there (shared to
 * Telegram, copied to a PC…). Opening the file itself would just hand it to a player, which is the
 * one thing this action is not for.
 *
 * Opening a folder is best-effort: the file managers that answer such an intent differ per phone,
 * and the system refuses the intent outright unless the app holds a permission for that document
 * tree (which it only does when the user picked a download folder themselves). Every failure —
 * including that refusal, which used to crash the app — falls through to the share sheet, so the
 * track always has a way out of the app.
 */
suspend fun openDownloadLocation(
    context: Context,
    exporter: DownloadExporter,
    songId: String,
) {
    val uri = existingExportUri(context, exporter, songId) ?: return
    val folder = withContext(Dispatchers.IO) { folderUriOf(context, uri) }

    withContext(Dispatchers.Main) {
        // A folder of our own tree is handed over with a grant; a Music/Drxwnify folder is opened
        // without one — the file manager can read it by itself, and asking to grant a uri we don't
        // hold is what the system refused (which fell back to "share").
        val opened = folder != null && startViewing(context, folder, grant = hasAccessTo(context, folder))
        if (!opened) shareFile(context, uri)
    }
}

/** Hands the downloaded file to another app (Telegram, a messenger, a cloud client…). */
suspend fun shareDownloadedFile(
    context: Context,
    exporter: DownloadExporter,
    songId: String,
) {
    val uri = existingExportUri(context, exporter, songId) ?: return
    withContext(Dispatchers.Main) { shareFile(context, uri) }
}

/** The export uri of [songId] if the file is still there; otherwise it says so and returns null. */
private suspend fun existingExportUri(
    context: Context,
    exporter: DownloadExporter,
    songId: String,
): Uri? {
    fun readable(candidate: Uri?): Boolean = candidate != null &&
        runCatching { context.contentResolver.openFileDescriptor(candidate, "r")?.close() }.isSuccess

    var failure: Throwable? = null
    val existing = withContext(Dispatchers.IO) { sharableExport(exporter, songId)?.takeIf(::readable) }
    if (existing == null) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(R.string.file_restoring), Toast.LENGTH_SHORT).show()
        }
    }
    val uri = existing ?: withContext(Dispatchers.IO) {
        exporter.exportedUri(songId)?.takeIf(::readable)
            // The track is downloaded (it plays offline from the app's own storage) but its copy in
            // the music folder is gone — deleted or moved by hand, or never written. Write it again
            // (forced: the exporter otherwise skips a track it has exported once).
            ?: exporter.export(songId, force = true)
                .onFailure { failure = it }
                .getOrNull()?.takeIf(::readable)
    }
    if (uri == null) {
        android.util.Log.w("DrxwnifyExport", "no file for $songId: ${failure?.message}")
        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
        }
        return null
    }
    return uri
}

/**
 * Sends the files of several downloaded tracks at once (a whole album or playlist). A track whose
 * copy in the music folder has gone is written again first.
 */
suspend fun shareDownloadedFiles(
    context: Context,
    exporter: DownloadExporter,
    songIds: List<String>,
) {
    fun readable(candidate: Uri?): Boolean = candidate != null &&
        runCatching { context.contentResolver.openFileDescriptor(candidate, "r")?.close() }.isSuccess

    withContext(Dispatchers.Main) {
        Toast.makeText(context, context.getString(R.string.preparing_files, songIds.size), Toast.LENGTH_SHORT).show()
    }
    val uris = withContext(Dispatchers.IO) {
        songIds.mapNotNull { id ->
            sharableExport(exporter, id)?.takeIf(::readable)
                ?: exporter.export(id, force = true)
                    .onFailure { android.util.Log.w("DrxwnifyExport", "no file for $id: ${it.message}") }
                    .getOrNull()?.takeIf(::readable)
        }
    }
    if (uris.size < songIds.size) {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                context.getString(R.string.files_partially_ready, uris.size, songIds.size),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    withContext(Dispatchers.Main) {
        if (uris.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
            return@withContext
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            // The grant covers every file only when they are all in the clip data too.
            clipData = android.content.ClipData.newRawUri(null, uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.send_files))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (e: Throwable) {
            Timber.w(e, "Nothing can share %d files", uris.size)
        }
    }
}

/** The exported file, moved out of a hidden ".folder" first (messengers can't read those). */
private suspend fun sharableExport(exporter: DownloadExporter, songId: String): Uri? {
    val uri = exporter.exportedUri(songId) ?: return null
    if (!exporter.isInHiddenPath(uri)) return uri
    exporter.rewrite(songId)
    return exporter.exportedUri(songId)
}

private fun shareFile(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.share_file))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Throwable) {
        Timber.w(e, "Nothing can share %s", uri)
        Toast.makeText(context, uri.toString(), Toast.LENGTH_LONG).show()
    }
}

/**
 * True when this app may hand [uri] to another app. A document under a tree the user granted us
 * qualifies; one we merely built a path for (the MediaStore case) does not, and passing it on
 * throws [SecurityException] from the system, not from the receiving app.
 */
private fun hasAccessTo(context: Context, uri: Uri): Boolean =
    context.contentResolver.persistedUriPermissions.any { permission ->
        permission.isReadPermission && DocumentsContract.isTreeUri(permission.uri) &&
            uri.toString().startsWith(permission.uri.toString().substringBefore("/document/"))
    }

private fun startViewing(context: Context, uri: Uri, grant: Boolean): Boolean {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, DIRECTORY_MIME)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (grant) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // The system file manager opens a storage folder reliably; vendor ones often don't.
        DOCUMENTS_UI_PACKAGES.firstOrNull { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }?.let { setPackage(it) }
    }
    // Anything can go wrong here (no file manager, no permission for the document, a manufacturer
    // file app that rejects the intent) and none of it is worth a crash: the caller shares instead.
    return try {
        context.startActivity(intent)
        true
    } catch (e: Throwable) {
        Timber.d(e, "Could not open the folder %s", uri)
        false
    }
}

/**
 * The folder [fileUri] lives in, as a document uri a file manager can open.
 *
 *  - a SAF document (the user picked a download folder): its parent document id is the same id
 *    without the last path segment;
 *  - a MediaStore item (no folder picked): the path it reports is turned into an
 *    ExternalStorageProvider document, which is what DocumentsUI shows as "Music/Drxwnify/…".
 */
private fun folderUriOf(context: Context, fileUri: Uri): Uri? = runCatching {
    when {
        DocumentsContract.isDocumentUri(context, fileUri) -> {
            val documentId = DocumentsContract.getDocumentId(fileUri)
            val parentId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
                .takeIf { it.isNotBlank() && it != documentId } ?: return@runCatching null
            if (DocumentsContract.isTreeUri(fileUri)) {
                DocumentsContract.buildDocumentUriUsingTree(fileUri, parentId)
            } else {
                DocumentsContract.buildDocumentUri(
                    fileUri.authority ?: return@runCatching null,
                    parentId,
                )
            }
        }

        fileUri.authority == MediaStore.AUTHORITY -> {
            val relativePath = context.contentResolver.query(
                fileUri,
                arrayOf(MediaStore.Audio.Media.RELATIVE_PATH),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }?.trim('/')?.takeIf { it.isNotBlank() } ?: return@runCatching null
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$relativePath")
        }

        else -> null
    }
}.onFailure { Timber.d(it, "Could not derive the folder of %s", fileUri) }.getOrNull()

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

private val DOCUMENTS_UI_PACKAGES = listOf("com.google.android.documentsui", "com.android.documentsui")
