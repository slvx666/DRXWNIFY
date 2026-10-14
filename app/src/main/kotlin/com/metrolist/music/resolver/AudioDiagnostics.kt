/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Human-readable journal of how audio was found for each track: which providers answered, how fast,
 * why one failed, which stream was played or downloaded. Kept in memory (last [MAX_LINES] lines) for
 * the in-app "Audio search log" screen AND written to logcat with tag [TAG] via android.util.Log.i/w,
 * which release builds keep (`adb logcat -s MeldAudio`).
 */
object AudioDiagnostics {
    const val TAG = "DrxwAudio"
    private const val MAX_LINES = 600

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    fun info(message: String) = add('I', message)

    fun warn(message: String) = add('W', message)

    private fun add(level: Char, message: String) {
        runCatching {
            if (level == 'W') Log.w(TAG, message) else Log.i(TAG, message)
        }
        val line = "${timeFormat.get()!!.format(Date())} $level $message"
        synchronized(this) {
            val current = _lines.value
            _lines.value = if (current.size >= MAX_LINES) current.drop(current.size - MAX_LINES + 1) + line else current + line
        }
        file?.let { f -> writer.execute { runCatching { f.appendText(line + NEWLINE) } } }
    }

    /**
     * The journal is kept in a file as well: a slow start reported hours later used to be gone with
     * the app's process (and the system log only holds a minute), so there was nothing to look at.
     */
    @Volatile
    private var file: java.io.File? = null
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private const val NEWLINE = "\n"

    fun init(context: android.content.Context) {
        if (file != null) return
        val f = java.io.File(context.filesDir, "audio_log.txt")
        writer.execute {
            runCatching {
                val kept = if (f.exists()) f.readLines().takeLast(MAX_LINES) else emptyList()
                // Trimmed once per start, so the file never grows past a few hundred KB.
                f.writeText(kept.joinToString(NEWLINE, postfix = if (kept.isEmpty()) "" else NEWLINE))
                synchronized(this) { _lines.value = (kept + _lines.value).takeLast(MAX_LINES) }
            }
            file = f
        }
    }

    /** Why audio could not be found for a track — turned into a short explanation on the player cover. */
    enum class FailureKind {
        /** No enabled source had the track. */
        NOT_FOUND,

        /** Every audio source is switched off. */
        ALL_SOURCES_DISABLED,

        /** No catalog metadata to search other sources with (plain YouTube item). */
        NO_METADATA,
    }

    data class TrackProblems(
        val failure: FailureKind? = null,
        /** YouTube refused the video (age restriction, region, removed). */
        val youtubeRestricted: Boolean = false,
        /** YouTube could not be reached at all (network / VPN). */
        val youtubeUnreachable: Boolean = false,
        val at: Long = System.currentTimeMillis(),
    )

    private val problems = java.util.concurrent.ConcurrentHashMap<String, TrackProblems>()

    private fun update(mediaId: String, change: (TrackProblems) -> TrackProblems) {
        problems.compute(mediaId) { _, old -> change(old ?: TrackProblems()).copy(at = System.currentTimeMillis()) }
        if (problems.size > 500) problems.entries.minByOrNull { it.value.at }?.let { problems.remove(it.key) }
    }

    fun recordFailure(mediaId: String, kind: FailureKind) = update(mediaId) { it.copy(failure = kind) }

    fun recordYouTubeRestricted(mediaId: String) = update(mediaId) { it.copy(youtubeRestricted = true) }

    fun recordYouTubeUnreachable(mediaId: String) = update(mediaId) { it.copy(youtubeUnreachable = true) }

    /** Problems recorded for [mediaId] in the last 30 minutes. */
    fun problemsFor(mediaId: String?): TrackProblems? =
        mediaId?.let { problems[it] }?.takeIf { System.currentTimeMillis() - it.at < 30 * 60 * 1000L }

    fun clearProblems(mediaId: String) {
        problems.remove(mediaId)
    }

    fun clear() {
        synchronized(this) { _lines.value = emptyList() }
        file?.let { f -> writer.execute { runCatching { f.writeText("") } } }
    }

    fun asText(): String = _lines.value.joinToString("\n")
}
