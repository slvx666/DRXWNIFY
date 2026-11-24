/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * googlevideo stops serving a stream address after about a megabyte: the next piece comes back short
 * or empty, the extractor hit an EOF and the track broke off every minute or so (then switched to
 * another source mid-song, or — with the screen off — stood still until the phone was woken).
 *
 * Here a YouTube piece that ends before its bytes are all there is read on from the same byte with a
 * fresh address of the same video ([freshAddress]), so the player never sees the break. Anything that
 * is not googlevideo, and a piece that really reached the end of the file, passes through untouched.
 */
@UnstableApi
class YoutubeResumingDataSource(
    private val upstream: DataSource,
    /** A fresh address for the same video and format, or null when there is none. */
    private val freshAddress: (key: String?, old: Uri) -> Uri?,
    private val onResumed: (key: String?, position: Long) -> Unit = { _, _ -> },
) : DataSource {

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val freshAddress: (key: String?, old: Uri) -> Uri?,
        private val onResumed: (key: String?, position: Long) -> Unit = { _, _ -> },
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            YoutubeResumingDataSource(upstreamFactory.createDataSource(), freshAddress, onResumed)
    }

    /** The piece as it was asked for. */
    private var origin: DataSpec? = null

    /** The address the piece is being read from now. */
    private var address: Uri? = null
    private var watching = false

    /** Bytes the first open promised, or unset. */
    private var promised = C.LENGTH_UNSET.toLong()

    /** The whole file's length (Content-Range / clen), or unset. */
    private var fileLength = C.LENGTH_UNSET.toLong()

    /** Bytes of the piece delivered so far. */
    private var read = 0L
    private var freshTries = 0

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        origin = dataSpec
        address = dataSpec.uri
        read = 0
        freshTries = 0
        fileLength = C.LENGTH_UNSET.toLong()
        watching = isYoutube(dataSpec.uri)
        if (!watching) return upstream.open(dataSpec)
        promised = openAt(dataSpec)
        return promised
    }

    private fun openAt(spec: DataSpec): Long {
        val length = try {
            upstream.open(spec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            // A spent address answers a piece past the start with 403: a fresh one instead.
            if (e.responseCode != 403 || spec.position == 0L) throw e
            val fresh = renew(spec) ?: throw e
            runCatching { upstream.close() }
            return openAt(fresh)
        }
        address = spec.uri
        (totalFrom(upstream.responseHeaders) ?: clenOf(spec.uri))?.let { fileLength = it }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val n = upstream.read(buffer, offset, length)
            if (n != C.RESULT_END_OF_INPUT) {
                read += n
                if (n > 0) freshTries = 0
                return n
            }
            if (!watching || !cutShort()) return n
            val piece = origin ?: return n
            val rest = piece.buildUpon()
                .setUri(address ?: piece.uri)
                .setPosition(piece.position + read)
                .setLength(if (piece.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else piece.length - read)
                .build()
            val fresh = renew(rest) ?: return n
            runCatching { upstream.close() }
            openAt(fresh)
            onResumed(piece.key, piece.position + read)
        }
    }

    /** The piece ended before its promised bytes, and the file goes on past where it stopped. */
    private fun cutShort(): Boolean {
        val piece = origin ?: return false
        if (promised != C.LENGTH_UNSET.toLong() && read >= promised) return false
        val reached = piece.position + read
        if (fileLength != C.LENGTH_UNSET.toLong()) return reached < fileLength
        // Nothing says how long the file is: only an empty piece past the start counts as a break.
        return read == 0L && piece.position > 0
    }

    private fun renew(at: DataSpec): DataSpec? {
        if (freshTries >= MAX_FRESH) return null
        freshTries++
        val uri = runCatching { freshAddress(at.key, at.uri) }.getOrNull() ?: return null
        return at.withUri(uri)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        origin = null
        address = null
        upstream.close()
    }

    companion object {
        /** Fresh addresses in a row without a byte from them before the piece is given up. */
        private const val MAX_FRESH = 3

        fun isYoutube(uri: Uri): Boolean =
            uri.scheme?.startsWith("http") == true && uri.host?.endsWith("googlevideo.com") == true

        /** "bytes 0-524287/3349481" → 3349481. */
        private fun totalFrom(headers: Map<String, List<String>>): Long? =
            headers.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value?.firstOrNull()?.substringAfterLast('/', "")?.trim()?.toLongOrNull()

        private fun clenOf(uri: Uri): Long? = runCatching { uri.getQueryParameter("clen")?.toLongOrNull() }.getOrNull()
    }
}
