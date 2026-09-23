/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.datasource

import java.io.ByteArrayOutputStream

/**
 * Pulls the audio elementary stream (the MP3 or ADTS AAC frames) out of an MPEG transport stream.
 *
 * VK wraps its HLS audio in MPEG-TS. Joined segments already play in a TS-aware player, but the app's
 * progressive pipeline sniffs formats and the exporter expects a plain audio file, so the 188-byte
 * packets, the PES headers and the program tables are stripped here and only the audio remains.
 * Pure Kotlin, no Android types (unit-testable).
 */
object TsAudioDemuxer {
    private const val PACKET = 188
    private const val SYNC = 0x47

    /** MPEG-1/2 audio (MP3), ADTS AAC and LATM AAC — the audio stream types a TS can carry here. */
    private val AUDIO_STREAM_TYPES = setOf(0x03, 0x04, 0x0F, 0x11)

    /** The audio frames of [ts], or null when it has no recognisable audio stream. */
    fun extract(ts: ByteArray): ByteArray? {
        var pmtPid = -1
        var audioPid = -1
        val out = ByteArrayOutputStream(ts.size)
        var i = 0
        while (i + PACKET <= ts.size) {
            if (ts.u8(i) != SYNC) {
                // Lost sync (a truncated segment boundary): look for the next packet start.
                i++
                continue
            }
            val end = i + PACKET
            val payloadStart = ts.u8(i + 1) and 0x40 != 0
            val pid = ((ts.u8(i + 1) and 0x1F) shl 8) or ts.u8(i + 2)
            val adaptation = (ts.u8(i + 3) shr 4) and 0x3
            var p = i + 4
            if (adaptation == 2 || adaptation == 3) p += 1 + ts.u8(p)
            if ((adaptation == 1 || adaptation == 3) && p < end) {
                when {
                    pid == 0 && pmtPid < 0 -> pmtPid = programMapPid(ts, p, end, payloadStart)
                    pid == pmtPid && audioPid < 0 -> audioPid = audioPidOf(ts, p, end, payloadStart)
                    pid == audioPid && audioPid >= 0 -> {
                        if (payloadStart && p + 9 <= end &&
                            ts.u8(p) == 0 && ts.u8(p + 1) == 0 && ts.u8(p + 2) == 1
                        ) {
                            // PES header: start code, stream id, length, two flag bytes, header length.
                            p += 9 + ts.u8(p + 8)
                        }
                        if (p < end) out.write(ts, p, end - p)
                    }
                }
            }
            i = end
        }
        return if (audioPid >= 0 && out.size() > 0) out.toByteArray() else null
    }

    /** First real program's PMT pid from a PAT section. */
    private fun programMapPid(ts: ByteArray, start: Int, end: Int, payloadStart: Boolean): Int {
        var p = start
        if (payloadStart) p += 1 + ts.u8(p)
        if (p + 8 > end) return -1
        val sectionLength = ((ts.u8(p + 1) and 0x0F) shl 8) or ts.u8(p + 2)
        val tableEnd = minOf(p + 3 + sectionLength - 4, end)
        var e = p + 8
        while (e + 4 <= tableEnd) {
            val program = (ts.u8(e) shl 8) or ts.u8(e + 1)
            val pid = ((ts.u8(e + 2) and 0x1F) shl 8) or ts.u8(e + 3)
            if (program != 0) return pid
            e += 4
        }
        return -1
    }

    /** The first audio stream's pid from a PMT section. */
    private fun audioPidOf(ts: ByteArray, start: Int, end: Int, payloadStart: Boolean): Int {
        var p = start
        if (payloadStart) p += 1 + ts.u8(p)
        if (p + 12 > end) return -1
        val sectionLength = ((ts.u8(p + 1) and 0x0F) shl 8) or ts.u8(p + 2)
        val programInfoLength = ((ts.u8(p + 10) and 0x0F) shl 8) or ts.u8(p + 11)
        val tableEnd = minOf(p + 3 + sectionLength - 4, end)
        var e = p + 12 + programInfoLength
        while (e + 5 <= tableEnd) {
            val streamType = ts.u8(e)
            val pid = ((ts.u8(e + 1) and 0x1F) shl 8) or ts.u8(e + 2)
            val infoLength = ((ts.u8(e + 3) and 0x0F) shl 8) or ts.u8(e + 4)
            if (streamType in AUDIO_STREAM_TYPES) return pid
            e += 5 + infoLength
        }
        return -1
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
}
