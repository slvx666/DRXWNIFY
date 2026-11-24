/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.io.File

/** "Write lyrics into the tracks": how far it is, for the downloaded list's progress line. */
object LyricsEmbedJob {
    data class Progress(
        val total: Int,
        val checked: Int = 0,
        val written: Int = 0,
        val notFound: Int = 0,
        val failed: Int = 0,
        val done: Boolean = false,
    )

    val progress = MutableStateFlow<Progress?>(null)

    @Volatile
    var cancelled = false

    fun cancel() {
        if (progress.value?.done == false) cancelled = true
    }

    fun dismiss() {
        if (progress.value?.done == true) progress.value = null
    }
}

/**
 * Puts plain lyrics into an MP3's ID3v2.3 tag as a USLT frame (the one players read). FFmpeg 6
 * writes "lyrics" only as a TXXX frame. A USLT frame already there is replaced; the tag's padding
 * is dropped. A file without an ID3v2.3 tag is left alone.
 */
object Id3Lyrics {
    fun write(file: File, lyrics: String) {
        val bytes = file.readBytes()
        if (bytes.size < 10 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) return
        if (bytes[3].toInt() != 3) return
        val flags = bytes[5].toInt()
        if (flags and 0xC0 != 0) return // unsynchronised or with an extended header: not touched
        val tagSize = syncsafe(bytes, 6)
        val tagEnd = 10 + tagSize
        if (tagEnd > bytes.size) return

        val frames = ByteArrayOutputStream()
        var pos = 10
        while (pos + 10 <= tagEnd && bytes[pos].toInt() != 0) {
            val size = ((bytes[pos + 4].toInt() and 0xFF) shl 24) or ((bytes[pos + 5].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 6].toInt() and 0xFF) shl 8) or (bytes[pos + 7].toInt() and 0xFF)
            if (size < 0 || pos + 10 + size > tagEnd) break
            val id = String(bytes, pos, 4, Charsets.ISO_8859_1)
            if (id != "USLT") frames.write(bytes, pos, 10 + size)
            pos += 10 + size
        }
        frames.write(usltFrame(lyrics))
        val body = frames.toByteArray()

        val out = ByteArrayOutputStream(bytes.size + body.size)
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
        out.write(toSyncsafe(body.size))
        out.write(body)
        out.write(bytes, tagEnd, bytes.size - tagEnd)
        file.writeBytes(out.toByteArray())
    }

    private fun usltFrame(lyrics: String): ByteArray {
        val content = ByteArrayOutputStream()
        content.write(1) // UTF-16 with a byte order mark
        content.write("eng".toByteArray(Charsets.ISO_8859_1))
        content.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0)) // empty description
        content.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        content.write(lyrics.toByteArray(Charsets.UTF_16LE))
        val body = content.toByteArray()
        val frame = ByteArrayOutputStream()
        frame.write("USLT".toByteArray(Charsets.ISO_8859_1))
        frame.write(byteArrayOf((body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()))
        frame.write(byteArrayOf(0, 0))
        frame.write(body)
        return frame.toByteArray()
    }

    private fun syncsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun toSyncsafe(n: Int): ByteArray =
        byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())
}
