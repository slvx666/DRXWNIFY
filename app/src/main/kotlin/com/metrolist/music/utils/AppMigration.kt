/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drxwnify moved from the inherited app id "com.meld.app" to its own. To Android that is another
 * app, with its own (empty) data, so the move goes through a backup file:
 *  - the old app, when it installs the new one, first saves everything into
 *    Download/Drxwnify/Drxwnify-перенос-<date>.backup (settings and accounts, the library, friends…);
 *  - the new app, on its first start, offers to restore that file; the music already downloaded
 *    stays where it is and plays from the folder.
 */
object AppMigration {
    const val OLD_PACKAGE = "com.meld.app"

    fun isOldApp(context: Context): Boolean = context.packageName == OLD_PACKAGE

    /** Where the old app writes its backup; null when it couldn't be created. */
    fun createBackupFile(context: Context): Uri? {
        val name = "Drxwnify-перенос-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".backup"
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Drxwnify")
                }
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } else {
                @Suppress("DEPRECATION")
                val dir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Drxwnify")
                    .apply { mkdirs() }
                Uri.fromFile(java.io.File(dir, name))
            }
        }.getOrNull()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("app_migration", Context.MODE_PRIVATE)

    /** The new app asks once whether to bring the data over. */
    fun shouldOfferRestore(context: Context): Boolean =
        !isOldApp(context) && !prefs(context).getBoolean("offered", false)

    fun markOffered(context: Context) {
        prefs(context).edit().putBoolean("offered", true).apply()
    }
}
