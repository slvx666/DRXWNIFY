/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Is the bitrate real? Lossy encoders throw away the highest frequencies — MP3 at 128 kbps cuts
 * everything above ~16 kHz, at 320 kbps above ~20 kHz — and that cut stays in the file forever, so
 * a "FLAC" or a "320 kbps" made from a 128 kbps file still stops at 16 kHz. The check decodes a
 * stretch of the track that is already on the phone, averages its spectrum, finds where the sound
 * ends and compares that with what the stated format should reach.
 *
 * Deliberately light: about 40 seconds of audio, a 4096-point FFT, no network. It gives a
 * probability, not a verdict of a studio analyser.
 */
object SpectrumCheck {
    data class Verdict(
        /** Where the steady sound ends, Hz (not where the last stray peak is). */
        val cutoffHz: Int,
        /** What the stated format should reach, Hz. */
        val expectedHz: Int,
        /** 0..100: how likely the file is an upscaled lower-quality one. */
        val upscaledPercent: Int,
        /** How steep the fall right above the cut is, dB per 500 Hz: an encoder's wall is steep. */
        val wallDb: Int,
        /** Spectrogram as ARGB pixels, [imageWidth] x [imageHeight], high frequencies on top. */
        val pixels: IntArray,
        val imageWidth: Int,
        val imageHeight: Int,
        /** Per frequency (0 Hz .. nyquist, [PROFILE_POINTS] steps): the typical (median) level, dB. */
        val profileDb: FloatArray,
        /** Per frequency: the share of moments the band carries sound at all, 0..1. */
        val occupancy: FloatArray,
        val sampleRate: Int,
        val seconds: Int,
        /** What a lossy encoder cutting where this file is cut runs at, kbps; null: no encoder cut. */
        val equivalentKbps: Int? = null,
        /** The spectrum falls off a wall (an encoder's low-pass) rather than fading out. */
        val hasCliff: Boolean = false,
        /** Measured in the middle of the track (not its intro). */
        val fromMiddle: Boolean = false,
    ) {
        val upscaled: Boolean get() = upscaledPercent >= 60
    }

    private val verdicts = MutableStateFlow<Map<String, Verdict>>(emptyMap())

    fun verdictFlow(mediaId: String): Flow<Verdict?> = verdicts.map { it[mediaId] }

    private const val FFT_SIZE = 4096
    private const val MAX_SECONDS = 60
    private const val MAX_BYTES = 48L shl 20
    private const val SPECTROGRAM_COLUMNS = 720
    private const val SPECTROGRAM_ROWS = 512
    const val PROFILE_POINTS = 512

    /**
     * Analyses [mediaId] from whatever part of it is cached. [codec] and [kbps] are what the format
     * claims. Returns null when nothing of the track is on the phone yet.
     */
    suspend fun analyze(
        mediaId: String,
        codec: String?,
        kbps: Int?,
        caches: List<SimpleCache>,
    ): Verdict? = withContext(Dispatchers.IO) {
        val file = File.createTempFile("spec_", ".bin")
        try {
            val keys = listOf(mediaId, "fbrescue:$mediaId")
            val copied = keys.firstNotNullOfOrNull { key ->
                caches.firstNotNullOfOrNull { cache -> copyCached(cache, key, file).takeIf { it > 64 * 1024 } }
            } ?: return@withContext null
            Timber.d("SpectrumCheck: %s — %d bytes cached", mediaId, copied)
            // The middle of the track first: intros are often quiet or band-limited and say little.
            val fromMiddle = decode(file, fromMiddle = true)
            val (pcm, sampleRate) = fromMiddle ?: decode(file, fromMiddle = false) ?: return@withContext null
            val verdict = measure(pcm, sampleRate, codec, kbps, fromMiddle = fromMiddle != null)
            verdicts.value = verdicts.value + (mediaId to verdict)
            verdict
        } catch (t: Throwable) {
            Timber.w(t, "SpectrumCheck failed for %s", mediaId)
            null
        } finally {
            file.delete()
        }
    }

    /** Copies the cached start of [key] (never touching the network) into [dest]. */
    private fun copyCached(cache: SimpleCache, key: String, dest: File): Long {
        if (cache.getCachedBytes(key, 0, 1) <= 0) return 0
        val source = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory { NoNetwork }
            .createDataSource()
        var total = 0L
        runCatching {
            source.open(DataSpec.Builder().setUri(Uri.parse(key)).setKey(key).build())
            dest.outputStream().use { out ->
                val buffer = ByteArray(256 * 1024)
                while (total < MAX_BYTES) {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    out.write(buffer, 0, read)
                    total += read
                }
            }
        }
        runCatching { source.close() }
        return total
    }

    /** Decodes up to [MAX_SECONDS] into mono floats, from the start or from the middle of the track. */
    private fun decode(file: File, fromMiddle: Boolean): Pair<FloatArray, Int>? {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (fromMiddle) {
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
            if (durationUs < (MAX_SECONDS + 30) * 1_000_000L) { extractor.release(); return null }
            extractor.seekTo((durationUs - MAX_SECONDS * 1_000_000L) / 2, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val out = FloatArrayBuilder(sampleRate * MAX_SECONDS)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var pcmFloat = false
        try {
            while (!outputDone && out.size < sampleRate * MAX_SECONDS) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (pcmFloat) {
                            val floats = buffer.asFloatBuffer()
                            while (floats.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += floats.get() }
                                out.add(sum / channels)
                            }
                        } else {
                            val shorts = buffer.asShortBuffer()
                            while (shorts.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += shorts.get() / 32768f }
                                out.add(sum / channels)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        return if (out.size >= FFT_SIZE * 8) out.toArray() to sampleRate else null
    }

    private fun measure(pcm: FloatArray, sampleRate: Int, codec: String?, kbps: Int?, fromMiddle: Boolean): Verdict {
        val bins = FFT_SIZE / 2
        val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }
        val frameCount = min(SPECTROGRAM_COLUMNS, max(1, (pcm.size - FFT_SIZE) / (FFT_SIZE / 4)))
        val hop = max(1, (pcm.size - FFT_SIZE) / frameCount)
        // Every frame's spectrum in dB, kept for the median and the picture.
        val frames = Array(frameCount) { FloatArray(bins) }
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val hzPerBin = sampleRate.toDouble() / FFT_SIZE
        fun bin(hz: Int) = (hz / hzPerBin).toInt().coerceIn(0, bins - 1)
        val refFrom = bin(2000)
        val refTo = bin(6000)
        val frameRef = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            val start = f * hop
            for (i in 0 until FFT_SIZE) { re[i] = (pcm[start + i] * window[i]).toDouble(); im[i] = 0.0 }
            fft(re, im)
            val row = frames[f]
            var refSum = 0.0
            for (k in 0 until bins) {
                row[k] = (10 * log10(re[k] * re[k] + im[k] * im[k] + 1e-12)).toFloat()
                if (k in refFrom until refTo) refSum += row[k]
            }
            frameRef[f] = (refSum / (refTo - refFrom)).toFloat()
        }
        // Silent frames (pauses, fade-outs) say nothing about the cut: only frames with music count.
        val loudFrames = frameRef.withIndex().sortedByDescending { it.value }.take(max(1, frameCount * 3 / 4)).map { it.index }

        // Per band: the typical level against each frame's own 2–6 kHz loudness (median — the stray
        // peaks and the faint noise an encoder sprinkles above an old cut don't move it), and how
        // often the band carries real sound.
        val median = FloatArray(bins)
        val occupancy = FloatArray(bins)
        val column = FloatArray(loudFrames.size)
        for (k in 0 until bins) {
            var present = 0
            loudFrames.forEachIndexed { i, f ->
                val rel = frames[f][k] - frameRef[f]
                column[i] = rel
                if (rel > -PRESENT_DB) present++
            }
            column.sort()
            median[k] = column[column.size / 2]
            occupancy[k] = present.toFloat() / loudFrames.size
        }
        val smooth = smoothOver(median, bin(100).coerceAtLeast(1))
        val occ = smoothOver(occupancy, bin(150).coerceAtLeast(1))

        // The cut is a CLIFF: an encoder's low-pass drops the level by tens of dB within a few hundred
        // Hz, and that stays true however the file was blown up later. A real recording fades out
        // gradually. So: the frequency with the biggest drop between the band just below and the
        // band just above it.
        val side = bin(1200) - bin(0)
        val gap = max(1, bin(200))
        val searchFrom = bin(CLIFF_SEARCH_FROM_HZ)
        val searchTo = bins - side - gap - 1
        var bestDrop = 0f
        var bestBin = -1
        for (c in searchFrom..searchTo) {
            val drop = mean(smooth, c - side, c - gap) - mean(smooth, c + gap, c + side)
            if (drop > bestDrop) { bestDrop = drop; bestBin = c }
        }
        // The floor: what the empty top of the spectrum looks like.
        val floor = smooth.copyOfRange(refTo, bins).sorted().let { it[it.size / 10] }
        val hasCliff = bestBin > 0 && bestDrop >= CLIFF_DB &&
            mean(smooth, bestBin + gap, min(bins - 1, bestBin + side)) <= floor + CLIFF_FLOOR_MARGIN_DB
        val cutoffBin = if (hasCliff) {
            // Exactly where the level crosses the middle of the fall.
            val high = mean(smooth, bestBin - side, bestBin - gap)
            val low = mean(smooth, bestBin + gap, bestBin + side)
            val mid = (high + low) / 2
            (max(0, bestBin - gap * 2)..min(bins - 1, bestBin + gap * 2)).firstOrNull { smooth[it] < mid } ?: bestBin
        } else {
            // No wall: where the sound fades into the floor.
            var k = bins - 1
            while (k > refTo && smooth[k] < floor + FADE_ABOVE_FLOOR_DB) k--
            k
        }
        val cutoffHz = (cutoffBin * hzPerBin).toInt()
        val nyquist = sampleRate / 2

        val lossless = codec?.uppercase() in setOf("FLAC", "ALAC", "WAV")
        val claimed = when {
            lossless -> 1411
            kbps != null && kbps > 0 -> kbps
            else -> null
        }
        // A wall right under nyquist is the resampler of a genuine file, not a lossy encoder.
        val equivalent = if (hasCliff && cutoffHz < nyquist - NYQUIST_WALL_MARGIN_HZ) equivalentKbps(cutoffHz) else null
        var percent = when {
            equivalent == null -> 3
            claimed == null -> if (equivalent <= 128) 50 else 10
            else -> {
                val ratio = claimed.toDouble() / equivalent
                when {
                    ratio <= 1.2 -> 5
                    ratio <= 1.6 -> 40
                    ratio <= 2.2 -> 75
                    else -> 93
                }
            }
        }
        // A shallow wall is weaker evidence than a sheer one.
        if (equivalent != null && bestDrop < STRONG_CLIFF_DB && percent > 10) percent -= 10
        val expected = expectedCutoff(codec, kbps, sampleRate)

        // The picture: frames left to right, high frequencies on top, levels against the loudest.
        // Each pixel row shows the AVERAGE power of its bins: a lone peak no longer paints a bright
        // dot above the cut, so the picture shows the cut the way Spek does.
        val loudest = frames.maxOf { it.max() }
        val width = frameCount
        val height = SPECTROGRAM_ROWS
        val pixels = IntArray(width * height)
        for (x in 0 until width) {
            val row = frames[x]
            for (y in 0 until height) {
                val from = y * bins / height
                val to = max(from + 1, (y + 1) * bins / height)
                var power = 0.0
                for (k in from until to) power += 10.0.pow(row[k] / 10.0)
                val db = (10 * log10(power / (to - from) + 1e-12)).toFloat()
                val level = ((db - (loudest - RANGE_DB)) / RANGE_DB).coerceIn(0f, 1f)
                pixels[(height - 1 - y) * width + x] = palette(level)
            }
        }
        val profile = FloatArray(PROFILE_POINTS) { i -> smooth[i * bins / PROFILE_POINTS] }
        val occProfile = FloatArray(PROFILE_POINTS) { i -> occ[i * bins / PROFILE_POINTS] }
        return Verdict(
            cutoffHz = cutoffHz,
            expectedHz = expected,
            upscaledPercent = percent,
            wallDb = bestDrop.toInt().coerceAtLeast(0),
            pixels = pixels,
            imageWidth = width,
            imageHeight = height,
            profileDb = profile,
            occupancy = occProfile,
            sampleRate = sampleRate,
            seconds = pcm.size / sampleRate,
            equivalentKbps = equivalent,
            hasCliff = hasCliff,
            fromMiddle = fromMiddle,
        )
    }

    private fun mean(values: FloatArray, from: Int, to: Int): Float {
        val a = from.coerceIn(0, values.size - 1)
        val b = to.coerceIn(a, values.size - 1)
        var sum = 0f
        for (k in a..b) sum += values[k]
        return sum / (b - a + 1)
    }

    /** What a lossy encoder that cuts at [cutoffHz] usually runs at, kbps (MP3/AAC rules of thumb). */
    private fun equivalentKbps(cutoffHz: Int): Int = when {
        cutoffHz < 11_500 -> 64
        cutoffHz < 13_500 -> 80
        cutoffHz < 15_000 -> 96
        cutoffHz < 16_700 -> 128
        cutoffHz < 17_700 -> 160
        cutoffHz < 18_900 -> 192
        cutoffHz < 19_700 -> 224
        cutoffHz < 20_700 -> 320
        else -> 1411
    }

    private fun smoothOver(values: FloatArray, radius: Int): FloatArray = FloatArray(values.size) { i ->
        var sum = 0f
        var n = 0
        for (k in max(0, i - radius)..min(values.size - 1, i + radius)) { sum += values[k]; n++ }
        sum / n
    }

    /** Dark blue → purple → orange → pale yellow, like the usual analysers. */
    private fun palette(level: Float): Int {
        val stops = floatArrayOf(0f, 0.35f, 0.65f, 0.85f, 1f)
        val colors = intArrayOf(0xFF05040F.toInt(), 0xFF3B0F6F.toInt(), 0xFFB5367A.toInt(), 0xFFF98C2F.toInt(), 0xFFFCF4B6.toInt())
        var i = 0
        while (i < stops.size - 2 && level > stops[i + 1]) i++
        val t = ((level - stops[i]) / (stops[i + 1] - stops[i])).coerceIn(0f, 1f)
        fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF
        fun mix(shift: Int) = (ch(colors[i], shift) + (ch(colors[i + 1], shift) - ch(colors[i], shift)) * t).toInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private const val PRESENT_DB = 45f
    private const val CLIFF_SEARCH_FROM_HZ = 9_000
    /** A fall at least this steep within ~1 kHz is an encoder's low-pass… */
    private const val CLIFF_DB = 20f
    /** …and this steep, beyond doubt. */
    private const val STRONG_CLIFF_DB = 30f
    /** Above a real cut the spectrum is (nearly) as empty as its emptiest part. */
    private const val CLIFF_FLOOR_MARGIN_DB = 15f
    private const val FADE_ABOVE_FLOOR_DB = 10f
    private const val NYQUIST_WALL_MARGIN_HZ = 600
    private const val RANGE_DB = 90f

    /** Where a genuine file of this kind stops, Hz. */
    private fun expectedCutoff(codec: String?, kbps: Int?, sampleRate: Int): Int {
        val nyquist = sampleRate / 2
        val c = codec?.uppercase().orEmpty()
        return when {
            c == "FLAC" || c == "ALAC" || c == "WAV" -> min(nyquist, 21000)
            c == "MP3" -> when {
                (kbps ?: 0) >= 300 -> 19800
                (kbps ?: 0) >= 240 -> 19500
                (kbps ?: 0) >= 180 -> 18800
                (kbps ?: 0) >= 150 -> 17500
                else -> 15800
            }
            c == "OPUS" -> min(nyquist, 19800)
            else -> when { // AAC, Vorbis…
                (kbps ?: 0) >= 240 -> 19500
                (kbps ?: 0) >= 180 -> 18500
                (kbps ?: 0) >= 120 -> 16500
                else -> 15000
            }
        }
    }


    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wr = cos(angle)
            val wi = sin(angle)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val next = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = next
                }
                i += len
            }
            len = len shl 1
        }
    }

    private class FloatArrayBuilder(capacity: Int) {
        private var data = FloatArray(capacity)
        var size = 0
            private set

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    private object NoNetwork : DataSource {
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long = throw java.io.IOException("not cached")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw java.io.IOException("not cached")
        override fun getUri(): Uri? = null
        override fun close() {}
    }
}
