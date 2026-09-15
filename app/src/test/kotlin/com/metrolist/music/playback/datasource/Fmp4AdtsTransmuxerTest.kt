package com.metrolist.music.playback.datasource

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class Fmp4AdtsTransmuxerTest {

    // ── tiny MP4 box builder ────────────────────────────────────────────────────────────────────
    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { payload.forEach { write(it) } }.toByteArray()
        return int(8 + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun short(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun initSegment(): ByteArray {
        // AudioSpecificConfig: AAC-LC (2), 44100 Hz (index 4), stereo (2) → 0x12 0x10
        val esdsPayload = int(0) +
            bytes(0x03, 0x19) + short(1) + bytes(0x00) +
            bytes(0x04, 0x11, 0x40, 0x15) + bytes(0, 0, 0) + int(160000) + int(160000) +
            bytes(0x05, 0x02, 0x12, 0x10)
        val mp4a = box(
            "mp4a",
            ByteArray(6) + short(1) + ByteArray(8) + short(2) + short(16) + ByteArray(4) + int(44100 shl 16),
            box("esds", esdsPayload),
        )
        val stsd = box("stsd", int(0) + int(1), mp4a)
        val trak = box("trak", box("mdia", box("minf", box("stbl", stsd))))
        return box("ftyp", "iso6".toByteArray()) + box("moov", box("mvhd", ByteArray(100)), trak)
    }

    private fun mediaSegment(samples: List<ByteArray>, useDataOffset: Boolean): ByteArray {
        val trunFlags = 0x200 or (if (useDataOffset) 0x1 else 0)
        fun trun(dataOffset: Int) = box(
            "trun",
            int(trunFlags) + int(samples.size) + (if (useDataOffset) int(dataOffset) else ByteArray(0)) +
                samples.map { int(it.size) }.reduce { a, b -> a + b },
        )
        val tfhd = box("tfhd", int(0x20000) + int(1))
        val provisional = box("moof", box("mfhd", int(0) + int(1)), box("traf", tfhd, trun(0)))
        val moof = box("moof", box("mfhd", int(0) + int(1)), box("traf", tfhd, trun(provisional.size + 8)))
        val mdat = box("mdat", *samples.toTypedArray())
        return box("styp", "msdh".toByteArray()).let { styp ->
            // data_offset is relative to the moof start, which follows styp.
            styp + moof + mdat
        }
    }

    @Test
    fun initSegmentYieldsAacLcStereo44k() {
        val config = Fmp4AdtsTransmuxer.parseInit(initSegment())
        assertEquals(Fmp4AdtsTransmuxer.AudioConfig(2, 4, 2), config)
    }

    @Test
    fun samplesBecomeAdtsFramesWithCorrectHeaders() {
        val samples = listOf(ByteArray(20) { 1 }, ByteArray(300) { 2 }, ByteArray(5) { 3 })
        for (useOffset in listOf(true, false)) {
            val segment = mediaSegment(samples, useOffset)
            val head = segment.copyOf(segment.size - samples.sumOf { it.size })
            assertNotNull(Fmp4AdtsTransmuxer.moofEnd(head))
            val out = Fmp4AdtsTransmuxer.transmux(segment, Fmp4AdtsTransmuxer.AudioConfig(2, 4, 2))!!
            assertEquals(samples.sumOf { it.size + 7 }, out.size)
            var o = 0
            for (s in samples) {
                assertEquals(0xFF, out[o].toInt() and 0xFF)
                assertEquals(0xF1, out[o + 1].toInt() and 0xFF)
                val frameLength = ((out[o + 3].toInt() and 0x3) shl 11) or ((out[o + 4].toInt() and 0xFF) shl 3) or
                    ((out[o + 5].toInt() and 0xFF) ushr 5)
                assertEquals(s.size + 7, frameLength)
                // profile LC (1), freq index 4, channel config 2
                assertEquals(0x50, out[o + 2].toInt() and 0xFF)
                assertArrayEquals(s, out.copyOfRange(o + 7, o + 7 + s.size))
                o += frameLength
            }
        }
    }

    /** Runs against real SoundCloud CMAF files when AUDIO_FIXTURES points at them (never committed). */
    @Test
    fun realSoundCloudSegmentsTransmuxToAContinuousAdtsStream() {
        val dir = System.getenv("AUDIO_FIXTURES")?.let(::File)
        assumeTrue(dir != null && File(dir, "sc_init.mp4").exists())
        val config = Fmp4AdtsTransmuxer.parseInit(File(dir, "sc_init.mp4").readBytes())!!
        println("config=$config")
        for (name in listOf("sc_seg0.m4s", "sc_seg1.m4s")) {
            val seg = File(dir, name).readBytes()
            val fragment = Fmp4AdtsTransmuxer.parseFragment(seg)!!
            val out = Fmp4AdtsTransmuxer.transmux(seg, config)!!
            var o = 0
            var frames = 0
            while (o < out.size) {
                assertEquals(0xFFF, ((out[o].toInt() and 0xFF) shl 4) or ((out[o + 1].toInt() and 0xF0) ushr 4))
                val len = ((out[o + 3].toInt() and 0x3) shl 11) or ((out[o + 4].toInt() and 0xFF) shl 3) or
                    ((out[o + 5].toInt() and 0xFF) ushr 5)
                o += len
                frames++
            }
            assertEquals(out.size, o)
            assertEquals(fragment.sampleSizes.size, frames)
            println("$name: ${fragment.sampleSizes.size} frames, ${seg.size} → ${out.size} bytes, head moofEnd=${Fmp4AdtsTransmuxer.moofEnd(seg.copyOf(4096))}")
            File(dir, "$name.aac").writeBytes(out)
        }
    }
}
