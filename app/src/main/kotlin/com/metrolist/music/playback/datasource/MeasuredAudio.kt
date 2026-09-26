/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.datasource

import java.util.concurrent.ConcurrentHashMap

/**
 * The real bitrate of streams whose service doesn't say it (VK's HLS), measured from the audio
 * itself once the app has assembled it. Nothing is assumed: until a stream is measured its bitrate
 * is simply unknown.
 */
object MeasuredAudio {
    data class Measured(val bitrate: Int, val sampleRate: Int?)

    private val measured = ConcurrentHashMap<String, Measured>()
    private val waiting = ConcurrentHashMap<String, MutableList<(Measured) -> Unit>>()

    fun record(key: String, value: Measured) {
        measured[key] = value
        waiting.remove(key)?.forEach { runCatching { it(value) } }
    }

    /** Calls [action] with the measurement of [key]: now if known, else once it is taken. */
    fun whenMeasured(key: String, action: (Measured) -> Unit) {
        measured[key]?.let {
            action(it)
            return
        }
        waiting.getOrPut(key) { java.util.Collections.synchronizedList(mutableListOf()) }.add(action)
        // Measured in between: don't miss it.
        measured[key]?.let { value -> waiting.remove(key)?.forEach { runCatching { it(value) } } }
    }

    /**
     * Average bitrate and sample rate of MPEG audio (MP3) or ADTS AAC in [data], from its own frame
     * headers — right for variable bitrate too. Null when the data is neither.
     */
    fun analyze(data: ByteArray): Measured? {
        var i = 0
        // An ID3v2 tag in front of MP3 data.
        if (data.size > 10 && data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()) {
            val size = ((data[6].toInt() and 0x7F) shl 21) or ((data[7].toInt() and 0x7F) shl 14) or
                ((data[8].toInt() and 0x7F) shl 7) or (data[9].toInt() and 0x7F)
            i = 10 + size
        }
        var frames = 0L
        var bytes = 0L
        var seconds = 0.0
        var sampleRate: Int? = null
        while (i + 7 < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = data[i + 1].toInt() and 0xFF
            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) {
                i++
                continue
            }
            val frame = if ((b1 and 0x06) == 0) adtsFrame(data, i) else mpegFrame(data, i)
            if (frame == null) {
                i++
                continue
            }
            val (length, samples, rate) = frame
            frames++
            bytes += length
            seconds += samples.toDouble() / rate
            sampleRate = rate
            i += length
        }
        if (frames < 10 || seconds <= 0.0) return null
        return Measured(bitrate = (bytes * 8 / seconds).toInt(), sampleRate = sampleRate)
    }

    private val MPEG1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val MPEG2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val MPEG1_RATES = intArrayOf(44100, 48000, 32000)
    private val ADTS_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** (frame length, samples, sample rate) of an MPEG Layer III frame at [i]. */
    private fun mpegFrame(data: ByteArray, i: Int): Triple<Int, Int, Int>? {
        val b1 = data[i + 1].toInt() and 0xFF
        val b2 = data[i + 2].toInt() and 0xFF
        val version = (b1 shr 3) and 3 // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
        val layer = (b1 shr 1) and 3 // 1 = Layer III
        if (version == 1 || layer != 1) return null
        val bitrateIndex = (b2 shr 4) and 0xF
        val rateIndex = (b2 shr 2) and 3
        if (bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
        val mpeg1 = version == 3
        val kbps = (if (mpeg1) MPEG1_L3 else MPEG2_L3)[bitrateIndex]
        val rate = MPEG1_RATES[rateIndex] / when (version) {
            3 -> 1
            2 -> 2
            else -> 4
        }
        val padding = (b2 shr 1) and 1
        val length = (if (mpeg1) 144 else 72) * kbps * 1000 / rate + padding
        if (length < 24) return null
        return Triple(length, if (mpeg1) 1152 else 576, rate)
    }

    /** (frame length, samples, sample rate) of an ADTS (AAC) frame at [i]. */
    private fun adtsFrame(data: ByteArray, i: Int): Triple<Int, Int, Int>? {
        val b2 = data[i + 2].toInt() and 0xFF
        val b3 = data[i + 3].toInt() and 0xFF
        val b4 = data[i + 4].toInt() and 0xFF
        val b5 = data[i + 5].toInt() and 0xFF
        val rateIndex = (b2 shr 2) and 0xF
        if (rateIndex >= ADTS_RATES.size) return null
        val length = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 shr 5) and 0x07)
        if (length < 7) return null
        return Triple(length, 1024, ADTS_RATES[rateIndex])
    }
}
