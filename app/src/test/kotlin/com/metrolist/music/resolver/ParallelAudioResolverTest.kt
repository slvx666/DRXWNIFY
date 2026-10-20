package com.metrolist.music.resolver

import com.metrolist.music.playback.datasource.HlsPlaylist
import com.metrolist.music.resolver.providers.VkAudioProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ParallelAudioResolverTest {

    private val query = AudioQuery(
        catalogId = "4uLU6hMCjMI75M1A2tKUQC",
        title = "Mahjong",
        artists = listOf("ASAVA"),
        album = "Mahjong",
        durationMs = 201_000,
        isrc = null,
    )

    private class FakeProvider(
        override val id: AudioProviderId,
        private val delayMs: Long,
        private val found: Boolean,
        private val throws: Boolean = false,
        override val searchTimeoutMs: Long = 5_000,
        private val confidence: Double = 0.9,
    ) : AudioProvider {
        val cancelled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)

        override suspend fun search(query: AudioQuery): ProviderMatch? {
            try {
                delay(delayMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled.set(true)
                throw e
            }
            finished.set(true)
            if (throws) error("service down")
            return if (found) ProviderMatch(id, "$id-track", query.title, query.primaryArtist, query.durationMs, confidence) else null
        }

        override suspend fun stream(query: AudioQuery, match: ProviderMatch): AudioStream? = null
    }

    /** Speed mode: the provider order decides between comparable matches (the tests below). */
    private fun resolver(soft: Long = 400, hard: Long = 1_500) =
        ParallelAudioResolver(soft, hard, preferQuality = { false })

    private fun qualityResolver() = ParallelAudioResolver(400, 1_500, preferQuality = { true })

    @Test
    fun qualityModePicksTheBetterSoundingExactMatch() = runBlocking {
        // Both found the very same track: VK's 320 kbps MP3 beats YouTube's ~160 Opus though
        // YouTube is ranked first and answered first.
        val youtube = FakeProvider(AudioProviderId.YOUTUBE, 20, found = true, confidence = 1.0)
        val vk = FakeProvider(AudioProviderId.VK, 150, found = true, confidence = 1.0)
        val order = listOf(AudioProviderId.YOUTUBE, AudioProviderId.VK)
        val outcome = qualityResolver().resolve(query, listOf(youtube, vk), order)
        assertEquals(AudioProviderId.VK, outcome.winner?.provider)
    }

    @Test
    fun aSourceThatLatelySentNoAudioIsNotWaitedFor() = runBlocking {
        // VK could sound better, but it just failed to deliver: YouTube's exact match goes at once.
        val youtube = FakeProvider(AudioProviderId.YOUTUBE, 20, found = true, confidence = 1.0)
        val vk = FakeProvider(AudioProviderId.VK, 1_000, found = true, confidence = 1.0)
        val order = listOf(AudioProviderId.YOUTUBE, AudioProviderId.VK)
        val outcome = ParallelAudioResolver(400, 1_500, preferQuality = { true }, isSlow = { it == AudioProviderId.VK })
            .resolve(query, listOf(youtube, vk), order)
        assertEquals(AudioProviderId.YOUTUBE, outcome.winner?.provider)
        assertTrue("decided without waiting: ${outcome.elapsedMs}", outcome.elapsedMs < 500)
    }

    @Test
    fun qualityModeDoesNotWaitForSourcesThatCannotSoundBetter() = runBlocking {
        // SoundCloud (160) can't beat YouTube's exact 160 kbps match ranked above it: no 4.5 s wait.
        val youtube = FakeProvider(AudioProviderId.YOUTUBE, 20, found = true, confidence = 1.0)
        val soundcloud = FakeProvider(AudioProviderId.SOUNDCLOUD, 1_000, found = true, confidence = 1.0)
        val order = listOf(AudioProviderId.YOUTUBE, AudioProviderId.VK, AudioProviderId.SOUNDCLOUD)
        val outcome = qualityResolver().resolve(query, listOf(youtube, soundcloud), order)
        assertEquals(AudioProviderId.YOUTUBE, outcome.winner?.provider)
        assertTrue("decided without waiting: ${outcome.elapsedMs}", outcome.elapsedMs < 500)
    }

    @Test
    fun doubtfulLowerRankedMatchDoesNotWinByTheClock() = runBlocking {
        // A doubtful SoundCloud re-upload answers first; YouTube, ranked higher, needs longer than
        // the soft deadline but has the exact track.
        val youtube = FakeProvider(AudioProviderId.YOUTUBE, 600, found = true, confidence = 1.0)
        val soundcloud = FakeProvider(AudioProviderId.SOUNDCLOUD, 30, found = true, confidence = 0.7)
        val order = listOf(AudioProviderId.YOUTUBE, AudioProviderId.SOUNDCLOUD)
        val speed = resolver(soft = 300, hard = 2_000).resolve(query, listOf(youtube, soundcloud), order)
        assertEquals(AudioProviderId.YOUTUBE, speed.winner?.provider)
        val quality = ParallelAudioResolver(300, 2_000, preferQuality = { true })
            .resolve(query, listOf(youtube, soundcloud), order)
        assertEquals(AudioProviderId.YOUTUBE, quality.winner?.provider)
    }

    @Test
    fun qualityModeNeverTradesAccuracyForBitrate() = runBlocking {
        // A doubtful match stays behind an exact one whatever its bitrate.
        val youtube = FakeProvider(AudioProviderId.YOUTUBE, 20, found = true, confidence = 1.0)
        val vk = FakeProvider(AudioProviderId.VK, 50, found = true, confidence = 0.8)
        val outcome = qualityResolver().resolve(query, listOf(youtube, vk))
        assertEquals(AudioProviderId.YOUTUBE, outcome.winner?.provider)
    }

    @Test
    fun exactMatchBeatsDoubtfulMatchFromHigherRankedProvider() = runBlocking {
        // Bandcamp is ranked first but only has a similarly named act; YouTube has the exact track.
        val bandcamp = FakeProvider(AudioProviderId.BANDCAMP, 20, found = true, confidence = 0.77)
        val youtube = FakeProvider(AudioProviderId.YOUTUBE, 120, found = true, confidence = 1.0)
        val order = listOf(AudioProviderId.BANDCAMP, AudioProviderId.YOUTUBE)
        val outcome = resolver().resolve(query, listOf(bandcamp, youtube), order)
        assertEquals(AudioProviderId.YOUTUBE, outcome.winner?.provider)
        assertEquals(AudioProviderId.YOUTUBE, outcome.matches.first().provider)
    }

    @Test
    fun comparablyConfidentMatchesFollowProviderOrder() = runBlocking {
        val bandcamp = FakeProvider(AudioProviderId.BANDCAMP, 20, found = true, confidence = 0.92)
        val youtube = FakeProvider(AudioProviderId.YOUTUBE, 60, found = true, confidence = 1.0)
        val order = listOf(AudioProviderId.BANDCAMP, AudioProviderId.YOUTUBE)
        val outcome = resolver().resolve(query, listOf(bandcamp, youtube), order)
        assertEquals(AudioProviderId.BANDCAMP, outcome.winner?.provider)
    }

    @Test
    fun fastHigherPriorityProviderWins() = runBlocking {
        val yt = FakeProvider(AudioProviderId.YOUTUBE, 50, found = true)
        val sc = FakeProvider(AudioProviderId.SOUNDCLOUD, 10, found = true)
        val outcome = resolver().resolve(query, listOf(sc, yt))
        assertEquals(AudioProviderId.YOUTUBE, outcome.winner?.provider)
        assertTrue(outcome.elapsedMs < 400)
    }

    @Test
    fun lowerPriorityMatchIsTakenImmediatelyWhenHigherOnesMissed() = runBlocking {
        val yt = FakeProvider(AudioProviderId.YOUTUBE, 20, found = false)
        val qobuz = FakeProvider(AudioProviderId.QOBUZ, 30, found = false, throws = true)
        val vk = FakeProvider(AudioProviderId.VK, 60, found = true)
        val sc = FakeProvider(AudioProviderId.SOUNDCLOUD, 1_000, found = true)
        val outcome = resolver().resolve(query, listOf(yt, qobuz, vk, sc))
        assertEquals(AudioProviderId.VK, outcome.winner?.provider)
        assertTrue("should not wait for slower providers: ${outcome.elapsedMs}", outcome.elapsedMs < 400)
        assertEquals(setOf(AudioProviderId.YOUTUBE), outcome.definitiveMisses)
        assertTrue(sc.cancelled.get())
    }

    @Test
    fun slowHigherPriorityProviderIsNotWaitedForPastSoftDeadline() = runBlocking {
        // YouTube blocked in this network (hangs), SoundCloud has the track quickly.
        val yt = FakeProvider(AudioProviderId.YOUTUBE, 10_000, found = true)
        val sc = FakeProvider(AudioProviderId.SOUNDCLOUD, 50, found = true)
        val outcome = resolver(soft = 300).resolve(query, listOf(yt, sc))
        assertEquals(AudioProviderId.SOUNDCLOUD, outcome.winner?.provider)
        assertTrue("decided at the soft deadline, got ${outcome.elapsedMs}", outcome.elapsedMs in 250..900)
        assertTrue(yt.cancelled.get())
    }

    @Test
    fun waitsPastSoftDeadlineWhenNothingMatchedYet() = runBlocking {
        val yt = FakeProvider(AudioProviderId.YOUTUBE, 50, found = false)
        val sc = FakeProvider(AudioProviderId.SOUNDCLOUD, 700, found = true)
        val outcome = resolver(soft = 300, hard = 2_000).resolve(query, listOf(yt, sc))
        assertEquals(AudioProviderId.SOUNDCLOUD, outcome.winner?.provider)
    }

    @Test
    fun hardDeadlineEndsTheRaceWithNoMatch() = runBlocking {
        val yt = FakeProvider(AudioProviderId.YOUTUBE, 5_000, found = true)
        val outcome = resolver(soft = 100, hard = 400).resolve(query, listOf(yt))
        assertNull(outcome.winner)
        assertTrue(outcome.elapsedMs < 1_500)
    }

    @Test
    fun providerTimeoutCountsAsFailureNotMiss() = runBlocking {
        val yt = FakeProvider(AudioProviderId.YOUTUBE, 1_000, found = true, searchTimeoutMs = 100)
        val sc = FakeProvider(AudioProviderId.SOUNDCLOUD, 200, found = true)
        val outcome = resolver(soft = 1_000, hard = 2_000).resolve(query, listOf(yt, sc))
        assertEquals(AudioProviderId.SOUNDCLOUD, outcome.winner?.provider)
        assertTrue(AudioProviderId.YOUTUBE !in outcome.definitiveMisses)
    }

    @Test
    fun allMatchesAreReportedAsAlternatesInPriorityOrder() = runBlocking {
        val yt = FakeProvider(AudioProviderId.YOUTUBE, 100, found = true)
        val qobuz = FakeProvider(AudioProviderId.QOBUZ, 20, found = true)
        val sc = FakeProvider(AudioProviderId.SOUNDCLOUD, 10, found = true)
        val outcome = resolver().resolve(query, listOf(sc, qobuz, yt))
        assertEquals(AudioProviderId.YOUTUBE, outcome.winner?.provider)
        assertEquals(
            listOf(AudioProviderId.YOUTUBE, AudioProviderId.QOBUZ, AudioProviderId.SOUNDCLOUD),
            outcome.matches.map { it.provider },
        )
    }

    @Test
    fun userOrderDecidesTheWinner() = runBlocking {
        val yt = FakeProvider(AudioProviderId.YOUTUBE, 10, found = true)
        val sc = FakeProvider(AudioProviderId.SOUNDCLOUD, 80, found = true)
        val outcome = resolver().resolve(query, listOf(yt, sc), AudioProviderId.DEFAULT_ORDER)
        assertEquals(AudioProviderId.SOUNDCLOUD, outcome.winner?.provider)
        assertEquals(2, outcome.report.size)
    }

    @Test
    fun storedOrderIsParsedAndCompleted() {
        assertEquals(
            listOf(
                AudioProviderId.VK, AudioProviderId.YOUTUBE, AudioProviderId.SOUNDCLOUD,
                AudioProviderId.LOSSLESS, AudioProviderId.BANDCAMP, AudioProviderId.AUDIUS, AudioProviderId.SOULSEEK,
            ),
            AudioProviderId.parseOrder("VK,YOUTUBE,BOGUS"),
        )
        assertEquals(AudioProviderId.DEFAULT_ORDER, AudioProviderId.parseOrder(null))
    }

    // ── Fallback ids ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun fallbackIdsRoundTripAndLegacyQobuzIsRecognised() {
        assertEquals("mfb:ym_123", FallbackIds.of("ym_123"))
        assertEquals("ym_123", FallbackIds.catalogIdOf("mfb:ym_123"))
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", FallbackIds.catalogIdOf("qbzfb:4uLU6hMCjMI75M1A2tKUQC"))
        assertTrue(FallbackIds.isFallbackId("qbzfb:x"))
        assertNull(FallbackIds.catalogIdOf("dQw4w9WgXcQ"))
    }

    // ── VK / HLS helpers ──────────────────────────────────────────────────────────────────────────

    @Test
    fun vkHlsUrlIsConvertedToMp3() {
        val hls = "https://cs1-66v4.vkuseraudio.net/s/v1/acmp/0f3a9b1c2d/ab12cd34ef56/index.m3u8?extra=abc"
        assertEquals(
            "https://cs1-66v4.vkuseraudio.net/s/v1/acmp/ab12cd34ef56.mp3?extra=abc",
            VkAudioProvider.toMp3Url(hls),
        )
        val audios = "https://psv4.vkuseraudio.net/s/v1/ac/deadbeef/audios/cafe01/index.m3u8?extra=1"
        assertEquals("https://psv4.vkuseraudio.net/s/v1/ac/audios/cafe01.mp3?extra=1", VkAudioProvider.toMp3Url(audios))
        assertEquals("https://x/y.mp3", VkAudioProvider.toMp3Url("https://x/y.mp3"))
    }

    @Test
    fun hlsMediaPlaylistResolvesInitAndSegments() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:10
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:10.0,
            seg/0.m4s?sig=1
            #EXTINF:9.5,
            https://cdn.example/other/1.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        assertEquals(
            listOf(
                "https://playback.media-streaming.soundcloud.cloud/abc/init.mp4",
                "https://playback.media-streaming.soundcloud.cloud/abc/seg/0.m4s?sig=1",
                "https://cdn.example/other/1.m4s",
            ),
            HlsPlaylist.parseMediaPlaylist("https://playback.media-streaming.soundcloud.cloud/abc/playlist.m3u8", text),
        )
    }

    @Test
    fun segmentLengthsAreParsed() {
        val text = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:10.005,\na.ts\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\nb.ts\n"
        val playlist = HlsPlaylist.parse("https://h/p.m3u8", text, allowAes128 = true)!!
        assertEquals(listOf(10.005, 4.0), playlist.durations)
    }

    @Test
    fun constantBitrateMp3IsRecognisedAndSilenceFitsIt() {
        // 20 frames of MPEG-1 Layer III, 320 kbps, 44.1 kHz, joint stereo (sizes 1044/1045 with padding).
        val frames = java.io.ByteArrayOutputStream()
        repeat(20) { i ->
            val padding = i % 2
            val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), (0xE0 or (padding shl 1)).toByte(), 0x44)
            frames.write(header)
            frames.write(ByteArray(1044 + padding - 4))
        }
        val cbr = com.metrolist.music.playback.datasource.MeasuredAudio.constantMp3(frames.toByteArray())!!
        assertEquals(320_000, cbr.bitrate)
        assertEquals(44_100, cbr.sampleRate)
        val silent = cbr.silentFrame()
        assertEquals(1044, silent.size)
        assertEquals(0xFF.toByte(), silent[0])
        // Protection bit set (no CRC) and padding bit cleared.
        assertEquals(0xFB.toByte(), silent[1])
        assertEquals(0xE0.toByte(), silent[2])
    }

    @Test
    fun encryptedOrMasterPlaylistsAreRejected() {
        val encrypted = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:10,\na.ts\n"
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=128000\nlow.m3u8\n"
        assertNull(HlsPlaylist.parseMediaPlaylist("https://h/p.m3u8", encrypted))
        assertNull(HlsPlaylist.parseMediaPlaylist("https://h/p.m3u8", master))
    }
}
