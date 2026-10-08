/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
 * Is the bitrate real? A lossy encoder cuts the top of the spectrum off — MP3 128 at ~16 kHz, 192
 * at ~19, 320 at ~20 — and blowing the file up later never brings the top back.
 *
 * The check works frame by frame, not on an average: three short stretches of the track (start,
 * middle, end), each split into ~93 ms frames; quiet frames are skipped; every loud frame gets its
 * own cutoff (the highest frequency still clearly above that frame's empty floor). What decides is
 * the DISTRIBUTION of those cutoffs — a 320 made from a 128 may reach 20 kHz in a few frames
 * (noise, an upscaler) while most of it stops at 16 kHz, and a track pieced together from poor
 * samples jumps between 15 and 20 kHz. Light enough for a phone: ~24 s of audio, no network.
 */
object SpectrumCheck {
    enum class Kind {
        /** Reaches what its format promises. */
        GENUINE,

        /** Full band, no encoder cut at all (lossless-like). */
        FULL_BAND,

        /** Most of it stops well below what the format promises: made from a lower-quality file. */
        TRANSCODED,

        /** The cut jumps around: pieced together from sources (samples) of different quality. */
        MIXED,
    }

    data class Verdict(
        /** Median cutoff of the loud frames, Hz: the file's real top. */
        val cutoffHz: Int,
        /** 95th percentile of the frame cutoffs, Hz: how high the best moments reach. */
        val maxCutoffHz: Int,
        /** 10th percentile, Hz: how low the worst moments fall. */
        val lowCutoffHz: Int,
        /** Share of loud frames cut at 16.5 kHz or lower (0..1). */
        val lowShare: Float,
        /** Standard deviation of the frame cutoffs, Hz. */
        val spreadHz: Int,
        /** What the stated format should reach, Hz. */
        val expectedHz: Int,
        val kind: Kind,
        /** 0..100: how likely the file is an upscaled lower-quality one. */
        val upscaledPercent: Int,
        /** What a lossy encoder cutting at [cutoffHz] runs at, kbps; 1411 = no cut. */
        val equivalentKbps: Int?,
        /** The bitrate the verdict compares against (the analysed file's own, else the stream's). */
        val claimedKbps: Int?,
        /** Spectrogram as ARGB pixels, [imageWidth] x [imageHeight], high frequencies on top. */
        val pixels: IntArray,
        val imageWidth: Int,
        val imageHeight: Int,
        /** Per spectrogram column: that frame's cutoff in Hz, NaN for a quiet frame. */
        val frameCutoffs: FloatArray,
        /** Columns where a new stretch of the track starts. */
        val segmentStarts: List<Int>,
        /** Per frequency (0 Hz .. nyquist, [PROFILE_POINTS] steps): median level against 2–6 kHz, dB. */
        val profileDb: FloatArray,
        /** Per frequency: the share of loud frames whose cutoff is at least that high, 0..1. */
        val occupancy: FloatArray,
        val sampleRate: Int,
        val seconds: Int,
        val loudFrames: Int,
        /** The downloaded file itself was analysed (not the player's cache). */
        val fromFile: Boolean,
        /**
         * A former encoder ceiling, Hz: a sharp horizontal step at the same frequency through the
         * track, still there under whatever a later re-encode painted above it. Null when none.
         */
        val shelfHz: Int? = null,
        /** Share of loud frames that show that step, 0..1. */
        val shelfShare: Float = 0f,
        /** The top the audio really had, Hz: the former ceiling when there is one, else [cutoffHz]. */
        val realCutoffHz: Int = cutoffHz,
    ) {
        val upscaled: Boolean get() = upscaledPercent >= 60
    }

    private val verdicts = MutableStateFlow<Map<String, Verdict>>(emptyMap())

    fun verdictFlow(mediaId: String): Flow<Verdict?> = verdicts.map { it[mediaId] }

    private const val FFT_SIZE = 4096
    private const val SEGMENT_SECONDS = 6
    private const val SEGMENTS = 8

    /** Former-ceiling search: ± this much on each side of an edge, the stretch length, the share it needs. */
    private const val SHELF_HALF_HZ = 65
    private const val SHELF_WINDOW_SECONDS = 4
    private const val SHELF_MIN_SHARE = 0.12f
    private const val SHELF_MIN_WINDOWS = 8
    private const val SHELF_MIN_DEPTH_DB = 12.0
    private const val LOUDNESS_SAMPLE_US = 350_000L
    private const val SHELF_FROM_HZ = 11_000
    private const val SHELF_TO_HZ = 21_000
    private const val MAX_BYTES = 48L shl 20
    private const val SPECTROGRAM_ROWS = 384
    const val PROFILE_POINTS = 512

    /** A frame quieter than this (dBFS) says nothing about the cut. */
    private const val SILENCE_DBFS = -42.0

    /** A band counts as sound when it is this far above the frame's own empty floor. */
    private const val ABOVE_FLOOR_DB = 15f
    private const val LOW_CUT_HZ = 16_500

    /** Where the audio of a track can be read from: the downloaded file, else the caches. */
    private sealed interface Source {
        data class Local(val file: File, val temporary: Boolean) : Source
        data class Content(val uri: Uri) : Source
    }

    /** Older callers: one id, caches only. */
    suspend fun analyze(mediaId: String, codec: String?, kbps: Int?, caches: List<SimpleCache>): Verdict? =
        analyze(null, listOf(mediaId), codec, kbps, caches, null)

    /**
     * Analyses the track known under any of [mediaIds] (the first is the one the verdict is filed
     * under): the downloaded file [exportedUri] when there is one, else whatever the caches hold.
     * [codec] and [kbps] are what the stream claims. Null when nothing of it is on the phone.
     */
    suspend fun analyze(
        context: Context?,
        mediaIds: List<String>,
        codec: String?,
        kbps: Int?,
        caches: List<SimpleCache>,
        exportedUri: Uri?,
    ): Verdict? = withContext(Dispatchers.IO) {
        val ids = mediaIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return@withContext null
        val source = openSource(context, ids, caches, exportedUri) ?: return@withContext null
        try {
            val decoded = decodeSegments(context, source) ?: return@withContext null
            val shelf = runCatching { scanShelves(context, source) }
                .onFailure { Timber.w(it, "SpectrumCheck: ceiling scan failed") }
                .getOrNull()
            val fromFile = source is Source.Content
            val fileKbps = decoded.bitrateKbps?.takeIf { fromFile }
            val verdict = measure(decoded, codec, fileKbps ?: kbps, fromFile, shelf)
            verdicts.value = verdicts.value + ids.associateWith { verdict }
            verdict
        } catch (t: Throwable) {
            Timber.w(t, "SpectrumCheck failed for %s", ids.first())
            null
        } finally {
            (source as? Source.Local)?.takeIf { it.temporary }?.file?.delete()
        }
    }

    private fun openSource(context: Context?, ids: List<String>, caches: List<SimpleCache>, exportedUri: Uri?): Source? {
        // The downloaded file first: it is complete, and it is what a PC analyser (Spek) would open.
        if (context != null && exportedUri != null &&
            runCatching { context.contentResolver.openFileDescriptor(exportedUri, "r")?.close() }.isSuccess
        ) {
            return Source.Content(exportedUri)
        }
        val file = File.createTempFile("spec_", ".bin")
        val keys = ids.flatMap { listOf(it, "fbrescue:$it") }
        val copied = keys.firstNotNullOfOrNull { key ->
            caches.firstNotNullOfOrNull { cache -> copyCached(cache, key, file).takeIf { it > 64 * 1024 } }
        }
        if (copied == null) {
            file.delete()
            return null
        }
        return Source.Local(file, temporary = true)
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

    private class Decoded(
        val segments: List<FloatArray>,
        val sampleRate: Int,
        val durationUs: Long,
        val bitrateKbps: Int?,
    )

    private fun extractorFor(context: Context?, source: Source): MediaExtractor = MediaExtractor().apply {
        when (source) {
            is Source.Local -> setDataSource(source.file.absolutePath)
            is Source.Content -> setDataSource(requireNotNull(context), source.uri, null)
        }
    }

    /**
     * Mono PCM of three short stretches (15 %, 50 %, 80 % into the track); a short or partly cached
     * track gives what it has. One decoder, flushed between stretches.
     */
    private fun decodeSegments(context: Context?, source: Source): Decoded? {
        val extractor = extractorFor(context, source)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); return null }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: run { extractor.release(); return null }
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
        val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE) else 0
        // Eight stretches spread over the track: a former ceiling often shows only in some parts
        // (quiet passages), so three samples could miss it.
        val starts = if (durationUs > 60_000_000L) {
            (0 until SEGMENTS).map { (durationUs * (0.05 + 0.88 * it / (SEGMENTS - 1))).toLong() }
        } else if (durationUs > (SEGMENT_SECONDS * 4) * 1_000_000L) {
            listOf(0.15, 0.5, 0.8).map { (durationUs * it).toLong() }
        } else {
            listOf(0L)
        }
        val seconds = if (starts.size == 1) SEGMENT_SECONDS * 3 else SEGMENT_SECONDS
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmFloat = false
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val segments = mutableListOf<FloatArray>()
        val info = MediaCodec.BufferInfo()
        try {
            for ((i, startUs) in starts.withIndex()) {
                if (i > 0) codec.flush()
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val wanted = sampleRate * seconds
                val out = FloatArrayBuilder(wanted)
                var inputDone = false
                var outputDone = false
                var idle = 0
                while (!outputDone && out.size < wanted && idle < 200) {
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
                            idle = 0
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
                        else -> idle++
                    }
                }
                if (out.size >= FFT_SIZE * 4) segments += out.toArray()
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        if (segments.isEmpty()) return null
        return Decoded(segments, sampleRate, durationUs, (bitrate / 1000).takeIf { it > 0 })
    }

    private fun measure(decoded: Decoded, codec: String?, kbps: Int?, fromFile: Boolean, shelf: Shelf? = null): Verdict {
        val sampleRate = decoded.sampleRate
        val bins = FFT_SIZE / 2
        val hzPerBin = sampleRate.toDouble() / FFT_SIZE
        fun bin(hz: Int) = (hz / hzPerBin).toInt().coerceIn(0, bins - 1)
        val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1)) }
        val refFrom = bin(2000)
        val refTo = bin(6000)
        val floorFrom = bin(6000)
        val smoothRadius = max(1, bin(200) - bin(0))
        val nyquist = sampleRate / 2

        val columns = mutableListOf<FloatArray>()
        val cutoffs = mutableListOf<Float>()
        val relative = mutableListOf<FloatArray>()
        val segmentStarts = mutableListOf<Int>()
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val db = FloatArray(bins)
        val smooth = FloatArray(bins)
        val prefix = DoubleArray(bins + 1)
        val scratch = FloatArray(bins - floorFrom)
        for (pcm in decoded.segments) {
            segmentStarts += columns.size
            var start = 0
            while (start + FFT_SIZE <= pcm.size) {
                var energy = 0.0
                for (i in 0 until FFT_SIZE) {
                    val s = pcm[start + i].toDouble()
                    energy += s * s
                    re[i] = s * window[i]
                    im[i] = 0.0
                }
                fft(re, im)
                for (k in 0 until bins) db[k] = (10 * log10(re[k] * re[k] + im[k] * im[k] + 1e-12)).toFloat()
                columns += db.copyOf()
                val rmsDb = 10 * log10(energy / FFT_SIZE + 1e-20)
                if (rmsDb < SILENCE_DBFS) {
                    cutoffs += Float.NaN
                    start += FFT_SIZE
                    continue
                }
                // Box-smoothed over ±200 Hz, so one stray peak can't carry the cut up.
                for (k in 0 until bins) prefix[k + 1] = prefix[k] + db[k]
                for (k in 0 until bins) {
                    val a = max(0, k - smoothRadius)
                    val b = min(bins - 1, k + smoothRadius)
                    smooth[k] = ((prefix[b + 1] - prefix[a]) / (b - a + 1)).toFloat()
                }
                var ref = 0.0
                for (k in refFrom until refTo) ref += smooth[k]
                ref /= (refTo - refFrom)
                // The frame's own empty floor: the quietest tenth above 6 kHz.
                System.arraycopy(smooth, floorFrom, scratch, 0, scratch.size)
                scratch.sort()
                val floor = scratch[scratch.size / 10]
                var k = bins - 1
                while (k > floorFrom && smooth[k] < floor + ABOVE_FLOOR_DB) k--
                cutoffs += (k * hzPerBin).toFloat()
                relative += FloatArray(bins) { (smooth[it] - ref).toFloat() }
                start += FFT_SIZE
            }
        }

        val loud = cutoffs.filter { !it.isNaN() }.sorted()
        fun pct(p: Double): Int = if (loud.isEmpty()) 0 else loud[((loud.size - 1) * p).toInt()].toInt()
        val median = pct(0.5)
        val high = pct(0.95)
        val low = pct(0.10)
        val lowShare = if (loud.isEmpty()) 0f else loud.count { it <= LOW_CUT_HZ }.toFloat() / loud.size
        val mean = if (loud.isEmpty()) 0.0 else loud.average()
        val spread = if (loud.size < 2) 0 else sqrt(loud.sumOf { (it - mean) * (it - mean) } / loud.size).toInt()

        val lossless = codec?.uppercase() in setOf("FLAC", "ALAC", "WAV")
        val claimed = when {
            lossless -> 1411
            kbps != null && kbps > 0 -> kbps
            else -> null
        }
        val expected = expectedCutoff(codec, kbps, sampleRate)
        val shelfHz = shelf?.hz
        val shelfShare = shelf?.share ?: 0f
        // A step well under where the content (and the format) reaches = the file once stopped there.
        val shelfMatters = shelfHz != null && shelfHz < max(median, expected) - 700 && shelfHz < 20_300
        val realCutoff = if (shelfMatters) min(shelfHz!!, median) else median
        // A cut right under nyquist is the resampler of a genuine file, not a lossy encoder.
        val fullBand = !shelfMatters && median >= min(20_700, nyquist - 600)
        val equivalent = if (fullBand) 1411 else equivalentKbps(realCutoff)
        val pieced = spread > 1_500 && low <= LOW_CUT_HZ && high >= 19_000
        val transcoded = expected > 17_000 && ((high >= 19_500 && median < 18_000) || lowShare > 0.15f)
        var percent = when {
            loud.isEmpty() -> 0
            fullBand -> 3
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
        if (transcoded) percent = max(percent, 80)
        if (pieced) percent = max(percent, 65)
        val shelfUpscale = shelfMatters && (claimed == null || claimed > equivalent * 1.2)
        // Seen in every third stretch or more: certain; in one of eight: likely (an MP3's own
        // band edge at 16 kHz can leave a faint step too, so a rare one alone proves little).
        if (shelfUpscale) percent = max(percent, (55 + shelfShare * 100).toInt().coerceAtMost(97))
        val kind = when {
            fullBand && !pieced -> Kind.FULL_BAND
            shelfUpscale -> Kind.TRANSCODED
            pieced -> Kind.MIXED
            transcoded || percent >= 60 -> Kind.TRANSCODED
            else -> Kind.GENUINE
        }

        // The picture: one column per frame, average power per pixel row (a lone peak doesn't paint).
        val loudest = columns.maxOfOrNull { it.max() } ?: 0f
        val width = columns.size
        val height = SPECTROGRAM_ROWS
        val pixels = IntArray(width * height)
        for (x in 0 until width) {
            val col = columns[x]
            for (y in 0 until height) {
                val from = y * bins / height
                val to = max(from + 1, (y + 1) * bins / height)
                var power = 0.0
                for (b in from until to) power += 10.0.pow(col[b] / 10.0)
                val level = (((10 * log10(power / (to - from) + 1e-12)).toFloat() - (loudest - RANGE_DB)) / RANGE_DB).coerceIn(0f, 1f)
                pixels[(height - 1 - y) * width + x] = palette(level)
            }
        }
        val profile = FloatArray(PROFILE_POINTS) { i ->
            val b = i * bins / PROFILE_POINTS
            if (relative.isEmpty()) -120f else relative.map { it[b] }.sorted()[relative.size / 2]
        }
        val survival = FloatArray(PROFILE_POINTS) { i ->
            val hz = i * nyquist.toFloat() / PROFILE_POINTS
            if (loud.isEmpty()) 0f else loud.count { it >= hz }.toFloat() / loud.size
        }
        return Verdict(
            cutoffHz = median,
            maxCutoffHz = high,
            lowCutoffHz = low,
            lowShare = lowShare,
            spreadHz = spread,
            expectedHz = expected,
            kind = kind,
            upscaledPercent = percent,
            equivalentKbps = equivalent.takeIf { loud.isNotEmpty() },
            claimedKbps = claimed,
            pixels = pixels,
            imageWidth = width,
            imageHeight = height,
            frameCutoffs = cutoffs.toFloatArray(),
            segmentStarts = segmentStarts,
            profileDb = profile,
            occupancy = survival,
            sampleRate = sampleRate,
            seconds = decoded.segments.sumOf { it.size } / sampleRate,
            loudFrames = loud.size,
            fromFile = fromFile,
            shelfHz = shelfHz.takeIf { shelfMatters },
            shelfShare = if (shelfMatters) shelfShare else 0f,
            realCutoffHz = realCutoff,
        )
    }

    /** A former encoder ceiling: [hz], seen in [share] of the track's [windows] stretches. */
    private class Shelf(val hz: Int, val share: Float, val windows: Int)

    /**
     * Looks through the WHOLE track for a former encoder ceiling. The track is cut into 4-second
     * stretches; each stretch's spectrum is averaged over time (what makes the line visible to the
     * eye on a spectrogram) and searched for sharp steps — the band just below a frequency much
     * louder than the band just above, far more than the spectrum's usual unevenness around there.
     * A lowpass leaves such a step at one exact frequency in stretch after stretch, even under the
     * noise a later re-encode paints above it; music does not. The lowest frequency that keeps
     * coming back is the former ceiling.
     */
    private fun scanShelves(context: Context?, source: Source): Shelf? {
        val extractor = extractorFor(context, source)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); return null }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: run { extractor.release(); return null }
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmFloat = false
        val bins = FFT_SIZE / 2
        val hop = FFT_SIZE / 2
        val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1)) }
        val ring = FloatArray(FFT_SIZE)
        var ringPos = 0
        var filled = 0
        var sinceHop = 0
        var sinceWindow = 0
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val sum = DoubleArray(bins)
        var frames = 0
        val counts = IntArray(bins)
        var used = 0
        var limits: IntArray? = null // from, to, half

        fun limitsFor(rate: Int): IntArray {
            val hzPerBin = rate.toDouble() / FFT_SIZE
            val half = max(4, (SHELF_HALF_HZ / hzPerBin).toInt())
            val from = (SHELF_FROM_HZ / hzPerBin).toInt()
            val to = min(((min(SHELF_TO_HZ, rate / 2 - 300)) / hzPerBin).toInt(), bins - 4 * half - 2)
            return intArrayOf(from, to, half, (6000 / hzPerBin).toInt(), (1000 / hzPerBin).toInt())
        }

        fun closeWindow() {
            if (frames >= 5) {
                val l = limits ?: limitsFor(sampleRate).also { limits = it }
                val (from, to, half, floorFrom, around) = l.toList()
                val avg = DoubleArray(bins) { sum[it] / frames }
                val floor = avg.copyOfRange(floorFrom, bins).sorted()[(bins - floorFrom) / 20]
                val prefix = DoubleArray(bins + 1)
                for (b in 0 until bins) prefix[b + 1] = prefix[b] + avg[b]
                val n = to - from + 1
                if (n > 8) {
                    val step = DoubleArray(n) { i ->
                        val c = from + i
                        (prefix[c] - prefix[c - half]) / half - (prefix[c + 1 + half] - prefix[c + 1]) / half
                    }
                    val absPrefix = DoubleArray(n + 1)
                    for (i in 0 until n) absPrefix[i + 1] = absPrefix[i] + kotlin.math.abs(step[i])
                    for (i in 0 until n) {
                        val c = from + i
                        val below = (prefix[c] - prefix[c - half]) / half
                        if (step[i] < 4.0 || below < floor + 6) continue
                        val a = max(0, i - around)
                        val b = min(n, i + around)
                        val rough = max(0.4, (absPrefix[b] - absPrefix[a]) / (b - a))
                        if (step[i] < 3 * rough) continue
                        // A shelf, not the upper flank of one loud tone: it stays down well above too.
                        val wide = (prefix[c + 1 + 4 * half] - prefix[c + 1]) / (4 * half)
                        if (below - wide < 0.6 * step[i]) continue
                        // A ceiling has (almost) nothing above it: the band 0.3–2 kHz over the edge
                        // is far below the band under it. A step inside dense music (a synth's
                        // filter, a hi-hat band) has music on both sides — a few dB apart.
                        val near = around * 3 / 10
                        val depthAbove = (prefix[min(bins, c + 2 * around)] - prefix[min(bins - 1, c + near)]) /
                            max(1, min(bins, c + 2 * around) - min(bins - 1, c + near))
                        val depthBelow = (prefix[c - near] - prefix[max(0, c - 2 * around)]) / max(1, c - near - max(0, c - 2 * around))
                        if (depthBelow - depthAbove < SHELF_MIN_DEPTH_DB) continue
                        // One count per edge: the strongest bin within ±3.
                        var isPeak = true
                        for (d in max(0, i - 3)..min(n - 1, i + 3)) if (step[d] > step[i]) { isPeak = false; break }
                        if (isPeak) counts[c]++
                    }
                    used++
                }
            }
            java.util.Arrays.fill(sum, 0.0)
            frames = 0
        }

        fun push(sample: Float) {
            ring[ringPos] = sample
            ringPos = (ringPos + 1) % FFT_SIZE
            if (filled < FFT_SIZE) filled++
            sinceHop++
            sinceWindow++
            if (filled == FFT_SIZE && sinceHop >= hop) {
                sinceHop = 0
                var energy = 0.0
                for (i in 0 until FFT_SIZE) {
                    val v = ring[(ringPos + i) % FFT_SIZE].toDouble()
                    energy += v * v
                    re[i] = v * window[i]
                    im[i] = 0.0
                }
                if (10 * log10(energy / FFT_SIZE + 1e-20) >= SILENCE_DBFS - 3) {
                    fft(re, im)
                    for (k in 0 until bins) sum[k] += 10 * log10(re[k] * re[k] + im[k] * im[k] + 1e-12)
                    frames++
                }
            }
            if (sinceWindow >= sampleRate * SHELF_WINDOW_SECONDS) {
                sinceWindow = 0
                closeWindow()
            }
        }

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var idle = 0
        try {
            while (!outputDone && idle < 300) {
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
                        idle = 0
                        val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (pcmFloat) {
                            val floats = buffer.asFloatBuffer()
                            while (floats.remaining() >= channels) {
                                var acc = 0f
                                repeat(channels) { acc += floats.get() }
                                push(acc / channels)
                            }
                        } else {
                            val shorts = buffer.asShortBuffer()
                            while (shorts.remaining() >= channels) {
                                var acc = 0f
                                repeat(channels) { acc += shorts.get() / 32768f }
                                push(acc / channels)
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
                        limits = null
                    }
                    else -> idle++
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        closeWindow()
        if (used < SHELF_MIN_WINDOWS) return null
        val l = limits ?: return null
        val from = l[0]
        val to = l[1]
        val hzPerBin = sampleRate.toDouble() / FFT_SIZE
        // Counts merged over ±2 bins (the edge wobbles by a bin), then the lowest frequency that
        // comes back in enough stretches.
        val merged = IntArray(bins) { c -> if (c < from || c > to) 0 else (max(from, c - 2)..min(to, c + 2)).sumOf { counts[it] } }
        for (c in from..to) {
            val share = merged[c].toFloat() / used
            if (merged[c] < 3 || share < SHELF_MIN_SHARE) continue
            var peak = c
            for (d in c..min(to, c + 6)) if (merged[d] > merged[peak]) peak = d
            return Shelf((peak * hzPerBin).toInt(), merged[peak].toFloat() / used, used)
        }
        return null
    }

    // ── The full spectrogram, as an image file ──────────────────────────────────────────────────

    /**
     * Renders the spectrogram of the WHOLE track (like Spek) into a PNG in Pictures/Drxwnify and
     * returns its uri. Streams through the audio once, one FFT per image column, so memory stays
     * flat whatever the length.
     */
    suspend fun renderFull(
        context: Context,
        mediaIds: List<String>,
        caches: List<SimpleCache>,
        exportedUri: Uri?,
        title: String,
        subtitle: String,
    ): Uri? = withContext(Dispatchers.IO) {
        val source = openSource(context, mediaIds.filter { it.isNotBlank() }.distinct(), caches, exportedUri) ?: return@withContext null
        try {
            val columns = FULL_WIDTH
            val image = decodeWhole(context, source, columns) ?: return@withContext null
            val bitmap = drawFull(image, title, subtitle)
            saveImage(context, bitmap, "${sanitize(title)} — spectrogram.png").also { bitmap.recycle() }
        } catch (t: Throwable) {
            Timber.w(t, "Full spectrogram failed")
            null
        } finally {
            (source as? Source.Local)?.takeIf { it.temporary }?.file?.delete()
        }
    }

    /**
     * How loud the track is along its length, for the bar seek bar: [bars] values 0..1, -1 for the
     * stretches not on the phone yet (a stream still being cached). Read from the downloaded file or
     * the caches, never the network. Null when nothing of the track is here.
     */
    suspend fun waveform(
        context: Context,
        mediaIds: List<String>,
        caches: List<SimpleCache>,
        exportedUri: Uri?,
        bars: Int,
        /** The player's length: a partly cached stream would otherwise claim to be as long as what is cached. */
        durationMs: Long = 0,
    ): FloatArray? = withContext(Dispatchers.IO) {
        val ids = mediaIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return@withContext null
        val source = openSource(context, ids, caches, exportedUri) ?: return@withContext null
        try {
            decodeLoudness(context, source, bars, durationMs * 1000)
        } catch (t: Throwable) {
            Timber.w(t, "waveform failed for %s", ids.first())
            null
        } finally {
            (source as? Source.Local)?.takeIf { it.temporary }?.file?.delete()
        }
    }

    /**
     * Loudness per bar from a short sample in the middle of each bar (seek, decode ~0.35 s) instead
     * of the whole track: about ten times faster, and the bars look the same. Decoding everything took
     * ~20 s on a phone, so the bar seek bar showed even bars that long on every downloaded track.
     */
    private fun decodeLoudness(context: Context, source: Source, bars: Int, durationHintUs: Long): FloatArray? {
        val extractor = extractorFor(context, source)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); return null }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: run { extractor.release(); return null }
        val fileUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
        val durationUs = max(fileUs, durationHintUs)
        if (durationUs <= 0) { extractor.release(); return null }
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmFloat = false
        val sumSq = DoubleArray(bars)
        val counts = IntArray(bars)
        val usPerBar = durationUs.toDouble() / bars
        val sampleUs = min(LOUDNESS_SAMPLE_US.toDouble(), usPerBar * 0.8).toLong()
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        try {
            for (bar in 0 until bars) {
                val from = (bar * usPerBar + (usPerBar - sampleUs) / 2).toLong()
                val to = from + sampleUs
                if (bar > 0) codec.flush()
                extractor.seekTo(from, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                // Past what the file holds (a partly cached stream): this bar stays unknown.
                if (extractor.sampleTime < 0) continue
                var inputDone = false
                var reached = false
                var idle = 0
                while (!reached && idle < 60) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(5_000)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0 || extractor.sampleTime > to + 500_000) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, 5_000)
                    when {
                        outIndex >= 0 -> {
                            idle = 0
                            val t = info.presentationTimeUs
                            if (t >= from && t <= to && info.size > 0) {
                                val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                var acc = 0.0
                                var n = 0
                                if (pcmFloat) {
                                    val floats = buffer.asFloatBuffer()
                                    while (floats.remaining() >= channels) {
                                        val v = floats.get()
                                        acc += v * v
                                        n++
                                        floats.position(floats.position() + channels - 1)
                                    }
                                } else {
                                    val shorts = buffer.asShortBuffer()
                                    while (shorts.remaining() >= channels) {
                                        val v = shorts.get() / 32768.0
                                        acc += v * v
                                        n++
                                        shorts.position(shorts.position() + channels - 1)
                                    }
                                }
                                sumSq[bar] += acc
                                counts[bar] += n
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (t > to || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) reached = true
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = codec.outputFormat
                            channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmFloat = newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        }
                        else -> idle++
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        val rms = DoubleArray(bars) { if (counts[it] > 0) sqrt(sumSq[it] / counts[it]) else -1.0 }
        val loudest = rms.maxOrNull()?.takeIf { it > 0 } ?: return null
        // Decibels mapped onto 0..1 over a 36 dB range: quiet passages stay visible, peaks don't clip.
        return FloatArray(bars) { i ->
            val v = rms[i]
            if (v < 0) -1f else ((20 * log10(max(v, 1e-6) / loudest) + 36.0) / 36.0).coerceIn(0.04, 1.0).toFloat()
        }
    }

    private class FullImage(val db: Array<FloatArray>, val sampleRate: Int, val durationSec: Double)

    private fun decodeWhole(context: Context, source: Source, columns: Int): FullImage? {
        val extractor = extractorFor(context, source)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); return null }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: run { extractor.release(); return null }
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
        if (durationUs <= 0) { extractor.release(); return null }
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmFloat = false
        val totalSamples = (durationUs / 1e6 * sampleRate).toLong()
        val hop = max(1L, totalSamples / columns)
        val bins = FFT_SIZE / 2
        val out = Array(columns) { FloatArray(bins) { -200f } }
        val ring = FloatArray(FFT_SIZE)
        var ringPos = 0
        var sampleIndex = 0L
        var column = 0
        val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1)) }
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        fun push(sample: Float) {
            ring[ringPos] = sample
            ringPos = (ringPos + 1) % FFT_SIZE
            sampleIndex++
            if (column < columns && sampleIndex >= FFT_SIZE && sampleIndex >= (column + 1) * hop) {
                for (i in 0 until FFT_SIZE) {
                    re[i] = ring[(ringPos + i) % FFT_SIZE] * window[i]
                    im[i] = 0.0
                }
                fft(re, im)
                val col = out[column]
                for (k in 0 until bins) col[k] = (10 * log10(re[k] * re[k] + im[k] * im[k] + 1e-12)).toFloat()
                column++
            }
        }
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var idle = 0
        try {
            while (!outputDone && column < columns && idle < 300) {
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
                        idle = 0
                        val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (pcmFloat) {
                            val floats = buffer.asFloatBuffer()
                            while (floats.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += floats.get() }
                                push(sum / channels)
                            }
                        } else {
                            val shorts = buffer.asShortBuffer()
                            while (shorts.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += shorts.get() / 32768f }
                                push(sum / channels)
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
                    else -> idle++
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        if (column < columns / 4) return null
        return FullImage(out.copyOf(column).requireNoNulls(), sampleRate, durationUs / 1e6)
    }

    private const val FULL_WIDTH = 1600
    private const val FULL_HEIGHT = 900

    private fun drawFull(image: FullImage, title: String, subtitle: String): Bitmap {
        val left = 110
        val right = 120
        val top = 110
        val bottom = 80
        val w = image.db.size
        val h = FULL_HEIGHT
        val bitmap = Bitmap.createBitmap(left + w + right, top + h + bottom, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF07060F.toInt())
        val bins = FFT_SIZE / 2
        val loudest = image.db.maxOf { it.max() }
        val px = IntArray(w * h)
        for (x in 0 until w) {
            val col = image.db[x]
            for (y in 0 until h) {
                val from = y * bins / h
                val to = max(from + 1, (y + 1) * bins / h)
                var power = 0.0
                for (b in from until to) power += 10.0.pow(col[b] / 10.0)
                val level = (((10 * log10(power / (to - from) + 1e-12)).toFloat() - (loudest - RANGE_DB)) / RANGE_DB).coerceIn(0f, 1f)
                px[(h - 1 - y) * w + x] = palette(level)
            }
        }
        canvas.drawBitmap(px, 0, w, left.toFloat(), top.toFloat(), w, h, false, null)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEDED.toInt(); textSize = 34f }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8B8C8.toInt(); textSize = 22f }
        val grid = Paint().apply { color = 0x33FFFFFF; strokeWidth = 1f }
        canvas.drawText(title, left.toFloat(), 46f, text)
        canvas.drawText(subtitle, left.toFloat(), 84f, small)
        val nyquist = image.sampleRate / 2f
        var khz = 0
        while (khz * 1000 <= nyquist) {
            val y = top + h * (1 - khz * 1000 / nyquist)
            canvas.drawLine(left.toFloat(), y, (left + w).toFloat(), y, grid)
            canvas.drawText("$khz kHz", 14f, y + 8f, small)
            khz += 2
        }
        val step = when {
            image.durationSec > 600 -> 60
            image.durationSec > 240 -> 30
            else -> 15
        }
        var t = 0
        while (t <= image.durationSec) {
            val x = left + (w * t / image.durationSec).toFloat()
            canvas.drawLine(x, top.toFloat(), x, (top + h).toFloat(), grid)
            canvas.drawText("%d:%02d".format(t / 60, t % 60), x - 20f, (top + h + 34).toFloat(), small)
            t += step
        }
        // Colour scale, loudest on top.
        val barX = left + w + 40
        for (y in 0 until h) {
            val level = 1f - y.toFloat() / h
            canvas.drawLine(barX.toFloat(), (top + y).toFloat(), (barX + 24).toFloat(), (top + y).toFloat(), Paint().apply { color = palette(level) })
        }
        for (i in 0..4) {
            val y = top + h * i / 4f
            canvas.drawText("-${(RANGE_DB * i / 4).toInt()} dB", (barX + 30).toFloat(), y + 8f, small)
        }
        canvas.drawText("Drxwnify", (left + w - 110).toFloat(), (top + h + 66).toFloat(), small)
        return bitmap
    }

    private fun saveImage(context: Context, bitmap: Bitmap, name: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Drxwnify")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused")
            context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return uri
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Drxwnify").also { it.mkdirs() }
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
    }

    private fun sanitize(name: String): String =
        name.map { if (it in "/\\:*?\"<>|" || it.code < 0x20) '_' else it }.joinToString("").trim().trimStart('.').ifEmpty { "track" }

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

    private const val RANGE_DB = 100f

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
        private var data = FloatArray(max(16, capacity))
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
