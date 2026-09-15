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
 * Plays an (unencrypted) HLS *media* playlist as ONE continuous progressive stream. Meld's player and
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
        val l = layoutCache.get(playlistUrl) ?: loadLayout(playlistUrl).also { layoutCache.put(playlistUrl, it) }
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

    /** Runs [block] for every url concurrently on OkHttp's dispatcher; null if any result is missing. */
    private fun parallelMap(urls: List<String>, block: (String) -> Long?): LongArray? {
        val results = LongArray(urls.size) { -1L }
        val latch = CountDownLatch(urls.size)
        val executor = client.dispatcher.executorService
        urls.forEachIndexed { i, url ->
            executor.execute {
                try {
                    results[i] = runCatching { block(url) }.getOrNull() ?: -1L
                } finally {
                    latch.countDown()
                }
            }
        }
        if (!latch.await(SIZE_PROBE_TIMEOUT_S, TimeUnit.SECONDS)) return null
        return results.takeIf { r -> r.all { it >= 0 } }
    }

    companion object {
        const val SCHEME = "meldhls"
        private const val SIZE_PROBE_TIMEOUT_S = 10L
        private const val HEADER_PROBE_BYTES = 8192
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        private val layoutCache = LruCache<String, Layout>(16)

        /** Transmuxed segments; a player reopens the same part several times while buffering/seeking. */
        private val segmentCache = LruCache<String, ByteArray>(4)

        fun wrap(playlistUrl: String): String = "$SCHEME://hls?u=" + URLEncoder.encode(playlistUrl, "UTF-8")

        fun isWrapped(uri: String): Boolean = uri.startsWith("$SCHEME://")

        fun unwrap(uri: Uri): String? = uri.getQueryParameter("u")
    }
}

/** Pure HLS media-playlist parsing (kept Android-free for unit tests). */
object HlsPlaylist {
    private val MAP_URI = Regex("URI=\"([^\"]+)\"")

    data class MediaPlaylist(val initUrl: String?, val segments: List<String>)

    /** Init section + absolute segment urls of an unencrypted media playlist; null for master/encrypted. */
    fun parse(baseUrl: String, text: String): MediaPlaylist? {
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        if (!text.trimStart().startsWith("#EXTM3U")) return null
        var init: String? = null
        val segments = mutableListOf<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-STREAM-INF") -> return null
                line.startsWith("#EXT-X-KEY") && !line.contains("METHOD=NONE") -> return null
                line.startsWith("#EXT-X-MAP") -> {
                    val mapUri = MAP_URI.find(line)?.groupValues?.get(1) ?: return null
                    // Only one init section is supported (a mid-stream re-init would corrupt the file).
                    if (segments.isNotEmpty()) return null
                    if (init == null) init = base.resolve(mapUri)?.toString()
                }
                line.startsWith("#") -> Unit
                else -> base.resolve(line)?.toString()?.let(segments::add)
            }
        }
        if (segments.isEmpty()) return null
        return MediaPlaylist(init, segments)
    }

    /** Absolute part urls, init section first. */
    fun parseMediaPlaylist(baseUrl: String, text: String): List<String>? =
        parse(baseUrl, text)?.let { listOfNotNull(it.initUrl) + it.segments }
}
