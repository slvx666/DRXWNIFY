/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.metrolist.music.resolver.AudioDiagnostics
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a user sends when something went wrong: one .txt with the phone and app, the last crashes
 * (kept in a file: the crash screen used to be the only place they were, and gone once closed),
 * the audio and playback journal ([AudioDiagnostics]: sources, errors, tracks that ended early)
 * and this run's own system log. Settings → App logs → Share.
 */
object AppLogs {
    private const val CRASH_FILE = "crash_log.txt"
    private const val CRASH_MARK = "##### crash "
    private const val KEEP_CRASHES = 5

    /** Called from the crash handler, before the process dies: plain file I/O, no threads. */
    fun saveCrash(context: Context, report: String) {
        runCatching {
            val file = File(context.filesDir, CRASH_FILE)
            val old = if (file.exists()) file.readText() else ""
            val kept = old.split(CRASH_MARK).filter { it.isNotBlank() }.takeLast(KEEP_CRASHES - 1)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val all = kept.map { CRASH_MARK + it.trimEnd() + "\n" } + (CRASH_MARK + stamp + "\n" + report.trimEnd() + "\n")
            file.writeText(all.joinToString("\n"))
        }
    }

    fun crashes(context: Context): String =
        runCatching { File(context.filesDir, CRASH_FILE).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    fun clearCrashes(context: Context) {
        runCatching { File(context.filesDir, CRASH_FILE).delete() }
    }

    /** This process's own system log (an app may read its own lines), the last [lines]. */
    private fun ownLogcat(lines: Int = 3000): String = runCatching {
        val pid = android.os.Process.myPid()
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=$pid", "-t", lines.toString())
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text
    }.getOrElse { "(system log not readable: ${it.message})" }

    fun report(context: Context): String = buildString {
        append(AudioDiagnostics.environment(context))
        appendLine()
        appendLine("===== Crashes =====")
        appendLine(crashes(context).ifBlank { "(none saved)" })
        appendLine()
        appendLine("===== Audio and playback journal =====")
        appendLine(AudioDiagnostics.asText().ifBlank { "(empty)" })
        appendLine()
        appendLine("===== System log of this run =====")
        appendLine(ownLogcat())
    }

    /** Writes the report to a .txt (a long log pasted as a message gets cut by messengers) and shares it. */
    fun share(context: Context) {
        // Reading the system log takes a moment: off the main thread, the chooser opens after.
        Thread {
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
                val dir = File(context.cacheDir, "logs").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "drxwnify-log-$stamp.txt")
                file.writeText(report(context))
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }.start()
    }
}
