/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.friends

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    )

    private const val IDLE_CLOSE_MS = 90_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private class Relay(val url: String) {
        @Volatile var socket: WebSocket? = null
        @Volatile var open = false
        val pending = java.util.concurrent.ConcurrentLinkedQueue<String>()
    }

    private val relays = RELAYS.associateWith { Relay(it) }

    private class Subscription(val filters: List<JSONObject>, val onEvent: (NostrEvent) -> Unit)

    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val seen = java.util.Collections.newSetFromMap(
        object : java.util.LinkedHashMap<String, Boolean>(512, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 4_000
        },
    )
    @Volatile private var lastUse = System.currentTimeMillis()
    private var idleJob: Job? = null

    fun publish(event: NostrEvent) {
        val message = JSONArray().put("EVENT").put(event.toJson()).toString()
        relays.values.forEach { send(it, message) }
    }

    /** Runs [filters] on every relay; [onEvent] gets each valid event once. Returns the id to close. */
    fun subscribe(filters: List<JSONObject>, onEvent: (NostrEvent) -> Unit): String {
        val id = "drxw" + java.util.UUID.randomUUID().toString().take(8)
        subscriptions[id] = Subscription(filters, onEvent)
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
            socket.send(message)
            return
        }
        relay.pending += message
        if (socket == null) connect(relay)
    }

    private fun connect(relay: Relay) {
        relay.socket = client.newWebSocket(
            Request.Builder().url(relay.url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    relay.open = true
                    // Subscriptions that started while this relay was away run here too.
                    val queued = generateSequence { relay.pending.poll() }.toList()
                    val reqIds = queued.mapNotNull { runCatching { JSONArray(it) }.getOrNull() }
                        .filter { it.optString(0) == "REQ" }.map { it.optString(1) }.toSet()
                    subscriptions.forEach { (id, sub) -> if (id !in reqIds) webSocket.send(reqMessage(id, sub.filters)) }
                    queued.forEach { webSocket.send(it) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handle(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(relay, webSocket)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Timber.d("Relay %s failed: %s", relay.url, t.message)
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
            scope.launch {
                delay(10_000L)
                if (relay.socket == null && subscriptions.isNotEmpty()) connect(relay)
            }
        }
    }

    private fun handle(text: String) {
        val message = runCatching { JSONArray(text) }.getOrNull() ?: return
        if (message.optString(0) != "EVENT") return
        val subId = message.optString(1)
        val sub = subscriptions[subId] ?: return
        val event = message.optJSONObject(2)?.let(NostrEvent::fromJson) ?: return
        synchronized(seen) { if (!seen.add(subId + event.id)) return }
        if (!event.isValid()) return
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
