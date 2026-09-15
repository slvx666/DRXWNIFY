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
    const val TAG = "MeldAudio"
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
    }

    fun clear() {
        synchronized(this) { _lines.value = emptyList() }
    }

    fun asText(): String = _lines.value.joinToString("\n")
}
