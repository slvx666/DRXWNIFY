/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Left/right balance: -1 = only the left ear, 0 = both as recorded, 1 = only the right ear. The side
 * the balance moves away from is turned down; the other stays as it is (nothing gets louder).
 */
object StereoBalance {
    @Volatile
    var value: Float = 0f
        set(v) {
            field = v.coerceIn(-1f, 1f)
        }
}

/** Applies [StereoBalance] to stereo 16-bit and float PCM; any other format passes through untouched. */
@UnstableApi
class BalanceAudioProcessor : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported = inputAudioFormat.channelCount == 2 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        // Not active for other formats: the sink then skips this processor.
        return if (supported) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val balance = StereoBalance.value
        val left = if (balance > 0f) 1f - balance else 1f
        val right = if (balance < 0f) 1f + balance else 1f
        if (balance == 0f) {
            output.put(inputBuffer)
        } else if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 8) {
                output.putFloat(inputBuffer.float * left)
                output.putFloat(inputBuffer.float * right)
            }
        } else {
            while (inputBuffer.remaining() >= 4) {
                output.putShort((inputBuffer.short * left).toInt().toShort())
                output.putShort((inputBuffer.short * right).toInt().toShort())
            }
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }
}
