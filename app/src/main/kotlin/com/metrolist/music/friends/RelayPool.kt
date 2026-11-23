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
        "wss://purplerelay.com",
        // Not behind Cloudflare: where it is cut (Russia), damus and primal don't answer and nos.lol
        // and nostr.mom are one server; these keep messages going. damus also wants a login to read
        // direct messages now. Checked 2026-11-24: each takes a new key's direct messages and app
        // data and gives them back. Dropped then: offchain.pub (refuses keys outside its "web of
        // trust"), relay.noswhere.com (takes events, never gives them back), nostr-pub.wellorder.net
        // and relay.nostr.bg (gone). nostr.land is paid.
        "wss://relay.snort.social",
        "wss://relay.angor.io",
        "wss://nostr-01.yakihonne.com",
        "wss://relay.nostr.wirednet.jp",
    )

    private const val IDLE_CLOSE_MS = 90_000L
    private const val BURST = 12
    private const val PER_SECOND = 5.0
    private const val ACK_KEEP_MS = 60_000L
    /** A socket still not open after this long is given up and dialled again. */
    private const val CONNECT_STUCK_MS = 30_000L
    private const val MAX_PENDING = 300
    /** "banned": nothing goes to that relay for this long (talking to it only lengthens the ban). */
    private const val BANNED_MUTE_MS = 60 * 60_000L
    /** It won't store our events (quota spent, a whitelist, payment): reads go on, writes stop. */
    private const val WRITES_MUTE_MS = 6 * 60 * 60_000L
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
        /** Subscriptions this relay closed asking for authentication: sent again once it is done. */
        val waitingForAuth = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        /** The AUTH event sent to this relay (its "OK" means the relay knows who we are now). */
        @Volatile var authEventId: String? = null
        @Volatile var challenge: String? = null
        /** When the current socket was dialled (it may still be connecting). */
        @Volatile var connectingSince = 0L
        /** Until then this relay gets nothing at all (it banned us). */
        @Volatile var mutedUntil = 0L
        /** Until then our events don't go to it (it refuses to store them); subscriptions still run. */
        @Volatile var writesMutedUntil = 0L
        /** Until then the writer holds (it said "rate-limited"). */
        @Volatile var slowUntil = 0L
        /** "rate-limited" answers in a row: each holds the writer longer. */
        @Volatile var rateHits = 0
        /** A reconnect is already waiting for the mute to end. */
        @Volatile var wakeScheduled = false
    }

    /**
     * Signs the relay's authentication event (NIP-42, kind 22242) with this profile's key; set by
     * FriendsHub. Some relays (relay.damus.io) now answer "auth-required" to the friends' data
     * queries — profiles with their avatars among them — and send nothing until the client says who it is.
     */
    @Volatile
    var authSigner: ((relay: String, challenge: String) -> NostrEvent?)? = null

    private fun authenticate(relay: Relay) {
        val challenge = relay.challenge ?: return
        val event = authSigner?.invoke(relay.url, challenge) ?: return
        relay.authEventId = event.id
        relay.socket?.takeIf { relay.open }?.send(JSONArray().put("AUTH").put(event.toJson()).toString())
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
        val now = System.currentTimeMillis()
        relays.values.forEach { if (now >= it.writesMutedUntil) send(it, message) }
    }

    /** Relays whose socket is open right now. */
    fun openCount(): Int = relays.values.count { it.open }

    /** Each relay's state in a few words, for the log when a message found no relay. */
    fun describe(): String {
        val now = System.currentTimeMillis()
        return relays.values.joinToString(", ") { r ->
            val name = r.url.removePrefix("wss://")
            val state = when {
                now < r.mutedUntil -> "muted ${(r.mutedUntil - now) / 60_000}m"
                r.open -> "open"
                r.socket != null -> "dialling ${(now - r.connectingSince) / 1000}s"
                else -> "closed f=${r.failures}"
            }
            val extra = buildString {
                if (now < r.writesMutedUntil) append(" no-writes ${(r.writesMutedUntil - now) / 60_000}m")
                if (now < r.slowUntil) append(" slow")
                if (r.pending.isNotEmpty()) append(" q=${r.pending.size}")
            }
            "$name:$state$extra"
        }
    }

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
            if (subscriptions.isEmpty() && relay.pending.isEmpty()) return@forEach
            if (relay.socket == null || stuck(relay)) connect(relay)
        }
    }

    /** Dialled long ago and still not open: the attempt is lost (it would otherwise hold the relay forever). */
    private fun stuck(relay: Relay): Boolean =
        relay.socket != null && !relay.open && System.currentTimeMillis() - relay.connectingSince > CONNECT_STUCK_MS

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
        // A relay away for long doesn't hoard everything since (the outbox sends chats again anyway).
        while (relay.pending.size > MAX_PENDING) relay.pending.poll()
        if (socket == null || stuck(relay)) connect(relay)
    }

    private val writers = ConcurrentHashMap<String, Job>()

    /** One paced writer per relay: takes [Relay.out] in order, waiting for the bucket when a burst is spent. */
    private fun ensureWriter(relay: Relay) {
        if (writers[relay.url]?.isActive == true) return
        writers[relay.url] = scope.launch {
            for (message in relay.out) {
                val hold = relay.slowUntil - System.currentTimeMillis()
                if (hold > 0) delay(hold)
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

    /**
     * Dials [relay]. The socket is stored under the relay's lock and every callback checks it under
     * the same lock: a dial that failed at once (no network, an unknown host) used to report the
     * failure before newWebSocket had returned, the stale check threw the report away, and the dead
     * socket stayed as the relay's forever: every message for it waited in [Relay.pending], and on
     * a phone up for hours chats stopped going anywhere until the app was restarted.
     */
    private fun connect(relay: Relay) {
        val now = System.currentTimeMillis()
        if (now < relay.mutedUntil) {
            wakeAfterMute(relay)
            return
        }
        synchronized(relay) {
            relay.socket?.cancel()
            relay.open = false
            relay.connectingSince = now
            relay.socket = client.newWebSocket(Request.Builder().url(relay.url).build(), listenerFor(relay))
        }
    }

    /** One reconnect when the relay's ban is over (if anything still wants it then). */
    private fun wakeAfterMute(relay: Relay) {
        if (relay.wakeScheduled) return
        relay.wakeScheduled = true
        scope.launch {
            delay((relay.mutedUntil - System.currentTimeMillis()).coerceAtLeast(1_000L))
            relay.wakeScheduled = false
            if (relay.socket == null && (subscriptions.isNotEmpty() || relay.pending.isNotEmpty())) connect(relay)
        }
    }

    private fun current(relay: Relay, webSocket: WebSocket) = synchronized(relay) { relay.socket === webSocket }

    private fun listenerFor(relay: Relay) = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!current(relay, webSocket)) {
                        webSocket.close(1000, null)
                        return
                    }
                    relay.open = true
                    relay.challenge = null
                    relay.authEventId = null
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

                // The relay closes: we close too (without it the socket lingered half-closed, looking open).
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    dropped(relay, webSocket)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(relay, webSocket)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Timber.d("Relay %s failed: %s", relay.url, t.message)
                    if (current(relay, webSocket)) relay.failures++
                    dropped(relay, webSocket)
                }
            }

    private fun dropped(relay: Relay, webSocket: WebSocket) {
        synchronized(relay) {
            if (relay.socket !== webSocket) return
            relay.socket = null
            relay.open = false
        }
        // Live subscriptions (or messages still waiting) come back after a pause; with neither,
        // the relay just stays closed.
        if (subscriptions.isNotEmpty() || relay.pending.isNotEmpty()) {
            // 10 s, then longer after each failure in a row (up to 5 min), a little random so the
            // phone doesn't knock on every relay at the same instant.
            val wait = (10_000L shl relay.failures.coerceAtMost(5)).coerceAtMost(300_000L)
            scope.launch {
                delay(wait + (0..wait / 5).random())
                if (relay.socket == null && (subscriptions.isNotEmpty() || relay.pending.isNotEmpty())) connect(relay)
            }
        }
    }

    /**
     * What a relay's refusal means for us. "banned" stops everything to it for an hour; a relay
     * that won't store our events (quota spent, a web of trust, a whitelist, payment) stops getting
     * them for hours; "rate-limited" holds its writer, longer each time in a row. Before, the phone
     * went on sending at full pace into the limits and got itself banned.
     */
    private fun refused(relay: Relay, reason: String) {
        val r = reason.lowercase()
        val now = System.currentTimeMillis()
        when {
            r.startsWith("banned") -> {
                relay.mutedUntil = now + BANNED_MUTE_MS
                relay.pending.clear()
                synchronized(relay) { relay.socket }?.close(1000, null)
            }
            "quota" in r || "tomorrow" in r || "web of trust" in r || "white-list" in r || "whitelist" in r ||
                r.startsWith("restricted") || "pay " in r || "sign up" in r ->
                relay.writesMutedUntil = now + WRITES_MUTE_MS
            r.startsWith("rate-limited") || r.startsWith("error: rate-limited") -> slowDown(relay, r)
        }
    }

    private fun slowDown(relay: Relay, reason: String) {
        val asked = Regex("retry in (\\d+)\\s*ms").find(reason)?.groupValues?.get(1)?.toLongOrNull()
        // Its own "retry in" is a floor, not a promise: asking again at once got the phone banned.
        val backoff = (5_000L shl relay.rateHits.coerceAtMost(5)).coerceAtMost(120_000L)
        val hold = maxOf(asked ?: 0L, backoff)
        relay.rateHits++
        relay.slowUntil = maxOf(relay.slowUntil, System.currentTimeMillis() + hold)
    }

    private fun handle(relay: Relay, text: String) {
        val message = runCatching { JSONArray(text) }.getOrNull() ?: return
        when (message.optString(0)) {
            "EVENT" -> Unit
            "AUTH" -> {
                relay.challenge = message.optString(1).takeIf { it.isNotBlank() }
                if (relay.waitingForAuth.isNotEmpty()) authenticate(relay)
                return
            }
            "OK" -> {
                val ok = message.optBoolean(2)
                val reason = message.optString(3)
                // Authenticated: what this relay refused before is asked for again.
                if (message.optString(1) == relay.authEventId) {
                    android.util.Log.i(LOG_TAG, "${relay.url} auth ${if (ok) "ok" else "refused: $reason"}")
                    if (ok) {
                        val again = relay.waitingForAuth.toList()
                        relay.waitingForAuth.clear()
                        again.forEach { id -> subscriptions[id]?.let { sub -> relay.out.trySend(reqMessage(id, sub.filters)) } }
                        ensureWriter(relay)
                    }
                    return
                }
                if (ok) {
                    relay.rateHits = 0
                } else {
                    android.util.Log.i(LOG_TAG, "${relay.url} refused ${message.optString(1).take(8)}: $reason")
                    refused(relay, reason)
                }
                acks[message.optString(1)]?.let { runCatching { it(relay.url, ok, reason) } }
                return
            }
            "EOSE" -> {
                subscriptions[message.optString(1)]?.onEose?.let { runCatching { it() } }
                return
            }
            "NOTICE", "CLOSED" -> {
                val text = message.optString(message.length() - 1)
                android.util.Log.i(LOG_TAG, "${relay.url} ${message.optString(0)}: $text")
                // damus writes "ERROR: auth-required: ..." / "ERROR: rate-limited: ...".
                val said = text.removePrefix("ERROR: ").removePrefix("error: ")
                val closed = message.optString(0) == "CLOSED"
                // "auth-required": the client says who it is (with the challenge the relay sent), then asks again.
                if (closed && said.startsWith("auth-required")) {
                    relay.waitingForAuth += message.optString(1)
                    authenticate(relay)
                } else if (said.startsWith("rate-limited") || said.startsWith("banned")) {
                    refused(relay, said)
                    // A subscription it cut for reading too much is asked for once more after the pause.
                    val subId = message.optString(1)
                    if (closed && said.startsWith("rate-limited")) scope.launch {
                        delay((relay.slowUntil - System.currentTimeMillis()).coerceAtLeast(1_000L))
                        val sub = subscriptions[subId] ?: return@launch
                        if (relay.open) {
                            relay.out.trySend(reqMessage(subId, sub.filters))
                            ensureWriter(relay)
                        }
                    }
                }
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
                        synchronized(relay) {
                            relay.socket?.close(1000, null)
                            relay.socket = null
                            relay.open = false
                        }
                        relay.pending.clear()
                    }
                    break
                }
            }
        }
    }
}
