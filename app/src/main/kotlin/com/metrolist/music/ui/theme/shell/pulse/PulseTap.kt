/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme.shell.pulse

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The listening end of the Pulse theme: a link in the player's own audio chain that lets the sound
 * through untouched (same format, same bytes, no delay) and, while Pulse is on, measures it —
 * loudness and the energy of the lows, mids and highs, block by block. No microphone, no system
 * Visualizer.
 *
 * Safety first: any error of its own switches the measuring off for good (until the app restarts);
 * the sound keeps passing through. Formats it doesn't read (not 16-bit or float PCM) pass by it.
 */
@androidx.media3.common.util.UnstableApi
class PulseTap : BaseAudioProcessor() {
    private var sampleRate = 44_100
    private var channels = 2
    private var float = false

    // One-pole filters (cheap; no FFT): a low-pass at ~150 Hz for the lows, one at ~4 kHz to split off the highs.
    private var lowCoef = 0f
    private var highCoef = 0f
    private var lowState = 0f
    private var highState = 0f

    // The block being summed (~11 ms).
    private var blockFrames = 512
    private var frames = 0
    private var sumAll = 0f
    private var sumLow = 0f
    private var sumMid = 0f
    private var sumHigh = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8_000)
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        lowCoef = 1f - exp(-2f * Math.PI.toFloat() * 150f / sampleRate)
        highCoef = 1f - exp(-2f * Math.PI.toFloat() * 4_000f / sampleRate)
        blockFrames = (sampleRate / 90).coerceAtLeast(128)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        if (Pulse.listening && !failed) {
            try {
                measure(inputBuffer.duplicate().order(ByteOrder.nativeOrder()))
            } catch (t: Throwable) {
                failed = true
            }
        }
        // Through untouched.
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    private fun measure(buffer: ByteBuffer) {
        val bytesPerSample = if (float) 4 else 2
        val frameBytes = bytesPerSample * channels
        while (buffer.remaining() >= frameBytes) {
            var mono = 0f
            for (ch in 0 until channels) {
                mono += if (float) buffer.float else buffer.short / 32768f
            }
            mono /= channels
            lowState += lowCoef * (mono - lowState)
            highState += highCoef * (mono - highState)
            val low = lowState
            val high = mono - highState
            val mid = highState - lowState
            sumAll += mono * mono
            sumLow += low * low
            sumMid += mid * mid
            sumHigh += high * high
            if (++frames >= blockFrames) {
                Pulse.push(
                    durationMs = frames * 1000f / sampleRate,
                    level = sqrt(sumAll / frames),
                    low = sqrt(sumLow / frames),
                    mid = sqrt(sumMid / frames),
                    high = sqrt(sumHigh / frames),
                )
                frames = 0
                sumAll = 0f
                sumLow = 0f
                sumMid = 0f
                sumHigh = 0f
            }
        }
    }

    override fun onFlush() {
        // A seek or a new stream: the output was emptied too, the clock starts over at "now".
        frames = 0
        sumAll = 0f
        sumLow = 0f
        sumMid = 0f
        sumHigh = 0f
        lowState = 0f
        highState = 0f
        Pulse.restartClock()
    }

    override fun onReset() {
        onFlush()
    }

    companion object {
        @Volatile
        private var failed = false
    }
}

/**
 * What Pulse heard, on a timeline of when it is heard. The tap measures audio ahead of the speaker
 * (by the output buffer); each block gets the time it will play: blocks follow each other without
 * gaps, and after a pause or a stall the same lead as before is kept. The UI asks for "now".
 */
object Pulse {
    /** Measuring at all: Pulse is the theme and the app is on screen. */
    @Volatile
    var listening: Boolean = false

    private const val SIZE = 512
    private val times = LongArray(SIZE)
    private val values = FloatArray(SIZE * 4)

    @Volatile
    private var head = -1

    private var clockMs = 0.0
    private var lastWriteMs = 0L
    private var leadMs = 0.0

    @Volatile
    private var restart = true

    fun restartClock() {
        restart = true
    }

    internal fun push(durationMs: Float, level: Float, low: Float, mid: Float, high: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        if (restart) {
            clockMs = now.toDouble()
            leadMs = 0.0
            restart = false
        } else if (now - lastWriteMs > 120) {
            // A pause or a stall: what was in the buffer is still to play; keep the lead it had.
            clockMs = now + leadMs
        }
        if (clockMs < now) clockMs = now.toDouble()
        val i = (head + 1) % SIZE
        times[i] = clockMs.toLong()
        values[i * 4] = level
        values[i * 4 + 1] = low
        values[i * 4 + 2] = mid
        values[i * 4 + 3] = high
        head = i
        clockMs += durationMs
        leadMs = clockMs - now
        lastWriteMs = now
    }

    /**
     * The block playing at [nowMs] into [out] (level, low, mid, high); false when nothing fresh is
     * known (paused, casting, another theme).
     */
    fun sample(nowMs: Long, out: FloatArray): Boolean {
        val h = head
        if (h < 0) return false
        var i = h
        repeat(SIZE) {
            val t = times[i]
            if (t <= nowMs) {
                if (nowMs - t > 150) return false
                out[0] = values[i * 4]
                out[1] = values[i * 4 + 1]
                out[2] = values[i * 4 + 2]
                out[3] = values[i * 4 + 3]
                return true
            }
            i = (i - 1 + SIZE) % SIZE
        }
        return false
    }
}
