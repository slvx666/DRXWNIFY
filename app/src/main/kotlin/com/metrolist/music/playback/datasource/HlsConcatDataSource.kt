/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.datasource

import android.net.Uri
import android.util.LruCache
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plays an (unencrypted) HLS *media* playlist as ONE continuous progressive stream. Drxwnify's player and
 * download pipeline are progressive-only (they swap the uri inside a ResolvingDataSource, which cannot
 * switch the media source type), and SoundCloud serves most tracks as HLS only.
 *
 * Two modes:
 *  - **ADTS** (fMP4/CMAF AAC — SoundCloud's usual format): every segment is transmuxed to ADTS AAC
 *    frames ([Fmp4AdtsTransmuxer]). The exact output length is computed up front from the fragment
 *    headers (small parallel range requests), so the player gets a duration, seeking works (constant
 *    bitrate) and the cache/exporter see an ordinary .aac file.
 *  - **Concat** (MPEG audio / other segments): parts are concatenated; sizes come from parallel HEAD
 *    requests.
 *
 * Addressed through the `meldhls://` scheme created by [wrap]. Any other uri is delegated to [upstream].
 */
@UnstableApi
class HlsConcatDataSource(
    private val upstream: DataSource,
    private val client: OkHttpClient,
    /** Playback: "whole" tracks start after their first segment instead of after all of them. */
    private val streamWhole: Boolean = false,
) : BaseDataSource(/* isNetwork = */ true) {

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val client: OkHttpClient,
        private val streamWhole: Boolean = false,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            HlsConcatDataSource(upstreamFactory.createDataSource(), client, streamWhole)
    }

    /** Output layout: [urls] in order, each producing [sizes] bytes (null = unknown, stream from start). */
    private class Layout(
        val urls: List<String>,
        val sizes: LongArray?,
        val adts: Fmp4AdtsTransmuxer.AudioConfig?,
    ) {
        val totalLength: Long? get() = sizes?.sum()
    }

    private var delegating = false
    private var fileSource: androidx.media3.datasource.FileDataSource? = null
    private var uri: Uri? = null
    private var layout: Layout? = null
    private var partIndex = 0
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    // Concat mode: live HTTP stream of the current part.
    private var currentStream: InputStream? = null
    private var currentResponse: Response? = null

    // ADTS mode: the current part, transmuxed in memory.
    private var buffer: ByteArray? = null
    private var bufferPos = 0

    // Streamed whole track: what is read, and where.
    private var streaming: WholeStream? = null
    private var streamPos = 0L

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme == "file") {
            // Files fetched by the Soulseek provider (download pipeline has no DefaultDataSource).
            delegating = true
            fileSource = androidx.media3.datasource.FileDataSource()
            return fileSource!!.open(dataSpec)
        }
        if (dataSpec.uri.scheme != SCHEME) {
            delegating = true
            return upstream.open(dataSpec)
        }
        delegating = false
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val playlistUrl = unwrap(dataSpec.uri)
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        if (dataSpec.uri.getQueryParameter(WHOLE_PARAM) == "1") {
            return if (streamWhole) openStreamed(dataSpec, playlistUrl) else openWhole(dataSpec, playlistUrl)
        }
        val l = layoutCache.get(playlistUrl) ?: loadLayout(playlistUrl).also { if (it.sizes != null) layoutCache.put(playlistUrl, it) }
        // AAC whose output length could not be worked out (a probe failed on a weak network): without
        // a length the player can neither seek nor resume after a dropped connection — it starts the
        // track over. Built whole instead (a few MB), which gives the exact length.
        if (l.adts != null && l.sizes == null) {
            val bytes = wholeCache.get(playlistUrl) ?: assembleAdts(l, l.adts).also { wholeCache.put(playlistUrl, it) }
            return serveBytes(dataSpec, bytes)
        }
        layout = l

        val position = dataSpec.position
        val sizes = l.sizes
        val total = l.totalLength
        if (sizes != null && total != null) {
            if (position > total) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            var offset = position
            var index = 0
            while (index < l.urls.size && offset >= sizes[index]) {
                offset -= sizes[index]
                index++
            }
            partIndex = index
            if (index < l.urls.size) openPart(index, offset)
            val available = total - position
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, available) else available
        } else {
            // Sizes unknown: stream from the start and discard up to the requested position.
            partIndex = 0
            openPart(0, 0)
            skipFully(position)
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return if (sizes != null) bytesRemaining else dataSpec.length
    }

    /**
     * "Whole" mode (VK): the track is fetched completely, decrypted and demuxed before the first
     * byte is served. Segments are AES-128 encrypted (so their final size is unknown until they are
     * decrypted) and MPEG-TS wrapped (which a progressive player mis-sniffs as MP3); turning them into
     * one plain audio file up front gives the player and the exporter an exact length, a duration
     * and working seeking. A track is ~10 MB at 320 kbps, fetched in parallel.
     */
    private fun openWhole(dataSpec: DataSpec, playlistUrl: String): Long {
        val bytes = wholeCache.get(playlistUrl) ?: run {
            val started = System.currentTimeMillis()
            assembleWhole(playlistUrl).also {
                wholeCache.put(playlistUrl, it)
                com.metrolist.music.resolver.AudioDiagnostics.info(
                    "hls whole: ${it.size / 1024} KB in ${System.currentTimeMillis() - started} ms (${playlistUrl.toHttpUrlOrNull()?.encodedPath})",
                )
            }
        }
        return serveBytes(dataSpec, bytes)
    }

    /**
     * Playback of a "whole" track (VK) without waiting for all of it. The segments are fetched in
     * order in the background; the player starts as soon as the first one is decrypted and demuxed,
     * the way Lane starts within a second, instead of after the complete file (2–10 s).
     *
     * The player needs the length up front (duration, seeking), and the real one is only known at
     * the end. For a constant-bitrate MP3 it follows from the playlist's segment lengths: that
     * estimate (a hair longer) is declared, the real audio is served, and whatever is left up to it
     * is filled with silent MP3 frames. Anything else (variable bitrate, no lengths) is served
     * whole, as before.
     */
    private fun openStreamed(dataSpec: DataSpec, playlistUrl: String): Long {
        streamedCache.get(playlistUrl)?.let { return serveBytes(dataSpec, it) }
        val stream = synchronized(streams) {
            streams[playlistUrl]?.takeIf { it.failure == null } ?: startStream(playlistUrl).also { streams[playlistUrl] = it }
        }
        val declared = try {
            stream.awaitDeclaredLength()
        } catch (e: IOException) {
            synchronized(streams) { streams.remove(playlistUrl) }
            throw e
        }
        if (declared <= 0L) {
            // Not streamable: wait for it whole (the stream keeps fetching meanwhile).
            return serveBytes(dataSpec, stream.awaitExact())
        }
        val position = dataSpec.position
        if (position > declared) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        streaming = stream
        streamPos = position
        val available = declared - position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, available) else available
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    private fun startStream(playlistUrl: String): WholeStream {
        CdnHosts.hostOf(playlistUrl)?.takeIf(CdnHosts::isDown)?.let { host ->
            throw DataSourceException(HostUnreachableException(host, null), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        val started = System.currentTimeMillis()
        val playlistBytes = retrying { fetchBytes(playlistUrl, null) }
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        val playlist = HlsPlaylist.parse(playlistUrl, String(playlistBytes, Charsets.UTF_8), allowAes128 = true)
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
        com.metrolist.music.resolver.AudioDiagnostics.info(
            "hls: playlist (${playlist.segments.size} segments) after ${System.currentTimeMillis() - started} ms",
        )
        val keyBytes = playlist.keys.filterNotNull().map { it.url }.distinct().associateWith { url ->
            retrying { fetchBytes(url, null) } ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        val seconds = playlist.durations
            .takeIf { d -> d.size == playlist.segments.size && d.all { it > 0.0 } }
            ?.sum() ?: 0.0
        // An init section means fMP4, which isn't joined segment by segment here.
        val stream = WholeStream(playlistUrl, playlist.segments.size, if (playlist.initUrl == null) seconds else 0.0, started)
        playlist.segments.forEachIndexed { i, url ->
            segmentPool.execute {
                try {
                    val raw = retrying { fetchBytes(url, null) }
                        ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
                    val key = playlist.keys.getOrNull(i)
                    val plain = if (key == null) {
                        raw
                    } else {
                        decryptAes128(raw, keyBytes.getValue(key.url), key.iv ?: sequenceIv(playlist.mediaSequence + i))
                    }
                    val audio = if (plain.isNotEmpty() && plain[0] == 0x47.toByte()) {
                        TsAudioDemuxer.extract(plain)
                            ?: throw DataSourceException(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
                    } else {
                        plain
                    }
                    stream.segmentReady(i, audio)
                } catch (t: Throwable) {
                    stream.fail(t)
                }
            }
        }
        return stream
    }

    private fun serveBytes(dataSpec: DataSpec, bytes: ByteArray): Long {
        val playlistUrl = uri?.let { unwrap(it) }.orEmpty()
        val position = dataSpec.position
        if (position > bytes.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        layout = Layout(listOf(playlistUrl), longArrayOf(bytes.size.toLong()), null)
        partIndex = 0
        buffer = bytes
        bufferPos = position.toInt()
        val available = bytes.size - position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, available) else available
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    /** Every fMP4 segment fetched (in parallel, each retried) and transmuxed to ADTS, joined. */
    private fun assembleAdts(l: Layout, config: Fmp4AdtsTransmuxer.AudioConfig): ByteArray {
        val parts = arrayOfNulls<ByteArray>(l.urls.size)
        val latch = CountDownLatch(l.urls.size)
        val executor = segmentPool
        l.urls.forEachIndexed { i, url ->
            executor.execute {
                try {
                    parts[i] = segmentCache.get(url) ?: retrying { Fmp4AdtsTransmuxer.transmux(fetchBytes(url, null), config) }
                } finally {
                    latch.countDown()
                }
            }
        }
        if (!latch.await(WHOLE_TIMEOUT_S, TimeUnit.SECONDS)) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        }
        if (parts.any { it == null }) throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        return java.io.ByteArrayOutputStream(parts.sumOf { it!!.size }).apply { parts.forEach { write(it!!) } }.toByteArray()
    }

    private fun <T : Any> retrying(block: () -> T?): T? {
        repeat(PROBE_ATTEMPTS) { attempt ->
            val result = runCatching(block)
            result.getOrNull()?.let { return it }
            // A server out of reach stays out of reach: asking it twice more only adds silence.
            result.exceptionOrNull()?.takeIf { it is HostUnreachableException || it.cause is HostUnreachableException }?.let { throw it }
            if (attempt < PROBE_ATTEMPTS - 1) Thread.sleep(400L * (attempt + 1))
        }
        return null
    }

    private fun assembleWhole(playlistUrl: String): ByteArray {
        val text = String(fetchBytes(playlistUrl, null), Charsets.UTF_8)
        val playlist = HlsPlaylist.parse(playlistUrl, text, allowAes128 = true)
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
        val keyBytes = playlist.keys.filterNotNull().map { it.url }.distinct()
            .associateWith { url -> fetchBytes(url, null) }

        val urls = listOfNotNull(playlist.initUrl) + playlist.segments
        val offset = if (playlist.initUrl != null) 1 else 0
        val parts = arrayOfNulls<ByteArray>(urls.size)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(urls.size)
        // A few segments at a time, each retried: all ~70 segments of a 12-minute VK track at once
        // over a VPN made one of them time out, and a single lost segment failed the whole track
        // after ~45 s of silence (Drudkh – "Solitude" then fell back to Bandcamp).
        val executor = segmentPool
        urls.forEachIndexed { i, url ->
            executor.execute {
                try {
                    val raw = retrying { fetchBytes(url, null) }
                        ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
                    val segmentIndex = i - offset
                    val key = if (segmentIndex >= 0) playlist.keys.getOrNull(segmentIndex) else null
                    parts[i] = if (key == null) {
                        raw
                    } else {
                        val iv = key.iv ?: sequenceIv(playlist.mediaSequence + segmentIndex)
                        decryptAes128(raw, keyBytes.getValue(key.url), iv)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    latch.countDown()
                }
            }
        }
        if (!latch.await(WHOLE_TIMEOUT_S, TimeUnit.SECONDS)) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        }
        failure.get()?.let { t ->
            throw t as? DataSourceException
                ?: DataSourceException(t, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        val joined = java.io.ByteArrayOutputStream(parts.sumOf { it?.size ?: 0 }).apply {
            parts.forEach { part -> part?.let(::write) }
        }.toByteArray()
        // MPEG-TS starts with the 0x47 sync byte; hand the player the audio inside it, not the wrapper.
        val audio = if (joined.isNotEmpty() && joined[0] == 0x47.toByte()) {
            TsAudioDemuxer.extract(joined)
                ?: throw DataSourceException(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        } else {
            joined
        }
        // The real bitrate, from the audio itself (VK doesn't say it).
        MeasuredAudio.analyze(audio)?.let { MeasuredAudio.record(playlistUrl, it) }
        timber.log.Timber.tag("DrxwHls").i(
            "whole: %d segments (%d encrypted), %d bytes joined → %d bytes audio, starts %02x %02x",
            playlist.segments.size, playlist.keys.count { it != null }, joined.size, audio.size,
            audio.getOrNull(0) ?: 0, audio.getOrNull(1) ?: 0,
        )
        return audio
    }

    /** HLS default IV: the segment's media sequence number as a 128-bit big-endian integer. */
    private fun sequenceIv(sequence: Long): ByteArray =
        ByteArray(16).also { iv -> for (i in 0 until 8) iv[15 - i] = (sequence ushr (8 * i)).toByte() }

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(iv),
        )
        return cipher.doFinal(data)
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (delegating) return fileSource?.read(target, offset, length) ?: upstream.read(target, offset, length)
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        streaming?.let { stream ->
            val n = stream.read(streamPos, target, offset, minOf(length.toLong(), bytesRemaining).toInt())
            if (n <= 0) return C.RESULT_END_OF_INPUT
            streamPos += n
            bytesRemaining -= n
            bytesTransferred(n)
            return n
        }
        val l = layout ?: return C.RESULT_END_OF_INPUT
        while (true) {
            val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
            val read = readCurrent(target, offset, toRead)
            if (read > 0) {
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
                bytesTransferred(read)
                return read
            }
            closeCurrentPart()
            partIndex++
            if (partIndex >= l.urls.size) return C.RESULT_END_OF_INPUT
            openPart(partIndex, 0)
        }
    }

    private fun readCurrent(target: ByteArray, offset: Int, length: Int): Int {
        buffer?.let { buf ->
            val n = minOf(length, buf.size - bufferPos)
            if (n <= 0) return -1
            System.arraycopy(buf, bufferPos, target, offset, n)
            bufferPos += n
            return n
        }
        val stream = currentStream ?: return -1
        return try {
            stream.read(target, offset, length)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
    }

    override fun getUri(): Uri? = if (delegating) fileSource?.uri ?: upstream.uri else uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (delegating) upstream.responseHeaders else emptyMap()

    override fun close() {
        if (delegating) {
            delegating = false
            fileSource?.let {
                fileSource = null
                it.close()
                return
            }
            upstream.close()
            return
        }
        closeCurrentPart()
        layout = null
        streaming = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun openPart(index: Int, offset: Long) {
        val l = layout ?: return
        val url = l.urls[index]
        val config = l.adts
        if (config != null) {
            val adts = segmentCache.get(url) ?: run {
                val raw = fetchBytes(url, null)
                Fmp4AdtsTransmuxer.transmux(raw, config)
                    ?: throw DataSourceException(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
            }.also { segmentCache.put(url, it) }
            buffer = adts
            bufferPos = offset.toInt().coerceIn(0, adts.size)
            return
        }
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (!response.isSuccessful) {
            response.close()
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        }
        val body = response.body ?: run {
            response.close()
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        }
        currentResponse = response
        currentStream = body.byteStream().also { s ->
            // A server that ignores Range returns the whole part: skip to the offset ourselves.
            if (offset > 0 && response.code == 200) skipStream(s, offset)
        }
    }

    private fun skipFully(count: Long) {
        var remaining = count
        val scratch = ByteArray(16 * 1024)
        while (remaining > 0) {
            val n = readCurrent(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (n > 0) {
                remaining -= n
            } else {
                closeCurrentPart()
                partIndex++
                val l = layout ?: return
                if (partIndex >= l.urls.size) return
                openPart(partIndex, 0)
            }
        }
    }

    private fun skipStream(stream: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(16 * 1024)
        while (remaining > 0) {
            val n = stream.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (n <= 0) break
            remaining -= n
        }
    }

    private fun closeCurrentPart() {
        runCatching { currentStream?.close() }
        runCatching { currentResponse?.close() }
        currentStream = null
        currentResponse = null
        buffer = null
        bufferPos = 0
    }

    private fun fetchBytes(url: String, range: String?): ByteArray {
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        if (range != null) builder.header("Range", range)
        val started = System.currentTimeMillis()
        // What failed and how: the player only ever said "Source error", so a VK track that never
        // started left nothing to go on.
        fun report(what: String) = com.metrolist.music.resolver.AudioDiagnostics.warn(
            "hls fetch ✘ ${url.toHttpUrlOrNull()?.let { "${it.host}…${it.encodedPath.takeLast(24)}" } ?: "?"}: $what " +
                "after ${System.currentTimeMillis() - started} ms",
        )
        try {
            client.newCall(builder.build()).execute().use { r ->
                if (!r.isSuccessful) {
                    report("HTTP ${r.code}")
                    throw DataSourceException(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
                }
                return r.body?.bytes() ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
            }
        } catch (e: DataSourceException) {
            throw e
        } catch (e: IOException) {
            report("${e.javaClass.simpleName} ${e.message?.take(120).orEmpty()}")
            val host = CdnHosts.hostOf(url)
            if (host != null && CdnHosts.isUnreachable(e)) {
                CdnHosts.markDown(host, e.javaClass.simpleName)
                throw DataSourceException(HostUnreachableException(host, e), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            }
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
    }

    private fun loadLayout(playlistUrl: String): Layout {
        val text = String(fetchBytes(playlistUrl, null), Charsets.UTF_8)
        val playlist = HlsPlaylist.parse(playlistUrl, text)
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)

        val initUrl = playlist.initUrl
        if (initUrl != null) {
            // The init segment decides everything: fetched once without a retry, a single dropped
            // request on a weak network/VPN sent the track down the raw fMP4 path — no duration, no
            // seeking, and the player started the track over after the next hiccup. A fetch that
            // keeps failing is an error (the player retries the open), not a reason to degrade.
            val init = retrying { fetchBytes(initUrl, null) }
                ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            val config = runCatching { Fmp4AdtsTransmuxer.parseInit(init) }.getOrNull()
            if (config != null) {
                val sizes = parallelMap(playlist.segments) { url -> adtsSizeOf(url) }
                if (sizes != null) return Layout(playlist.segments, sizes, config)
                return Layout(playlist.segments, null, config)
            }
        }
        val parts = listOfNotNull(initUrl) + playlist.segments
        return Layout(parts, parallelMap(parts) { url -> headSize(url) }, null)
    }

    /** ADTS output size of one fMP4 segment, from just its fragment header. */
    private fun adtsSizeOf(url: String): Long? {
        var head = fetchBytes(url, "bytes=0-${HEADER_PROBE_BYTES - 1}")
        val moofEnd = Fmp4AdtsTransmuxer.moofEnd(head) ?: return null
        if (moofEnd > head.size) head = fetchBytes(url, "bytes=0-${moofEnd + 64}")
        return Fmp4AdtsTransmuxer.parseFragment(head)?.adtsSize
    }

    private fun headSize(url: String): Long? {
        val request = Request.Builder().url(url).head().header("User-Agent", USER_AGENT).build()
        return client.newCall(request).execute().use { r ->
            if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull() else null
        }
    }

    /**
     * Runs [block] for every url concurrently on OkHttp's dispatcher, then retries the ones that
     * failed (a single dropped request on mobile data used to cost the whole track its length);
     * null if any result is still missing.
     */
    private fun parallelMap(urls: List<String>, block: (String) -> Long?): LongArray? {
        val results = java.util.concurrent.atomic.AtomicLongArray(urls.size).apply { for (i in 0 until length()) set(i, -1L) }
        val latch = CountDownLatch(urls.size)
        val executor = client.dispatcher.executorService
        urls.forEachIndexed { i, url ->
            executor.execute {
                try {
                    results.set(i, runCatching { block(url) }.getOrNull() ?: -1L)
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(SIZE_PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        val out = LongArray(urls.size) { results.get(it) }
        for (i in out.indices) {
            if (out[i] < 0) out[i] = retrying { block(urls[i]) } ?: return null
        }
        return out
    }

    companion object {
        const val SCHEME = "meldhls"
        private const val SIZE_PROBE_TIMEOUT_S = 12L
        private const val PROBE_ATTEMPTS = 3
        private const val HEADER_PROBE_BYTES = 8192
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        private const val WHOLE_PARAM = "w"
        private const val WHOLE_TIMEOUT_S = 90L
        private const val SEGMENT_PARALLELISM = 8

        private val layoutCache = LruCache<String, Layout>(16)

        /** Segment downloads for whole tracks: bounded, so a long track doesn't open 70 connections at once. */
        private val segmentPool = java.util.concurrent.Executors.newFixedThreadPool(SEGMENT_PARALLELISM)

        /** Transmuxed segments; a player reopens the same part several times while buffering/seeking. */
        private val segmentCache = LruCache<String, ByteArray>(4)

        /** Fully assembled tracks ("whole" mode); the player reopens them on every seek. */
        private val wholeCache = LruCache<String, ByteArray>(2)

        /** Streamed tracks once complete, padded to the length the player was told. */
        private val streamedCache = LruCache<String, ByteArray>(2)

        /** Tracks being streamed, by playlist url (a seek reopens the same one). */
        private val streams = object : LinkedHashMap<String, WholeStream>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WholeStream>?) = size > 3
        }

        /** How long a reader waits for the next segment before giving up. */
        private const val SEGMENT_WAIT_MS = 25_000L

        /** How long a track's first sound may take to arrive. */
        private const val FIRST_SEGMENT_WAIT_MS = 10_000L

        /** Declared length = estimate × this, so the real audio is never cut (the rest is silence). */
        private const val LENGTH_MARGIN = 1.002

        /**
         * [whole]: fetch, decrypt and demux the complete track before playing it — for playlists
         * whose segments are AES-128 encrypted MPEG-TS (VK).
         */
        /** The playlist url inside a [wrap]ped uri, or null for any other uri. */
        fun playlistUrlOf(uri: String): String? {
            if (!uri.startsWith("$SCHEME://")) return null
            val encoded = uri.substringAfter("u=", "").substringBefore('&').ifBlank { return null }
            return runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
        }

        fun wrap(playlistUrl: String, whole: Boolean = false): String =
            "$SCHEME://hls?u=" + URLEncoder.encode(playlistUrl, "UTF-8") + if (whole) "&$WHOLE_PARAM=1" else ""

        fun isWrapped(uri: String): Boolean = uri.startsWith("$SCHEME://")

        fun unwrap(uri: Uri): String? = uri.getQueryParameter("u")
    }

    /**
     * One "whole" track being fetched segment by segment: readers block until the bytes they need
     * have arrived. See [openStreamed].
     */
    private class WholeStream(
        val playlistUrl: String,
        segmentCount: Int,
        /** Total length per the playlist; 0 = unknown, so not streamable. */
        private val seconds: Double,
        private val started: Long,
    ) {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        private val lock = Object() as java.lang.Object
        private val chunks = arrayOfNulls<ByteArray>(segmentCount)
        private var ready = 0

        @Volatile
        var failure: Throwable? = null
            private set

        /** -1 = not known yet, 0 = not streamable, otherwise the length the player is told. */
        private var declared = -1L
        private var silentFrame: ByteArray? = null
        private var exact: ByteArray? = null
        private var padded: ByteArray? = null

        fun segmentReady(index: Int, audio: ByteArray) {
            synchronized(lock) {
                chunks[index] = audio
                ready++
                if (index == 0) {
                    com.metrolist.music.resolver.AudioDiagnostics.info(
                        "hls: first segment after ${System.currentTimeMillis() - started} ms (${audio.size / 1024} KB)",
                    )
                    val cbr = if (seconds > 0.0) MeasuredAudio.constantMp3(audio) else null
                    declared = cbr?.let { (it.bitrate / 8.0 * seconds * LENGTH_MARGIN).toLong() + it.frameLength } ?: 0L
                    silentFrame = cbr?.silentFrame()
                    if (cbr == null) {
                        val head = audio.take(4).joinToString(" ") { "%02x".format(it) }
                        com.metrolist.music.resolver.AudioDiagnostics.warn(
                            "hls: served whole — playlist ${"%.1f".format(seconds)} s, first segment ${audio.size} B starts $head, not constant-bitrate MP3",
                        )
                    }
                }
                if (ready == chunks.size) finish()
                lock.notifyAll()
            }
        }

        fun fail(t: Throwable) {
            synchronized(lock) {
                if (failure == null) failure = t
                lock.notifyAll()
            }
        }

        /** Called under [lock] once every segment is in. */
        private fun finish() {
            val total = chunks.sumOf { it!!.size }
            val joined = ByteArray(total)
            var at = 0
            for (c in chunks) {
                c!!.copyInto(joined, at)
                at += c.size
            }
            exact = joined
            wholeCache.put(playlistUrl, joined)
            MeasuredAudio.analyze(joined)?.let { MeasuredAudio.record(playlistUrl, it) }
            if (declared > 0) {
                val filled = ByteArray(declared.toInt())
                joined.copyInto(filled, 0, 0, minOf(joined.size, filled.size))
                fillSilence(filled, joined.size)
                padded = filled
                streamedCache.put(playlistUrl, filled)
            }
            com.metrolist.music.resolver.AudioDiagnostics.info(
                "hls streamed: ${total / 1024} KB in ${System.currentTimeMillis() - started} ms, " +
                    "player told ${declared / 1024} KB (${chunks.size} segments)",
            )
            synchronized(streams) { streams.remove(playlistUrl) }
        }

        /** Silent MP3 frames from [from] to the end (a partial last frame stays zeros). */
        private fun fillSilence(target: ByteArray, from: Int) {
            val frame = silentFrame ?: return
            var at = from
            while (at + frame.size <= target.size) {
                frame.copyInto(target, at)
                at += frame.size
            }
        }

        /** Waits under [lock]; gives up when no segment has arrived for [waitMs]. */
        private fun waitFor(waitMs: Long = SEGMENT_WAIT_MS, condition: () -> Boolean) {
            var lastProgress = ready
            var deadline = System.currentTimeMillis() + waitMs
            while (!condition()) {
                failure?.let { t ->
                    throw t as? DataSourceException ?: DataSourceException(t, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
                }
                if (ready != lastProgress) {
                    lastProgress = ready
                    deadline = System.currentTimeMillis() + waitMs
                }
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
                try {
                    lock.wait(minOf(left, 1_000L))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException()
                }
            }
        }

        /**
         * The start is waited for less long than the rest: a source that sends nothing at all gives
         * way to the next one in seconds — the track used to stay silent for half a minute before
         * playing from YouTube anyway.
         */
        fun awaitDeclaredLength(): Long = synchronized(lock) {
            waitFor(FIRST_SEGMENT_WAIT_MS) { declared >= 0 }
            declared
        }

        fun awaitExact(): ByteArray = synchronized(lock) {
            waitFor { exact != null }
            exact!!
        }

        /** Copies bytes at [position] of the declared stream into [target]; blocks for missing segments. */
        fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
            synchronized(lock) { return readLocked(position, target, offset, length) }
        }

        private fun readLocked(position: Long, target: ByteArray, offset: Int, length: Int): Int {
            padded?.let { return copy(it, position, target, offset, length) }
            var start = 0L
            for (i in chunks.indices) {
                waitFor { chunks[i] != null || padded != null }
                padded?.let { return copy(it, position, target, offset, length) }
                val chunk = chunks[i]!!
                if (position < start + chunk.size) {
                    val inChunk = (position - start).toInt()
                    val n = minOf(length, chunk.size - inChunk)
                    chunk.copyInto(target, offset, inChunk, inChunk + n)
                    return n
                }
                start += chunk.size
            }
            // Past the real audio but within the declared length: the silence, once laid down.
            waitFor { padded != null }
            return copy(padded!!, position, target, offset, length)
        }

        private fun copy(all: ByteArray, position: Long, target: ByteArray, offset: Int, length: Int): Int {
            if (position >= all.size) return -1
            val n = minOf(length.toLong(), all.size - position).toInt()
            all.copyInto(target, offset, position.toInt(), position.toInt() + n)
            return n
        }
    }
}

/** Pure HLS media-playlist parsing (kept Android-free for unit tests). */
object HlsPlaylist {
    private val MAP_URI = Regex("URI=\"([^\"]+)\"")
    private val KEY_IV = Regex("IV=0[xX]([0-9a-fA-F]+)")

    /** Plain AES-128 key of a segment: where to fetch it, and its IV when the playlist names one. */
    data class Key(val url: String, val iv: ByteArray?)

    data class MediaPlaylist(
        val initUrl: String?,
        val segments: List<String>,
        /** Per segment, parallel to [segments]; null = not encrypted. Empty for unencrypted playlists. */
        val keys: List<Key?> = emptyList(),
        val mediaSequence: Long = 0,
        /** #EXTINF length of each segment in seconds, parallel to [segments] (0 when missing). */
        val durations: List<Double> = emptyList(),
    )

    /**
     * Init section + absolute segment urls of a media playlist; null for a master playlist.
     *
     * An encrypted playlist is rejected unless [allowAes128] is set, and then only plain AES-128
     * with a fetchable key is accepted (VK serves that; anything else — SAMPLE-AES, DRM key
     * schemes — is real protection and stays rejected).
     */
    fun parse(baseUrl: String, text: String, allowAes128: Boolean = false): MediaPlaylist? {
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        if (!text.trimStart().startsWith("#EXTM3U")) return null
        var init: String? = null
        var sequence = 0L
        var currentKey: Key? = null
        var anyKey = false
        val segments = mutableListOf<String>()
        val keys = mutableListOf<Key?>()
        val durations = mutableListOf<Double>()
        var pendingDuration = 0.0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-STREAM-INF") -> return null
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY") -> when {
                    line.contains("METHOD=NONE") -> currentKey = null
                    allowAes128 && line.contains("METHOD=AES-128") -> {
                        val keyUri = MAP_URI.find(line)?.groupValues?.get(1) ?: return null
                        val iv = KEY_IV.find(line)?.groupValues?.get(1)?.let(::hexToBytes)
                        currentKey = Key(base.resolve(keyUri)?.toString() ?: return null, iv)
                        anyKey = true
                    }
                    else -> return null
                }
                line.startsWith("#EXT-X-MAP") -> {
                    val mapUri = MAP_URI.find(line)?.groupValues?.get(1) ?: return null
                    // Only one init section is supported (a mid-stream re-init would corrupt the file).
                    if (segments.isNotEmpty()) return null
                    if (init == null) init = base.resolve(mapUri)?.toString()
                }
                line.startsWith("#EXTINF:") ->
                    pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#") -> Unit
                else -> base.resolve(line)?.toString()?.let {
                    segments += it
                    keys += currentKey
                    durations += pendingDuration
                    pendingDuration = 0.0
                }
            }
        }
        if (segments.isEmpty()) return null
        return MediaPlaylist(init, segments, if (anyKey) keys else emptyList(), sequence, durations)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val padded = hex.padStart(32, '0').takeLast(32)
        return ByteArray(16) { i -> padded.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Absolute part urls, init section first. */
    fun parseMediaPlaylist(baseUrl: String, text: String): List<String>? =
        parse(baseUrl, text)?.let { listOfNotNull(it.initUrl) + it.segments }
}
