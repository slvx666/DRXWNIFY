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
 * A YouTube stream read as one unbroken file, fetched from googlevideo in pieces.
 *
 * googlevideo wants ranged requests (a request without a Range header gets 403) and stops serving
 * one address after about a megabyte. The player used to get the track in 512 KB pieces: each piece
 * ended in an EOF that the player retried, and when a piece happened to end exactly between two audio
 * blocks the player took it for the end of the track — "Alias" stopped at 0:31 and was started over
 * from another source.
 *
 * Here the player sees the whole stream. It is fetched in [PIECE] ranges one after another; a piece
 * that ends early, an empty answer, a refused piece or a dropped connection go on from the same byte
 * with a fresh address of the same video ([freshAddress]). With no fresh address the read fails with
 * an error (the player retries), never with a quiet end before the file's end. Anything that is not
 * googlevideo passes through untouched.
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

    /** The range as the player asked for it. */
    private var origin: DataSpec? = null

    /** The address the pieces are read from now. */
    private var address: Uri? = null
    private var watching = false

    /** The whole file's length (Content-Range / clen), or unset. */
    private var fileLength = UNSET

    /** Bytes delivered of the asked range so far. */
    private var read = 0L

    /** The piece open now: how long it was answered, and how much of it is read. */
    private var pieceLength = UNSET
    private var pieceRead = 0L
    private var pieceOpen = false
    private var freshTries = 0

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        origin = dataSpec
        address = dataSpec.uri
        read = 0
        freshTries = 0
        fileLength = UNSET
        pieceOpen = false
        watching = isYoutube(dataSpec.uri)
        if (!watching) return upstream.open(dataSpec)
        fileLength = clenOf(dataSpec.uri) ?: UNSET
        openPiece(dataSpec.position, renewOnFailure = dataSpec.position > 0)
        val end = endOf(dataSpec)
        return if (end == UNSET) UNSET else end - dataSpec.position
    }

    /** Where the asked range ends (exclusive), or unset while the file's length is not known. */
    private fun endOf(spec: DataSpec): Long = when {
        spec.length != UNSET -> spec.position + spec.length
        fileLength != UNSET -> fileLength
        else -> UNSET
    }

    /** Opens the next piece at [position] of the file from the current address. */
    private fun openPiece(position: Long, renewOnFailure: Boolean) {
        val spec = origin ?: return
        val end = endOf(spec)
        val length = if (end == UNSET) PIECE else minOf(PIECE, end - position)
        val piece = spec.buildUpon()
            .setUri(address ?: spec.uri)
            .setPosition(position)
            .setLength(length)
            .build()
        val answered = openAt(piece, renewOnFailure)
        pieceLength = if (answered == UNSET) length else answered
        pieceRead = 0
        pieceOpen = true
    }

    private fun openAt(spec: DataSpec, renewOnFailure: Boolean): Long {
        val length = try {
            upstream.open(spec)
        } catch (e: java.io.IOException) {
            // A spent address refuses a piece past the start (403, or drops the connection): a fresh
            // one instead. The very start is the resolver's to deal with.
            runCatching { upstream.close() }
            if (!renewOnFailure) throw e
            if (e is HttpDataSource.InvalidResponseCodeException) {
                if (e.responseCode in 500..599) throw e
                if (e.responseCode == 416 && (fileLength == UNSET || spec.position >= fileLength)) throw e
            }
            val fresh = renew(spec) ?: throw e
            return openAt(fresh, renewOnFailure = true)
        }
        address = spec.uri
        totalFrom(upstream.responseHeaders)?.let { fileLength = it }
        // An empty answer while the file goes on: the address is spent.
        if (length == 0L && (fileLength == UNSET || spec.position < fileLength)) {
            renew(spec)?.let { fresh ->
                runCatching { upstream.close() }
                return openAt(fresh, renewOnFailure = true)
            }
        }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!watching) return upstream.read(buffer, offset, length)
        val spec = origin ?: return C.RESULT_END_OF_INPUT
        while (true) {
            val reached = spec.position + read
            val end = endOf(spec)
            if (end != UNSET && reached >= end) return C.RESULT_END_OF_INPUT
            if (!pieceOpen) {
                try {
                    openPiece(reached, renewOnFailure = true)
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    // Past the last byte of a file whose length nobody stated: that was the end.
                    if (e.responseCode == 416 && end == UNSET) return C.RESULT_END_OF_INPUT
                    throw e
                }
            }
            val n = try {
                upstream.read(buffer, offset, length)
            } catch (e: java.io.IOException) {
                // The connection dropped in the middle of the piece (mobile networks, a spent address
                // closing it): read on from the same byte with a fresh address.
                if (!resumeFromHere()) throw e
                continue
            }
            if (n != C.RESULT_END_OF_INPUT) {
                read += n
                pieceRead += n
                if (n > 0) freshTries = 0
                return n
            }
            val endNow = endOf(spec)
            if (endNow != UNSET && reached >= endNow) return C.RESULT_END_OF_INPUT
            if (pieceRead < pieceLength) {
                // Fewer bytes than the answer promised: cut off on the way, the address is spent.
                if (!resumeFromHere()) throw java.io.EOFException("YouTube piece cut off at $reached and no fresh address")
                continue
            }
            // The piece is whole. A last piece shorter than asked, of a file with no stated length, was the end.
            if (endNow == UNSET && pieceLength < PIECE) return C.RESULT_END_OF_INPUT
            runCatching { upstream.close() }
            pieceOpen = false
        }
    }

    /** Reopens at the byte reached, with a fresh address; false when there is none. */
    private fun resumeFromHere(): Boolean {
        val spec = origin ?: return false
        val at = spec.position + read
        val end = endOf(spec)
        if (end != UNSET && at >= end) return false
        runCatching { upstream.close() }
        pieceOpen = false
        val stale = spec.buildUpon().setUri(address ?: spec.uri).setPosition(at).build()
        val fresh = renew(stale) ?: return false
        address = fresh.uri
        return runCatching { openPiece(at, renewOnFailure = true) }.isSuccess.also { ok ->
            if (ok) onResumed(spec.key, at)
        }
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
        pieceOpen = false
        upstream.close()
    }

    companion object {
        private const val UNSET = C.LENGTH_UNSET.toLong()

        /** One ranged request to googlevideo. */
        const val PIECE = 512 * 1024L

        /** Fresh addresses in a row without a byte from them before the read is given up. */
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
