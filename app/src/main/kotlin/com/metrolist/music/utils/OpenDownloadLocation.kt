/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.metrolist.music.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Opens the file a download was written to, so the user lands on it in whatever file manager or
 * player they have. Downloads made before the app started remembering the location, or files that
 * have since been deleted, simply say so.
 */
suspend fun openDownloadLocation(
    context: Context,
    exporter: DownloadExporter,
    songId: String,
) {
    val uri = exporter.exportedUri(songId)
    if (uri == null) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
        }
        return
    }
    val exists = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.close() }.isSuccess
    }
    if (!exists) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
        }
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "audio/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    withContext(Dispatchers.Main) {
        try {
            context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No app can open %s", uri)
            Toast.makeText(context, uri.toString(), Toast.LENGTH_LONG).show()
        }
    }
}
