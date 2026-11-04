/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.friends

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Several free public Nostr relays at once: every event goes to all of them and every subscription
 * runs on all of them, so one relay being down or slow changes nothing. Nobody has to run a server.
 *
 * Connections are opened on demand and closed after [IDLE_CLOSE_MS] without subscriptions or sends,
 * so the phone doesn't hold sockets open in the background.
 */
internal object RelayPool {
    val RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
        "wss://nostr.mom",
        "wss://relay.snort.social",
        // Not behind Cloudflare: where it is cut (Russia), damus and primal don't answer and nos.lol
        // and nostr.mom are one server; these keep messages going. damus also wants a login to read
        // direct messages now.
        "wss://offchain.pub",
        "wss://nostr.oxtr.dev",
        "wss://nostr-pub.wellorder.net",
        "wss://nostr.einundzwanzig.space",
    )

    private const val IDLE_CLOSE_MS = 90_000L
    private const val BURST = 20
    private const val PER_SECOND = 8.0
    private const val ACK_KEEP_MS = 60_000L
    private const val LOG_TAG = "DrxwRelay"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private class Relay(val url: String) {
        @Volatile var socket: WebSocket? = null
        @Volatile var open = false
        val pending = java.util.concurrent.ConcurrentLinkedQueue<String>()
        /** What goes out on the open socket, paced by [Bucket] so a burst never trips a relay's rate limit. */
        val out = Channel<String>(Channel.UNLIMITED)
        val bucket = Bucket()
        /** Failed connects in a row: the next try waits longer (reset when one opens). */
        @Volatile var failures = 0
    }

    /** At most [BURST] messages at once, then [PER_SECOND] a second. */
    private class Bucket {
        private var tokens = BURST.toDouble()
        private var at = System.currentTimeMillis()

        /** How long to wait before the next message may go (0 = now, and it's taken). */
        @Synchronized
        fun take(): Long {
            val now = System.currentTimeMillis()
            tokens = minOf(BURST.toDouble(), tokens + (now - at) * PER_SECOND / 1000.0)
            at = now
            if (tokens >= 1) {
                tokens -= 1
                return 0
            }
            return ((1 - tokens) * 1000 / PER_SECOND).toLong().coerceAtLeast(1)
        }
    }

    private val relays = RELAYS.associateWith { Relay(it) }

    private class Subscription(val filters: List<JSONObject>, val onEose: (() -> Unit)?, val onEvent: (NostrEvent) -> Unit)

    /** Waiting for relays' "OK" on published events: event id → callback (once per relay). */
    private val acks = ConcurrentHashMap<String, (relay: String, ok: Boolean, reason: String) -> Unit>()

    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val seen = java.util.Collections.newSetFromMap(
        object : java.util.LinkedHashMap<String, Boolean>(512, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 4_000
        },
    )
    /** Ids of events whose signature was already checked. */
    private val verified = java.util.Collections.newSetFromMap(
        object : java.util.LinkedHashMap<String, Boolean>(512, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 4_000
        },
    )
    @Volatile private var lastUse = System.currentTimeMillis()
    private var idleJob: Job? = null

    /**
     * Sends [event] to every relay. [onAck], when given, hears each relay's answer: accepted, or
     * refused with its reason ("rate-limited: ...", "blocked: ..."). Kept for [ACK_KEEP_MS].
     */
    fun publish(event: NostrEvent, onAck: ((relay: String, ok: Boolean, reason: String) -> Unit)? = null) {
        if (onAck != null) listen(event.id, onAck)
        publishRaw(event.toJson())
    }

    /** Hears relays' answers on the event [eventId] (call before sending it). */
    fun listen(eventId: String, onAck: (relay: String, ok: Boolean, reason: String) -> Unit) {
        acks[eventId] = onAck
        scope.launch {
            delay(ACK_KEEP_MS)
            acks.remove(eventId, onAck)
        }
    }

    /** An already signed event (as JSON), sent again as it is: same id, so relays and readers take it once. */
    fun publishRaw(event: JSONObject) {
        val message = JSONArray().put("EVENT").put(event).toString()
        relays.values.forEach { send(it, message) }
    }

    /** Relays whose socket is open right now. */
    fun openCount(): Int = relays.values.count { it.open }

    /** Called when a relay's socket opens (messages that didn't go can try again then). */
    @Volatile
    var onOpen: (() -> Unit)? = null

    /**
     * The network coming back reconnects at once: after a few failures the wait had grown to
     * minutes, and messages stayed unsent long after the phone was online again.
     */
    fun watchNetwork(context: android.content.Context) {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = reconnectNow()
            })
        }
    }

    private fun reconnectNow() {
        relays.values.forEach { relay ->
            relay.failures = 0
            if (relay.socket == null && (subscriptions.isNotEmpty() || relay.pending.isNotEmpty())) connect(relay)
        }
    }

    /**
     * Runs [filters] on every relay; [onEvent] gets each valid event once. [onEose] is called each
     * time a relay has sent everything it had stored ("end of stored events"). Returns the id to close.
     */
    fun subscribe(filters: List<JSONObject>, onEvent: (NostrEvent) -> Unit): String = subscribe(filters, null, onEvent)

    fun subscribe(filters: List<JSONObject>, onEose: (() -> Unit)?, onEvent: (NostrEvent) -> Unit): String {
        val id = "drxw" + java.util.UUID.randomUUID().toString().take(8)
        subscriptions[id] = Subscription(filters, onEose, onEvent)
        val message = reqMessage(id, filters)
        relays.values.forEach { send(it, message) }
        return id
    }

    fun close(subscriptionId: String) {
        if (subscriptions.remove(subscriptionId) == null) return
        val message = JSONArray().put("CLOSE").put(subscriptionId).toString()
        relays.values.forEach { relay -> relay.socket?.takeIf { relay.open }?.send(message) }
    }

    private fun reqMessage(id: String, filters: List<JSONObject>): String =
        JSONArray().put("REQ").put(id).apply { filters.forEach { put(it) } }.toString()

    private fun send(relay: Relay, message: String) {
        lastUse = System.currentTimeMillis()
        scheduleIdleClose()
        val socket = relay.socket
        if (socket != null && relay.open) {
            relay.out.trySend(message)
            ensureWriter(relay)
            return
        }
        relay.pending += message
        if (socket == null) connect(relay)
    }

    private val writers = ConcurrentHashMap<String, Job>()

    /** One paced writer per relay: takes [Relay.out] in order, waiting for the bucket when a burst is spent. */
    private fun ensureWriter(relay: Relay) {
        if (writers[relay.url]?.isActive == true) return
        writers[relay.url] = scope.launch {
            for (message in relay.out) {
                while (true) {
                    val wait = relay.bucket.take()
                    if (wait == 0L) break
                    delay(wait)
                }
                val socket = relay.socket
                if (socket != null && relay.open) socket.send(message) else relay.pending += message
            }
        }
    }

    private fun connect(relay: Relay) {
        relay.socket = client.newWebSocket(
            Request.Builder().url(relay.url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    relay.open = true
                    relay.failures = 0
                    // Subscriptions that started while this relay was away run here too.
                    val queued = generateSequence { relay.pending.poll() }.toList()
                    val reqIds = queued.mapNotNull { runCatching { JSONArray(it) }.getOrNull() }
                        .filter { it.optString(0) == "REQ" }.map { it.optString(1) }.toSet()
                    subscriptions.forEach { (id, sub) -> if (id !in reqIds) relay.out.trySend(reqMessage(id, sub.filters)) }
                    queued.forEach { relay.out.trySend(it) }
                    ensureWriter(relay)
                    onOpen?.let { runCatching { it() } }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handle(relay, text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(relay, webSocket)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Timber.d("Relay %s failed: %s", relay.url, t.message)
                    relay.failures++
                    dropped(relay, webSocket)
                }
            },
        )
    }

    private fun dropped(relay: Relay, webSocket: WebSocket) {
        if (relay.socket !== webSocket) return
        relay.socket = null
        relay.open = false
        // Live subscriptions come back after a pause; with none, the relay just stays closed.
        if (subscriptions.isNotEmpty()) {
            // 10 s, then longer after each failure in a row (up to 5 min), a little random so the
            // phone doesn't knock on every relay at the same instant.
            val wait = (10_000L shl relay.failures.coerceAtMost(5)).coerceAtMost(300_000L)
            scope.launch {
                delay(wait + (0..wait / 5).random())
                if (relay.socket == null && subscriptions.isNotEmpty()) connect(relay)
            }
        }
    }

    private fun handle(relay: Relay, text: String) {
        val message = runCatching { JSONArray(text) }.getOrNull() ?: return
        when (message.optString(0)) {
            "EVENT" -> Unit
            "OK" -> {
                val ok = message.optBoolean(2)
                val reason = message.optString(3)
                if (!ok) android.util.Log.i(LOG_TAG, "${relay.url} refused ${message.optString(1).take(8)}: $reason")
                acks[message.optString(1)]?.let { runCatching { it(relay.url, ok, reason) } }
                return
            }
            "EOSE" -> {
                subscriptions[message.optString(1)]?.onEose?.let { runCatching { it() } }
                return
            }
            "NOTICE", "CLOSED" -> {
                android.util.Log.i(LOG_TAG, "${relay.url} ${message.optString(0)}: ${message.optString(message.length() - 1)}")
                return
            }
            else -> return
        }
        val subId = message.optString(1)
        val sub = subscriptions[subId] ?: return
        val event = message.optJSONObject(2)?.let(NostrEvent::fromJson) ?: return
        synchronized(seen) { if (!seen.add(subId + event.id)) return }
        // Every relay sends the same events, and again on each new subscription (each opening of
        // the Friends screen): the signature is checked once per event, then the id hash suffices.
        if (!event.hasValidId()) return
        val known = synchronized(verified) { event.id in verified }
        if (!known) {
            if (!event.hasValidSignature()) return
            synchronized(verified) { verified.add(event.id) }
        }
        lastUse = System.currentTimeMillis()
        runCatching { sub.onEvent(event) }.onFailure { Timber.w(it, "Relay event handler failed") }
    }

    private fun scheduleIdleClose() {
        if (idleJob?.isActive == true) return
        idleJob = scope.launch {
            while (true) {
                delay(IDLE_CLOSE_MS / 3)
                if (subscriptions.isEmpty() && System.currentTimeMillis() - lastUse > IDLE_CLOSE_MS) {
                    relays.values.forEach { relay ->
                        relay.socket?.close(1000, null)
                        relay.socket = null
                        relay.open = false
                        relay.pending.clear()
                    }
                    break
                }
            }
        }
    }
}
