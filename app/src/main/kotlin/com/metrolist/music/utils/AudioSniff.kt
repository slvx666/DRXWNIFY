/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

/** The container of audio bytes, told by their first bytes (magic numbers). */
object AudioSniff {
    /** "audio/mpeg", "audio/mp4", "audio/ogg", "audio/flac", "audio/webm", "audio/aac"; null when unknown. */
    fun mimeOf(head: ByteArray, read: Int = head.size): String? {
        if (read < 4) return null
        fun at(i: Int) = head[i].toInt() and 0xFF
        fun text(from: Int, s: String) = read >= from + s.length && String(head, from, s.length, Charsets.ISO_8859_1) == s
        return when {
            text(0, "ID3") -> "audio/mpeg"
            at(0) == 0xFF && (at(1) and 0xF6) == 0xF0 -> "audio/aac" // ADTS
            at(0) == 0xFF && (at(1) and 0xE0) == 0xE0 -> "audio/mpeg"
            text(4, "ftyp") -> "audio/mp4"
            text(0, "OggS") -> "audio/ogg"
            text(0, "fLaC") -> "audio/flac"
            at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF && at(3) == 0xA3 -> "audio/webm"
            else -> null
        }
    }

    fun mimeOf(file: java.io.File): String? = runCatching {
        val head = ByteArray(12)
        val read = file.inputStream().use { it.read(head) }
        mimeOf(head, read)
    }.getOrNull()
}
