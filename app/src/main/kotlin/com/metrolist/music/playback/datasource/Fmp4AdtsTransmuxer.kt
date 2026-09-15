/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.datasource

/**
 * Turns fragmented-MP4 AAC (CMAF HLS segments, e.g. SoundCloud's "aac_160k") into a plain ADTS AAC
 * stream. Concatenated fMP4 segments play but have no seek table and usually no duration; ADTS frames
 * are self-describing, so the player gets a known length, a duration and constant-bitrate seeking, and
 * ffmpeg can export the result directly.
 *
 * Pure byte logic (no Android) so it is unit-testable.
 */
object Fmp4AdtsTransmuxer {

    data class AudioConfig(val objectType: Int, val frequencyIndex: Int, val channelConfig: Int)

    /** Where one fragment's samples are, relative to the start of the segment bytes. */
    data class Fragment(val sampleSizes: IntArray, val dataStart: Long) {
        val adtsSize: Long get() = sampleSizes.sumOf { it.toLong() + ADTS_HEADER_SIZE }
    }

    const val ADTS_HEADER_SIZE = 7

    private val FREQUENCIES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    // ── Box helpers ──────────────────────────────────────────────────────────────────────────────

    private class Box(val type: String, val start: Int, val headerSize: Int, val end: Int) {
        val payload get() = start + headerSize
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    /** Child boxes in [from, to). Stops (returns what it has) at a truncated box. */
    private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = mutableListOf<Box>()
        var o = from
        while (o + 8 <= to) {
            var size = u32(b, o)
            val type = String(b, o + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (o + 16 > to) break
                size = (u32(b, o + 8) shl 32) or u32(b, o + 12)
                header = 16
            } else if (size == 0L) {
                size = (to - o).toLong()
            }
            if (size < header) break
            val end = o.toLong() + size
            out += Box(type, o, header, end.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (end > to) break
            o = end.toInt()
        }
        return out
    }

    private fun child(b: ByteArray, parent: Box, type: String): Box? =
        boxes(b, parent.payload, parent.end.coerceAtMost(b.size)).firstOrNull { it.type == type }

    // ── Init segment ─────────────────────────────────────────────────────────────────────────────

    /** AAC configuration from an init segment (moov/…/stsd/mp4a/esds), or null if it isn't AAC audio. */
    fun parseInit(init: ByteArray): AudioConfig? {
        val moov = boxes(init, 0, init.size).firstOrNull { it.type == "moov" } ?: return null
        for (trak in boxes(init, moov.payload, moov.end.coerceAtMost(init.size)).filter { it.type == "trak" }) {
            val stbl = child(init, trak, "mdia")?.let { child(init, it, "minf") }?.let { child(init, it, "stbl") } ?: continue
            val stsd = child(init, stbl, "stsd") ?: continue
            // stsd: full box (4) + entry count (4), then sample entries.
            val entries = boxes(init, stsd.payload + 8, stsd.end.coerceAtMost(init.size))
            val mp4a = entries.firstOrNull { it.type == "mp4a" } ?: continue
            // AudioSampleEntry: 6 reserved + 2 index + 8 reserved + 2 channels + 2 sample size + 4 + 4 rate.
            val base = mp4a.payload
            if (base + 28 > init.size) continue
            val channels = u16(init, base + 16)
            val sampleRate = (u32(init, base + 24) shr 16).toInt()
            val fallback = AudioConfig(2, frequencyIndexOf(sampleRate), channels.coerceIn(1, 7))
            val esds = boxes(init, base + 28, mp4a.end.coerceAtMost(init.size)).firstOrNull { it.type == "esds" }
                ?: return fallback
            return parseEsds(init, esds.payload + 4, esds.end.coerceAtMost(init.size)) ?: fallback
        }
        return null
    }

    private fun frequencyIndexOf(rate: Int): Int = FREQUENCIES.indexOf(rate).let { if (it < 0) 4 else it }

    private fun parseEsds(b: ByteArray, from: Int, to: Int): AudioConfig? {
        var o = from
        fun readLength(): Int {
            var len = 0
            repeat(4) {
                if (o >= to) return len
                val v = b[o++].toInt() and 0xFF
                len = (len shl 7) or (v and 0x7F)
                if (v and 0x80 == 0) return len
            }
            return len
        }
        while (o < to) {
            val tag = b[o++].toInt() and 0xFF
            val len = readLength()
            when (tag) {
                0x03 -> {
                    if (o + 3 > to) return null
                    val flags = b[o + 2].toInt() and 0xFF
                    o += 3
                    if (flags and 0x80 != 0) o += 2 // dependsOn_ES_ID
                    if (flags and 0x40 != 0) o += 1 + (b.getOrNull(o)?.toInt()?.and(0xFF) ?: 0) // URL
                    if (flags and 0x20 != 0) o += 2 // OCR_ES_Id
                }
                0x04 -> o += 13 // objectType, streamType, bufferSize, max/avg bitrate; children follow
                0x05 -> {
                    if (len < 2 || o + 2 > to) return null
                    val v = u16(b, o)
                    var objectType = v shr 11
                    var freqIndex = (v shr 7) and 0xF
                    var channel = (v shr 3) and 0xF
                    if (freqIndex == 15) {
                        // Explicit 24-bit frequency: fall back to the closest standard index.
                        if (o + 5 > to) return null
                        val rate = ((u32(b, o) shr 7) and 0xFFFFFF).toInt()
                        freqIndex = frequencyIndexOf(rate)
                        channel = ((u16(b, o + 3) shr 11) and 0xF)
                    }
                    // HE-AAC/PS signal the LC core in ADTS.
                    if (objectType !in 1..4) objectType = 2
                    return AudioConfig(objectType, freqIndex, channel.coerceIn(1, 7))
                }
                else -> o += len
            }
        }
        return null
    }

    // ── Media segments ───────────────────────────────────────────────────────────────────────────

    /**
     * Byte length of the leading boxes up to and including the first `moof`, or null when [head] is too
     * short to tell (caller fetches more). Lets callers range-request just the fragment header.
     */
    fun moofEnd(head: ByteArray): Long? {
        var o = 0
        while (o + 8 <= head.size) {
            var size = u32(head, o)
            val type = String(head, o + 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) {
                if (o + 16 > head.size) return null
                size = (u32(head, o + 8) shl 32) or u32(head, o + 12)
            }
            if (size < 8) return null
            if (type == "moof") return o + size
            o += size.toInt()
        }
        return null
    }

    /**
     * Sample layout of the fragment in [segment] (which must contain at least the whole `moof`).
     * Handles one track, one or more `trun` boxes.
     */
    fun parseFragment(segment: ByteArray): Fragment? {
        val top = boxes(segment, 0, segment.size)
        val moof = top.firstOrNull { it.type == "moof" } ?: return null
        val traf = child(segment, moof, "traf") ?: return null
        val tfhd = child(segment, traf, "tfhd") ?: return null
        val tfhdFlags = (u32(segment, tfhd.payload) and 0xFFFFFF).toInt()
        var p = tfhd.payload + 8 // version/flags + track id
        var baseOffset = moof.start.toLong()
        if (tfhdFlags and 0x1 != 0) {
            baseOffset = (u32(segment, p) shl 32) or u32(segment, p + 4)
            p += 8
        }
        if (tfhdFlags and 0x2 != 0) p += 4
        if (tfhdFlags and 0x8 != 0) p += 4
        val defaultSize = if (tfhdFlags and 0x10 != 0) u32(segment, p).toInt() else 0

        val sizes = mutableListOf<Int>()
        var dataStart: Long? = null
        for (trun in boxes(segment, traf.payload, traf.end.coerceAtMost(segment.size)).filter { it.type == "trun" }) {
            val flags = (u32(segment, trun.payload) and 0xFFFFFF).toInt()
            var q = trun.payload + 4
            val count = u32(segment, q).toInt()
            q += 4
            if (flags and 0x1 != 0) {
                val offset = u32(segment, q).toInt() // signed 32-bit
                if (dataStart == null) dataStart = baseOffset + offset
                q += 4
            }
            if (flags and 0x4 != 0) q += 4
            repeat(count) {
                if (flags and 0x100 != 0) q += 4
                val size = if (flags and 0x200 != 0) u32(segment, q).toInt().also { q += 4 } else defaultSize
                if (flags and 0x400 != 0) q += 4
                if (flags and 0x800 != 0) q += 4
                sizes += size
            }
        }
        if (sizes.isEmpty()) return null
        val start = dataStart ?: top.firstOrNull { it.type == "mdat" && it.start >= moof.end }?.payload?.toLong() ?: return null
        return Fragment(sizes.toIntArray(), start)
    }

    /** ADTS frames for a complete segment. */
    fun transmux(segment: ByteArray, config: AudioConfig): ByteArray? {
        val fragment = parseFragment(segment) ?: return null
        val out = ByteArray(fragment.adtsSize.toInt())
        var src = fragment.dataStart.toInt()
        var dst = 0
        for (size in fragment.sampleSizes) {
            if (src + size > segment.size) return null
            writeHeader(out, dst, size + ADTS_HEADER_SIZE, config)
            System.arraycopy(segment, src, out, dst + ADTS_HEADER_SIZE, size)
            src += size
            dst += size + ADTS_HEADER_SIZE
        }
        return out
    }

    fun writeHeader(out: ByteArray, o: Int, frameLength: Int, c: AudioConfig) {
        out[o] = 0xFF.toByte()
        out[o + 1] = 0xF1.toByte() // MPEG-4, layer 0, no CRC
        out[o + 2] = ((((c.objectType - 1) and 0x3) shl 6) or ((c.frequencyIndex and 0xF) shl 2) or ((c.channelConfig shr 2) and 0x1)).toByte()
        out[o + 3] = ((((c.channelConfig and 0x3) shl 6)) or ((frameLength shr 11) and 0x3)).toByte()
        out[o + 4] = ((frameLength shr 3) and 0xFF).toByte()
        out[o + 5] = ((((frameLength and 0x7) shl 5)) or 0x1F).toByte()
        out[o + 6] = 0xFC.toByte()
    }
}
