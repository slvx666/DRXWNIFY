/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.ActivityNotFoundException
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
 * one thing this action is not for; when no file manager answers, the share sheet is offered
 * instead so the track can still leave the app.
 */
suspend fun openDownloadLocation(
    context: Context,
    exporter: DownloadExporter,
    songId: String,
) {
    val uri = existingExportUri(context, exporter, songId) ?: return
    val folder = withContext(Dispatchers.IO) { folderUriOf(context, uri) }

    withContext(Dispatchers.Main) {
        val opened = folder != null && startViewing(context, folder, DIRECTORY_MIME)
        if (!opened) {
            // No file manager took the folder: sharing the file is the next best way out of the app.
            shareFile(context, uri)
        }
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
    val uri = exporter.exportedUri(songId)
    val exists = uri != null && withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.close() }.isSuccess
    }
    if (!exists) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
        }
        return null
    }
    return uri
}

private fun shareFile(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.open_file_location))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "Nothing can share %s", uri)
        Toast.makeText(context, uri.toString(), Toast.LENGTH_LONG).show()
    }
}

private fun startViewing(context: Context, uri: Uri, mime: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_ACTIVITY_NEW_TASK,
        )
    }
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Timber.d(e, "No file manager for %s", uri)
        false
    }
}

/**
 * The folder [fileUri] lives in, as a document uri a file manager can open.
 *
 *  - a SAF document (the user picked a download folder): its parent document id is the same id
 *    without the last path segment;
 *  - a MediaStore item (no folder picked): the path it reports is turned into an
 *    ExternalStorageProvider document, which is what DocumentsUI shows as "Music/Meld/…".
 */
private fun folderUriOf(context: Context, fileUri: Uri): Uri? = runCatching {
    when {
        DocumentsContract.isDocumentUri(context, fileUri) -> {
            val documentId = DocumentsContract.getDocumentId(fileUri)
            val parentId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
                .takeIf { it.isNotBlank() && it != documentId } ?: return@runCatching null
            if (isTreeUri(fileUri)) {
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

private fun isTreeUri(uri: Uri): Boolean = uri.pathSegments.firstOrNull() == "tree"

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
