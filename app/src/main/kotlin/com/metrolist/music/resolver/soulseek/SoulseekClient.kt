/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver.soulseek

import com.metrolist.music.resolver.AudioDiagnostics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Minimal Soulseek client: log in, search, download one file. Meld never shares files and never listens
 * for incoming connections (phones sit behind NAT): every peer connection is outgoing — directly to the
 * peer's address, or "pierced" when the server relays a peer's connection request to us.
 *
 * The server connection is opened on demand and closed after [IDLE_DISCONNECT_MS] without use, so an
 * enabled Soulseek source doesn't keep the radio awake.
 */
class SoulseekClient(
    private val credentials: () -> Pair<String, String>?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()

    @Volatile private var server: Socket? = null
    @Volatile private var serverOut: OutputStream? = null
    @Volatile private var loggedInAs: String? = null
    @Volatile private var lastUsedAt = 0L
    private var readerJob: Job? = null
    private var idleJob: Job? = null
    private var pendingLogin: CompletableDeferred<SlskProtocol.LoginResult>? = null

    private val tokens = AtomicInteger(Random.nextInt(1, Int.MAX_VALUE / 2))
    private val searches = ConcurrentHashMap<Int, CopyOnWriteArrayList<SlskProtocol.SearchResponse>>()
    private val peerAddress = ConcurrentHashMap<String, CompletableDeferred<SlskProtocol.PeerAddress>>()

    /** Open peer ("P") connections by username, reused for queueing downloads. */
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()

    private class Download(
        val username: String,
        val filename: String,
        val target: File,
        val result: CompletableDeferred<File?> = CompletableDeferred(),
    ) {
        @Volatile var transferToken: Int? = null
        @Volatile var size: Long? = null
    }

    private val downloads = CopyOnWriteArrayList<Download>()

    // For the diagnostics log: how many peers asked to reach us, and how many we reached.
    private val connectRequests = AtomicInteger()

    // After a failed connect/login, every caller gets the same answer for a while instead of each
    // track of a queue opening its own doomed connection (and the server seeing a login storm).
    @Volatile private var failedUntil = 0L
    @Volatile private var lastFailure: String? = null

    private fun fail(reason: String): Nothing {
        lastFailure = reason
        failedUntil = System.currentTimeMillis() + FAILURE_BACKOFF_MS
        AudioDiagnostics.warn("soulseek: $reason")
        throw LoginException(reason)
    }
    private val connectFailures = AtomicInteger()

    class LoginException(message: String) : Exception(message)

    // ── Server connection ────────────────────────────────────────────────────────────────────────

    private fun sendServer(bytes: ByteArray) {
        val out = serverOut ?: throw IOException("Soulseek server not connected")
        synchronized(out) { out.write(bytes); out.flush() }
    }

    private suspend fun ensureConnected(): String = connectMutex.withLock {
        lastUsedAt = System.currentTimeMillis()
        loggedInAs?.takeIf { server?.isConnected == true && server?.isClosed == false }?.let { return it }
        val (user, pass) = credentials() ?: throw LoginException("Soulseek account not set")
        if (System.currentTimeMillis() < failedUntil) throw LoginException(lastFailure ?: "unavailable")
        disconnect()
        AudioDiagnostics.info("soulseek: connecting to $SERVER_HOST:$SERVER_PORT as $user")
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(SERVER_HOST, SERVER_PORT), CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            fail("server unreachable: ${e.message}")
        }
        socket.soTimeout = 0
        server = socket
        serverOut = socket.getOutputStream()
        val login = CompletableDeferred<SlskProtocol.LoginResult>()
        pendingLogin = login
        readerJob = scope.launch { readServer(socket) }
        sendServer(SlskProtocol.login(user, pass))
        when (val result = withTimeoutOrNull(LOGIN_TIMEOUT_MS) { login.await() }) {
            is SlskProtocol.LoginResult.Success -> Unit
            is SlskProtocol.LoginResult.Failure -> {
                disconnect()
                if (result.reason == CLOSED_BEFORE_ANSWER) {
                    // What a blocked route looks like: the TCP connection opens (often through a VPN
                    // or proxy) and is dropped before the server says anything.
                    fail("the connection was closed before the server answered - Soulseek is probably blocked on this network or VPN")
                }
                fail("login refused: ${result.reason}")
            }
            null -> {
                disconnect()
                fail("login timed out")
            }
        }
        sendServer(SlskProtocol.setStatusOnline())
        sendServer(SlskProtocol.sharedFoldersFiles(0, 0))
        sendServer(SlskProtocol.haveNoParent())
        loggedInAs = user
        AudioDiagnostics.info("soulseek: logged in as $user")
        startIdleWatch()
        user
    }

    private fun startIdleWatch() {
        idleJob?.cancel()
        idleJob = scope.launch {
            var lastPing = System.currentTimeMillis()
            while (isActive && server != null) {
                delay(15_000)
                val now = System.currentTimeMillis()
                val busy = downloads.isNotEmpty()
                if (!busy && now - lastUsedAt > IDLE_DISCONNECT_MS) {
                    disconnect()
                    break
                }
                if (now - lastPing > PING_INTERVAL_MS) {
                    runCatching { sendServer(SlskProtocol.ping()) }
                    lastPing = now
                }
            }
        }
    }

    fun disconnect() {
        loggedInAs = null
        runCatching { server?.close() }
        server = null
        serverOut = null
        readerJob?.cancel()
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
    }

    private fun readServer(socket: Socket) {
        try {
            val input = DataInputStream(socket.getInputStream().buffered())
            while (!socket.isClosed) {
                val (code, payload) = readFrame(input) ?: break
                when (code) {
                    SlskProtocol.S_LOGIN -> pendingLogin?.complete(SlskProtocol.parseLogin(payload))
                    SlskProtocol.S_GET_PEER_ADDRESS -> runCatching { SlskProtocol.parsePeerAddress(payload) }
                        .getOrNull()?.let { peerAddress.remove(it.username)?.complete(it) }
                    SlskProtocol.S_CONNECT_TO_PEER -> runCatching { SlskProtocol.parseConnectToPeer(payload) }
                        .getOrNull()?.let { request -> scope.launch { answerConnectRequest(request) } }
                    else -> Unit // room lists, privileges, wishlist intervals… not needed
                }
            }
        } catch (_: IOException) {
        } finally {
            if (server === socket) {
                loggedInAs = null
                pendingLogin?.complete(SlskProtocol.LoginResult.Failure(CLOSED_BEFORE_ANSWER))
            }
        }
    }

    /** uint32 length + uint32 code + payload. */
    private fun readFrame(input: DataInputStream): Pair<Int, ByteArray>? {
        val header = ByteArray(4)
        try {
            input.readFully(header)
        } catch (_: IOException) {
            return null
        }
        val length = SlskProtocol.Reader(header).u32()
        if (length < 4 || length > MAX_MESSAGE_BYTES) throw IOException("bad frame length $length")
        val body = ByteArray(length.toInt())
        input.readFully(body)
        val code = SlskProtocol.Reader(body).i32()
        return code to body.copyOfRange(4, body.size)
    }

    // ── Peers ────────────────────────────────────────────────────────────────────────────────────

    private inner class PeerConnection(val username: String, val socket: Socket) {
        private val out = socket.getOutputStream()

        fun send(bytes: ByteArray) = synchronized(out) { out.write(bytes); out.flush() }

        fun close() {
            runCatching { socket.close() }
            peerConnections.remove(username, this)
        }

        fun readLoop() {
            try {
                val input = DataInputStream(socket.getInputStream().buffered())
                while (!socket.isClosed) {
                    val (code, payload) = readFrame(input) ?: break
                    handlePeerMessage(this, code, payload)
                }
            } catch (_: Exception) {
            } finally {
                close()
            }
        }
    }

    private fun handlePeerMessage(peer: PeerConnection, code: Int, payload: ByteArray) {
        when (code) {
            SlskProtocol.P_SEARCH_RESPONSE -> runCatching { SlskProtocol.parseSearchResponse(payload) }
                .getOrNull()?.let { response -> searches[response.token]?.add(response) }
            SlskProtocol.P_TRANSFER_REQUEST -> {
                val request = runCatching { SlskProtocol.parseTransferRequest(payload) }.getOrNull() ?: return
                if (request.direction != 1) return // we never upload
                val download = downloads.firstOrNull { it.username == peer.username && it.filename == request.filename } ?: return
                download.transferToken = request.token
                download.size = request.size
                AudioDiagnostics.info("soulseek: ${peer.username} is ready to send (${request.size} bytes)")
                runCatching { peer.send(SlskProtocol.transferResponseAllowed(request.token)) }
            }
            SlskProtocol.P_UPLOAD_FAILED, SlskProtocol.P_UPLOAD_DENIED -> {
                val filename = runCatching { SlskProtocol.Reader(payload).str() }.getOrNull() ?: return
                downloads.filter { it.username == peer.username && it.filename == filename }.forEach {
                    AudioDiagnostics.warn("soulseek: ${peer.username} refused the upload")
                    it.result.complete(null)
                }
            }
            else -> Unit
        }
    }

    /** The server relays a peer that wants to reach us: we connect to it and "pierce" with its token. */
    private fun answerConnectRequest(request: SlskProtocol.ConnectToPeer) {
        connectRequests.incrementAndGet()
        val socket = runCatching {
            Socket().apply { connect(InetSocketAddress(request.ip, request.port), CONNECT_TIMEOUT_MS) }
        }.getOrNull() ?: run {
            connectFailures.incrementAndGet()
            return
        }
        try {
            socket.getOutputStream().apply { write(SlskProtocol.pierceFirewall(request.token)); flush() }
            when (request.type) {
                "P" -> {
                    val peer = PeerConnection(request.username, socket)
                    peerConnections.putIfAbsent(request.username, peer)
                    peer.readLoop()
                }
                "F" -> {
                    AudioDiagnostics.info("soulseek: file connection from ${request.username}")
                    receiveFile(request.username, socket)
                }
                else -> socket.close()
            }
        } catch (_: Exception) {
            runCatching { socket.close() }
        }
    }

    /** File connection: the uploader sends the transfer token, we answer with the offset, then bytes follow. */
    private fun receiveFile(username: String, socket: Socket) {
        socket.use { s ->
            s.soTimeout = FILE_READ_TIMEOUT_MS
            val input = DataInputStream(s.getInputStream().buffered())
            val tokenBytes = ByteArray(4)
            input.readFully(tokenBytes)
            val token = SlskProtocol.Reader(tokenBytes).i32()
            val download = downloads.firstOrNull { it.username == username && it.transferToken == token } ?: return
            val size = download.size ?: return
            s.getOutputStream().apply { write(SlskProtocol.Writer().u64(0).bytes()); flush() }
            val tmp = File(download.target.path + ".part")
            var received = 0L
            tmp.outputStream().buffered().use { out ->
                val buf = ByteArray(64 * 1024)
                while (received < size) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), size - received).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    received += n
                    lastUsedAt = System.currentTimeMillis()
                }
            }
            if (received == size && tmp.renameTo(download.target)) {
                download.result.complete(download.target)
            } else {
                tmp.delete()
                download.result.complete(null)
            }
        }
    }

    private suspend fun peerConnection(username: String): PeerConnection? {
        peerConnections[username]?.takeIf { !it.socket.isClosed }?.let { return it }
        val me = ensureConnected()
        val deferred = CompletableDeferred<SlskProtocol.PeerAddress>()
        peerAddress[username] = deferred
        sendServer(SlskProtocol.getPeerAddress(username))
        val address = withTimeoutOrNull(PEER_ADDRESS_TIMEOUT_MS) { deferred.await() } ?: run {
            AudioDiagnostics.warn("soulseek: no address for $username (offline?)")
            return null
        }
        if (address.port <= 0 || address.ip == "0.0.0.0") {
            AudioDiagnostics.warn("soulseek: $username accepts no connections (firewalled, like us)")
            return null
        }
        val socket = runCatching {
            Socket().apply { connect(InetSocketAddress(address.ip, address.port), CONNECT_TIMEOUT_MS) }
        }.getOrElse {
            AudioDiagnostics.warn("soulseek: can't connect to $username at ${address.ip}:${address.port}: ${it.message}")
            return null
        }
        val peer = PeerConnection(username, socket)
        runCatching { peer.send(SlskProtocol.peerInit(me, "P")) }.onFailure { peer.close(); return null }
        peerConnections[username] = peer
        scope.launch { peer.readLoop() }
        return peer
    }

    // ── Public API ───────────────────────────────────────────────────────────────────────────────

    /** Searches the network and collects peer responses for [windowMs]. */
    suspend fun search(query: String, windowMs: Long): List<SlskProtocol.SearchResponse> {
        ensureConnected()
        val token = tokens.incrementAndGet()
        val collector = CopyOnWriteArrayList<SlskProtocol.SearchResponse>()
        searches[token] = collector
        val requestsBefore = connectRequests.get()
        val failuresBefore = connectFailures.get()
        try {
            sendServer(SlskProtocol.fileSearch(token, query))
            delay(windowMs)
            AudioDiagnostics.info(
                "soulseek: '$query' → ${collector.size} peer answer(s), ${collector.sumOf { it.files.size }} file(s); " +
                    "${connectRequests.get() - requestsBefore} peer(s) asked to connect, " +
                    "${connectFailures.get() - failuresBefore} unreachable",
            )
            return collector.toList()
        } finally {
            searches.remove(token)
        }
    }

    /**
     * Downloads [filename] from [username] into [target]. Returns the finished file, or null when the peer
     * is unreachable, refuses, or doesn't start within [timeoutMs].
     */
    suspend fun download(username: String, filename: String, target: File, timeoutMs: Long): File? {
        if (target.exists() && target.length() > 0) return target
        val peer = peerConnection(username) ?: run {
            AudioDiagnostics.warn("soulseek: can't reach $username (their client isn't connectable)")
            return null
        }
        val download = Download(username, filename, target)
        downloads += download
        try {
            peer.send(SlskProtocol.queueUpload(filename))
            AudioDiagnostics.info("soulseek: queued '${filename.substringAfterLast('\\')}' at $username")
            return withTimeoutOrNull(timeoutMs) { download.result.await() }.also {
                if (it == null) AudioDiagnostics.warn("soulseek: $username didn't send the file in ${timeoutMs / 1000}s")
            }
        } catch (e: Exception) {
            AudioDiagnostics.warn("soulseek: download from $username failed: ${e.message}")
            return null
        } finally {
            downloads -= download
            lastUsedAt = System.currentTimeMillis()
        }
    }

    companion object {
        const val SERVER_HOST = "server.slsknet.org"
        const val SERVER_PORT = 2242
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val LOGIN_TIMEOUT_MS = 10_000L
        private const val PEER_ADDRESS_TIMEOUT_MS = 6_000L
        private const val FILE_READ_TIMEOUT_MS = 30_000
        private const val MAX_MESSAGE_BYTES = 64L * 1024 * 1024
        private const val IDLE_DISCONNECT_MS = 5 * 60 * 1000L
        private const val PING_INTERVAL_MS = 4 * 60 * 1000L
        private const val FAILURE_BACKOFF_MS = 30_000L
        private const val CLOSED_BEFORE_ANSWER = "connection closed"
    }
}
