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
) : BaseDataSource(/* isNetwork = */ true) {

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val client: OkHttpClient,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            HlsConcatDataSource(upstreamFactory.createDataSource(), client)
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
        if (dataSpec.uri.getQueryParameter(WHOLE_PARAM) == "1") return openWhole(dataSpec, playlistUrl)
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
        val bytes = wholeCache.get(playlistUrl) ?: assembleWhole(playlistUrl).also { wholeCache.put(playlistUrl, it) }
        return serveBytes(dataSpec, bytes)
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
        val executor = client.dispatcher.executorService
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
            runCatching(block).getOrNull()?.let { return it }
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
        val executor = client.dispatcher.executorService
        urls.forEachIndexed { i, url ->
            executor.execute {
                try {
                    val raw = fetchBytes(url, null)
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
        try {
            client.newCall(builder.build()).execute().use { r ->
                if (!r.isSuccessful) throw DataSourceException(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
                return r.body?.bytes() ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
            }
        } catch (e: DataSourceException) {
            throw e
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
    }

    private fun loadLayout(playlistUrl: String): Layout {
        val text = String(fetchBytes(playlistUrl, null), Charsets.UTF_8)
        val playlist = HlsPlaylist.parse(playlistUrl, text)
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)

        val initUrl = playlist.initUrl
        if (initUrl != null) {
            val config = runCatching { Fmp4AdtsTransmuxer.parseInit(fetchBytes(initUrl, null)) }.getOrNull()
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
        private const val WHOLE_TIMEOUT_S = 60L

        private val layoutCache = LruCache<String, Layout>(16)

        /** Transmuxed segments; a player reopens the same part several times while buffering/seeking. */
        private val segmentCache = LruCache<String, ByteArray>(4)

        /** Fully assembled tracks ("whole" mode); the player reopens them on every seek. */
        private val wholeCache = LruCache<String, ByteArray>(2)

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
                line.startsWith("#") -> Unit
                else -> base.resolve(line)?.toString()?.let {
                    segments += it
                    keys += currentKey
                }
            }
        }
        if (segments.isEmpty()) return null
        return MediaPlaylist(init, segments, if (anyKey) keys else emptyList(), sequence)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val padded = hex.padStart(32, '0').takeLast(32)
        return ByteArray(16) { i -> padded.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Absolute part urls, init section first. */
    fun parseMediaPlaylist(baseUrl: String, text: String): List<String>? =
        parse(baseUrl, text)?.let { listOfNotNull(it.initUrl) + it.segments }
}
