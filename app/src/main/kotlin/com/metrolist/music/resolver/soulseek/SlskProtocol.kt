/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver.soulseek

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * Soulseek wire format (the protocol spoken by Soulseek NS / Nicotine+). All integers little-endian,
 * strings = uint32 length + UTF-8 bytes.
 *
 *  - Server and peer messages: uint32 length, uint32 code, payload.
 *  - Peer "init" messages (first message on a new peer connection): uint32 length, uint8 code, payload.
 *
 * Pure byte logic, no sockets — unit-tested.
 */
object SlskProtocol {
    // Server message codes
    const val S_LOGIN = 1
    const val S_GET_PEER_ADDRESS = 3
    const val S_CONNECT_TO_PEER = 18
    const val S_FILE_SEARCH = 26
    const val S_SET_STATUS = 28
    const val S_PING = 32
    const val S_SHARED_FOLDERS_FILES = 35
    const val S_HAVE_NO_PARENT = 71

    // Peer init codes
    const val I_PIERCE_FIREWALL = 0
    const val I_PEER_INIT = 1

    // Peer message codes
    const val P_SEARCH_RESPONSE = 9
    const val P_TRANSFER_REQUEST = 40
    const val P_TRANSFER_RESPONSE = 41
    const val P_QUEUE_UPLOAD = 43
    const val P_PLACE_IN_QUEUE = 44
    const val P_UPLOAD_FAILED = 46
    const val P_UPLOAD_DENIED = 50

    const val CLIENT_VERSION = 160
    const val CLIENT_MINOR_VERSION = 1

    // ── Writer / reader ──────────────────────────────────────────────────────────────────────────

    class Writer {
        private val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v and 0xFF) }
        fun bool(v: Boolean) = u8(if (v) 1 else 0)
        fun u32(v: Long) = apply { for (i in 0 until 4) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
        fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
        fun u64(v: Long) = apply { for (i in 0 until 8) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
        fun str(v: String) = apply {
            val b = v.toByteArray(Charsets.UTF_8)
            u32(b.size)
            out.write(b)
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    class Reader(private val b: ByteArray, var pos: Int = 0) {
        val remaining: Int get() = b.size - pos
        fun u8(): Int {
            check(pos < b.size) { "truncated" }
            return b[pos++].toInt() and 0xFF
        }
        fun bool(): Boolean = u8() != 0
        fun u32(): Long {
            check(pos + 4 <= b.size) { "truncated" }
            var v = 0L
            for (i in 0 until 4) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 4
            return v
        }
        fun i32(): Int = u32().toInt()
        fun u64(): Long {
            check(pos + 8 <= b.size) { "truncated" }
            var v = 0L
            for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 8
            return v
        }
        fun str(): String {
            val len = u32()
            check(len >= 0 && pos + len <= b.size) { "bad string length $len" }
            val s = String(b, pos, len.toInt(), Charsets.UTF_8)
            pos += len.toInt()
            return s
        }
        /** uint32 IPv4 as sent by the server (little-endian of the big-endian address). */
        fun ip(): String {
            val v = u32()
            return "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"
        }
    }

    /** uint32 length + uint32 code + payload. */
    fun message(code: Int, payload: Writer.() -> Unit = {}): ByteArray {
        val body = Writer().u32(code).apply(payload).bytes()
        return Writer().u32(body.size).bytes() + body
    }

    /** uint32 length + uint8 code + payload (first message on a peer connection). */
    fun initMessage(code: Int, payload: Writer.() -> Unit): ByteArray {
        val body = Writer().u8(code).apply(payload).bytes()
        return Writer().u32(body.size).bytes() + body
    }

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // ── Server messages we send ──────────────────────────────────────────────────────────────────

    fun login(username: String, password: String) = message(S_LOGIN) {
        str(username); str(password); u32(CLIENT_VERSION); str(md5Hex(username + password)); u32(CLIENT_MINOR_VERSION)
    }

    fun setStatusOnline() = message(S_SET_STATUS) { u32(2) }
    fun sharedFoldersFiles(folders: Int, files: Int) = message(S_SHARED_FOLDERS_FILES) { u32(folders); u32(files) }
    fun haveNoParent() = message(S_HAVE_NO_PARENT) { bool(true) }
    fun ping() = message(S_PING)
    fun fileSearch(token: Int, query: String) = message(S_FILE_SEARCH) { u32(token); str(query) }
    fun getPeerAddress(username: String) = message(S_GET_PEER_ADDRESS) { str(username) }

    // ── Peer messages we send ────────────────────────────────────────────────────────────────────

    fun peerInit(username: String, type: String, token: Int = 0) = initMessage(I_PEER_INIT) { str(username); str(type); u32(token) }
    fun pierceFirewall(token: Int) = initMessage(I_PIERCE_FIREWALL) { u32(token) }
    fun queueUpload(filename: String) = message(P_QUEUE_UPLOAD) { str(filename) }
    fun transferResponseAllowed(token: Int) = message(P_TRANSFER_RESPONSE) { u32(token); bool(true) }

    // ── Parsed messages ──────────────────────────────────────────────────────────────────────────

    sealed interface LoginResult {
        data class Success(val greeting: String) : LoginResult
        data class Failure(val reason: String) : LoginResult
    }

    fun parseLogin(payload: ByteArray): LoginResult {
        val r = Reader(payload)
        return if (r.bool()) LoginResult.Success(runCatching { r.str() }.getOrDefault(""))
        else LoginResult.Failure(runCatching { r.str() }.getOrDefault("login rejected"))
    }

    data class ConnectToPeer(val username: String, val type: String, val ip: String, val port: Int, val token: Int)

    fun parseConnectToPeer(payload: ByteArray): ConnectToPeer {
        val r = Reader(payload)
        return ConnectToPeer(username = r.str(), type = r.str(), ip = r.ip(), port = r.i32(), token = r.i32())
    }

    data class PeerAddress(val username: String, val ip: String, val port: Int)

    fun parsePeerAddress(payload: ByteArray): PeerAddress {
        val r = Reader(payload)
        return PeerAddress(r.str(), r.ip(), r.i32())
    }

    data class SearchFile(
        val filename: String,
        val size: Long,
        val bitrate: Int?,
        val durationSec: Int?,
        val sampleRate: Int?,
        val bitDepth: Int?,
    ) {
        val extension: String get() = filename.substringAfterLast('.', "").lowercase()

        /** Path segments of the (backslash-separated) remote path, file name last. */
        val segments: List<String> get() = filename.split('\\', '/').filter { it.isNotBlank() }
    }

    data class SearchResponse(
        val username: String,
        val token: Int,
        val files: List<SearchFile>,
        val freeSlot: Boolean,
        val speed: Long,
        val queueLength: Long,
    )

    /** Peer code 9: the payload after the code is zlib-compressed. */
    fun parseSearchResponse(compressed: ByteArray): SearchResponse {
        val r = Reader(inflate(compressed))
        val username = r.str()
        val token = r.i32()
        val count = r.u32()
        val files = ArrayList<SearchFile>(count.coerceAtMost(500).toInt())
        repeat(count.coerceAtMost(5000).toInt()) {
            r.u8()
            val name = r.str()
            val size = r.u64()
            r.str() // extension (legacy, usually empty)
            var bitrate: Int? = null
            var duration: Int? = null
            var sampleRate: Int? = null
            var bitDepth: Int? = null
            repeat(r.u32().coerceAtMost(32).toInt()) {
                val code = r.i32()
                val value = r.i32()
                when (code) {
                    0 -> bitrate = value
                    1 -> duration = value
                    4 -> sampleRate = value
                    5 -> bitDepth = value
                }
            }
            files += SearchFile(name, size, bitrate, duration, sampleRate, bitDepth)
        }
        val freeSlot = runCatching { r.bool() }.getOrDefault(false)
        val speed = runCatching { r.u32() }.getOrDefault(0)
        val queue = runCatching { r.u32() }.getOrDefault(0)
        return SearchResponse(username, token, files, freeSlot, speed, queue)
    }

    data class TransferRequest(val direction: Int, val token: Int, val filename: String, val size: Long?)

    fun parseTransferRequest(payload: ByteArray): TransferRequest {
        val r = Reader(payload)
        val direction = r.i32()
        val token = r.i32()
        val filename = r.str()
        val size = if (direction == 1 && r.remaining >= 8) r.u64() else null
        return TransferRequest(direction, token, filename, size)
    }

    fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4)
        val buf = ByteArray(16 * 1024)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
                check(out.size() < 16 * 1024 * 1024) { "search response too large" }
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }
}
