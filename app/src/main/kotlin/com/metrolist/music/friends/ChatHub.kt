/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.friends

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Chats with friends, over the same public Nostr relays as the rest of Friends: every message is
 * an encrypted direct message (kind 4, the type "drxw-chat" inside), so nobody runs a server and
 * the relays see who writes to whom, but not what.
 *
 * Relays promise to keep nothing, so the history is [ChatDatabase] on the phone. Messages come in
 * live while the app is open or music plays ([hold]); otherwise an inexact alarm looks for new ones
 * every half hour or so ([ChatAlarmReceiver]) and shows a notification.
 *
 * Only friends can write (the developer to and from anyone): a friend's messages are asked for by
 * their key, so a stranger's can't even arrive.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
object ChatHub {
    /**
     * Sent in the hello and in every payload: this version has chats (a higher number = a newer chat
     * format). 2: reactions.
     */
    const val PROTOCOL = 2

    /** The first protocol with reactions. */
    const val PROTOCOL_REACTIONS = 2

    private const val TYPE = "drxw-chat"
    /**
     * Reactions travel as direct messages of their own type: a 2.2.1 app drops any type it doesn't
     * know without a trace (a new "k" under [TYPE] would show there as an "unknown message").
     */
    private const val TYPE_REACT = "drxw-react"
    private const val KIND_DM = 4
    private const val KIND_DELETE = 5
    /** Ephemeral (20000–29999): relays pass it on and keep nothing. */
    private const val KIND_TYPING = 21004
    private const val LOG_TAG = "DrxwChat"

    private const val K_MSG = "msg"
    private const val K_EDIT = "edit"
    private const val K_DEL = "del"
    private const val K_READ = "read"
    private const val K_TYPING = "typing"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var database: ChatDatabase? = null

    private val dao get() = database?.dao
    private fun me(): String? = FriendsHub.profile.value?.pubkey

    /** How many messages were written and received here (statistics). */
    suspend fun messageCounts(): Pair<Int, Int> {
        val me = me() ?: return 0 to 0
        val d = dao ?: return 0 to 0
        return runCatching { d.countMessages(me, true) to d.countMessages(me, false) }.getOrDefault(0 to 0)
    }

    // ── Peers: did they add me, and does their app have chats ────────────────────────────────────

    /** [protocol] 0 = no chats in their app; [version] their app's version, when it says. */
    data class PeerStatus(val addedMe: Boolean, val protocol: Int, val version: String? = null) {
        val hasChat: Boolean get() = protocol > 0
        val hasReactions: Boolean get() = protocol >= PROTOCOL_REACTIONS
    }

    private val _peers = MutableStateFlow<Map<String, PeerStatus>>(emptyMap())
    /** What is known about friends (from their hello to this profile); missing = not looked up yet. */
    val peers: StateFlow<Map<String, PeerStatus>> = _peers

    /** Friends typing to me right now: key → until when (elapsed ms). */
    private val _typing = MutableStateFlow<Map<String, Long>>(emptyMap())
    val typing: StateFlow<Map<String, Long>> = _typing

    /** The conversation on screen (its messages are read as they come, and it gets no notification). */
    private val _open = MutableStateFlow<String?>(null)

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        prefs = app.getSharedPreferences("chat", Context.MODE_PRIVATE)
        database = ChatDatabase.open(app)
        Stickers.init(app)
        ChatReactions.init(app)
        _peers.value = loadPeers()
        previewList
        scheduleCheck(app)
        // A relay reachable again (network back, a relay up): what didn't go goes now, as in Telegram.
        RelayPool.watchNetwork(app)
        RelayPool.onOpen = ::retrySoon
        if (me() != null) scope.launch {
            delay(12_000L)
            refreshPeers()
            retryOutbox()
        }
    }

    fun previews(): Flow<List<ChatPreview>> =
        FriendsHub.profile.flatMapLatest { p -> p?.let { dao?.previews(it.pubkey) } ?: emptyFlow() }

    /**
     * The chats list, kept up to date from the start: opening "Chats" (or coming back to it) draws
     * it at once instead of an empty frame first. Null until the database first answers.
     */
    val previewList: StateFlow<List<ChatPreview>?> by lazy { previews().stateIn(scope, SharingStarted.Eagerly, null) }

    private val lastMessages = java.util.concurrent.ConcurrentHashMap<String, List<ChatMessage>>()

    /** A chat's messages as last seen, to draw at once when it is opened again; null before the first look. */
    fun cachedMessages(peer: String): List<ChatMessage>? = lastMessages[peer]

    /** A chat's reactions: message id → everyone's reactions to it. */
    fun reactions(peer: String): Flow<Map<String, List<ChatReaction>>> =
        FriendsHub.profile.flatMapLatest { p -> p?.let { dao?.reactions(it.pubkey, peer) } ?: emptyFlow() }
            .map { list -> list.groupBy { it.msgId } }
            .flowOn(Dispatchers.Default)

    fun messages(peer: String): Flow<List<ChatMessage>> =
        FriendsHub.profile.flatMapLatest { p -> p?.let { dao?.messages(it.pubkey, peer) } ?: emptyFlow() }
            .onEach { lastMessages[peer] = it }

    fun unreadTotal(): Flow<Int> =
        FriendsHub.profile.flatMapLatest { p -> p?.let { dao?.unreadTotal(it.pubkey) } ?: emptyFlow() }

    /** Who this profile can write to: friends, plus (on the developer's phone) whoever wrote. */
    fun canWrite(peer: String): Boolean =
        FriendsHub.isDeveloper(peer) || FriendsHub.profile.value?.isDeveloper == true ||
            FriendsHub.friends.value.any { it.pubkey == peer }

    // ── Staying connected ────────────────────────────────────────────────────────────────────────

    private val holders = mutableSetOf<String>()
    private var liveSub: String? = null
    private var liveKeys: Set<String>? = null
    private var releaseJob: Job? = null

    /**
     * Messages arrive live while anything holds the chats: the app on screen ("ui"), music playing
     * ("playing"). A few minutes after the last one lets go, the connection closes.
     */
    @Synchronized
    fun hold(reason: String, on: Boolean) {
        val changed = if (on) holders.add(reason) else holders.remove(reason)
        if (!changed) return
        if (holders.isNotEmpty()) {
            releaseJob?.cancel()
            releaseJob = null
            openLive()
        } else {
            releaseJob?.cancel()
            releaseJob = scope.launch {
                delay(RELEASE_MS)
                synchronized(this@ChatHub) { if (holders.isEmpty()) closeLive() }
            }
        }
    }

    /** Friends whose messages are asked for (null = anyone's: the developer's phone). */
    private fun senders(): Set<String>? {
        if (FriendsHub.profile.value?.isDeveloper == true) return null
        return (FriendsHub.friends.value.map { it.pubkey } + FriendsHub.DEVELOPER_PUBKEY).toSet()
    }

    private fun inboxFilter(me: String, since: Long): JSONObject {
        val filter = JSONObject()
            .put("kinds", JSONArray().put(KIND_DM))
            .put("#p", JSONArray().put(me))
            .put("since", since)
        senders()?.let { filter.put("authors", JSONArray(it.toList())) }
        return filter
    }

    /** Where the last look stopped (s), a little earlier so nothing that came late is missed. */
    private fun since(): Long {
        val cursor = prefs?.getLong("cursor_${me()}", 0L) ?: 0L
        val now = System.currentTimeMillis() / 1000
        return if (cursor == 0L) now - FIRST_LOOK_S else minOf(cursor, now) - OVERLAP_S
    }

    private fun advanceCursor(createdAt: Long) {
        val key = "cursor_${me()}"
        val p = prefs ?: return
        val now = System.currentTimeMillis() / 1000
        if (createdAt > p.getLong(key, 0L) && createdAt <= now + 60) p.edit().putLong(key, createdAt).apply()
    }

    @Synchronized
    private fun openLive() {
        val me = me() ?: return
        val keys = senders() ?: emptySet()
        if (liveSub != null && liveKeys == keys) return
        closeLive()
        liveKeys = keys
        liveSub = RelayPool.subscribe(listOf(inboxFilter(me, since())), null) { onEvent(it) }
        scope.launch { retryOutbox() }
    }

    @Synchronized
    private fun closeLive() {
        liveSub?.let(RelayPool::close)
        liveSub = null
        liveKeys = null
    }

    /** Friends changed (added, removed): the live inbox asks for the new list. */
    fun friendsChanged() {
        synchronized(this) { if (liveSub != null) openLive() }
        scope.launch { refreshPeers() }
    }

    /**
     * One look for new messages without staying connected (the background check): until two
     * relays said they sent everything, or [timeoutMs].
     */
    suspend fun checkOnce(timeoutMs: Long = CHECK_TIMEOUT_MS) {
        val me = me() ?: return
        if (liveSub != null) return
        var ends = 0
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val sub = RelayPool.subscribe(listOf(inboxFilter(me, since())), {
            synchronized(done) { ends++; if (ends >= 2) done.complete(Unit) }
        }) { onEvent(it) }
        withTimeoutOrNull(timeoutMs) { done.await() }
        // A moment for the last events to be stored.
        delay(500)
        RelayPool.close(sub)
    }

    // ── Receiving ────────────────────────────────────────────────────────────────────────────────

    private fun onEvent(event: NostrEvent) {
        val me = me() ?: return
        val sk = FriendsHub.secretKeyOrNull() ?: return
        val author = event.pubkey
        if (author == me || event.tag("p") != me) return
        val plain = Nostr.decrypt(sk, author, event.content) ?: run {
            Log.i(LOG_TAG, "can't decrypt ${event.id.take(8)} from ${author.take(8)}")
            return
        }
        val o = runCatching { JSONObject(plain) }.getOrNull() ?: return
        val type = o.optString("t")
        if (type != TYPE && type != TYPE_REACT) return
        notePeerProtocol(author, o.optInt("v"))
        if (type == TYPE_REACT) {
            scope.launch {
                runCatching { applyReaction(me, author, event, o) }.onFailure { Log.w(LOG_TAG, "react apply failed", it) }
                advanceCursor(event.createdAt)
            }
            return
        }
        if (event.kind == KIND_TYPING) {
            if (o.optString("k") == K_TYPING) {
                _typing.value = _typing.value + (author to SystemClock.elapsedRealtime() + TYPING_SHOWN_MS)
            }
            return
        }
        scope.launch {
            runCatching { apply(me, author, event, o) }.onFailure { Log.w(LOG_TAG, "apply failed", it) }
            advanceCursor(event.createdAt)
        }
    }

    private suspend fun apply(me: String, author: String, event: NostrEvent, o: JSONObject) {
        val dao = dao ?: return
        val id = o.optString("id").takeIf { it.isNotBlank() && it.length <= 64 } ?: return
        // The sender's clock, but never later than a minute after the relay got it.
        val ts = o.optLong("ts").takeIf { it > 0 }?.coerceAtMost(event.createdAt * 1000 + 60_000) ?: (event.createdAt * 1000)
        when (o.optString("k")) {
            K_MSG -> {
                // They're writing, so they aren't typing any more.
                _typing.value = _typing.value - author
                val reading = _open.value == author && FriendsHub.profile.value != null && appVisible
                val message = ChatMessage(
                    owner = me, peer = author, id = id, mine = false, ts = ts,
                    text = o.optString("text").take(MAX_TEXT),
                    music = o.optJSONObject("m")?.let(ChatMusic::fromJson)?.toJson()?.toString(),
                    replyTo = o.optString("re").takeIf { it.isNotBlank() },
                    state = ChatMessage.STATE_RECEIVED,
                    read = reading,
                )
                if (dao.insert(message) == -1L) return
                if (reading) sendRead(author, ts) else notifyNew()
            }
            K_EDIT -> {
                val text = o.optString("text").take(MAX_TEXT)
                val old = dao.get(me, author, id)
                when {
                    old == null -> dao.insert(ChatMessage(me, author, id, mine = false, ts = ts, text = text, edited = true))
                    !old.mine && !old.deleted -> dao.update(old.copy(text = text, edited = true))
                }
            }
            K_DEL -> {
                val old = dao.get(me, author, id)
                dao.dropReactions(me, author, id)
                when {
                    old == null -> dao.insert(ChatMessage(me, author, id, mine = false, ts = ts, text = "", deleted = true))
                    !old.mine -> {
                        dao.update(old.copy(text = "", music = null, deleted = true))
                        notifyNew()
                    }
                }
            }
            K_READ -> dao.markMineRead(me, author, o.optLong("upTo"))
            // A newer app's message: shown as such rather than dropped silently — unless it has no
            // text to show (a newer app's own bookkeeping), which is left out.
            else -> {
                if (o.optString("text").isBlank()) {
                    Log.i(LOG_TAG, "skipped unknown k=${o.optString("k").take(12)} from ${author.take(8)}")
                    return
                }
                val message = ChatMessage(
                    owner = me, peer = author, id = id, mine = false, ts = ts,
                    text = o.optString("text").ifBlank { appContext?.getString(R.string.chat_unknown_message).orEmpty() },
                )
                if (dao.insert(message) != -1L) notifyNew()
            }
        }
    }

    // ── Sending ──────────────────────────────────────────────────────────────────────────────────

    private fun payload(kind: String, id: String) = JSONObject().put("t", TYPE).put("v", PROTOCOL).put("k", kind).put("id", id)

    private fun sign(peer: String, kind: Int, content: JSONObject, extraTags: List<List<String>> = emptyList()): NostrEvent? {
        val sk = FriendsHub.secretKeyOrNull() ?: return null
        val encrypted = Nostr.encrypt(sk, peer, content.toString())
        return Nostr.sign(sk, kind, listOf(listOf("p", peer)) + extraTags, encrypted)
    }

    /**
     * Sends a message: [text] as typed (sticker codes become stickers), [music] as a card. A link to
     * a track, album or playlist in the text becomes its card (and leaves the text).
     */
    fun send(peer: String, text: String, music: ChatMusic? = null, replyTo: String? = null) =
        sendAll(peer, text, listOfNotNull(music), replyTo)

    /**
     * Several cards at once (picked in the music panel, or forwarded): one message each, in order,
     * the text (if any) with the first. Sent one after another so they arrive in this order.
     */
    fun sendAll(peer: String, text: String, music: List<ChatMusic>, replyTo: String? = null) {
        var body = text.trim()
        val cards = music.toMutableList()
        if (cards.isEmpty()) {
            ChatMusic.findLink(body)?.let { (range, target) ->
                cards += ChatMusic(target.kind, target.id, target.title.orEmpty(), target.by.orEmpty())
                body = body.removeRange(range).trim()
            }
        }
        val items = if (cards.isEmpty()) {
            listOf(Outgoing(body, null))
        } else {
            cards.mapIndexed { i, card -> Outgoing(if (i == 0) body else "", card) }
        }
        sendItems(peer, items, replyTo)
    }

    /** One message per item, in order; [replyTo] goes with the first. */
    fun sendItems(peer: String, items: List<Outgoing>, replyTo: String? = null) {
        if (me() == null) return
        val list = items.filter { it.text.isNotBlank() || it.music != null }
        if (list.isEmpty()) return
        scope.launch {
            sendLock.withLock {
                // Cards without a cover (a bare link, a forwarded album) are looked up before any
                // goes, so the batch leaves together and shows together.
                val ready = list.map { item ->
                    val m = item.music
                    if (m != null && m.cover == null) {
                        item.copy(music = ChatMusic.resolve(com.metrolist.music.utils.AppLinks.Target(m.kind, m.id, m.title, m.by)))
                    } else {
                        item
                    }
                }
                ready.forEachIndexed { i, item -> sendOne(peer, item.text.trim(), item.music, if (i == 0) replyTo else null) }
            }
        }
    }

    /** A message to send: text (stickers in it), a card, or both. */
    data class Outgoing(val text: String, val music: ChatMusic?)

    private val sendLock = kotlinx.coroutines.sync.Mutex()
    private var lastSentTs = 0L

    private suspend fun sendOne(peer: String, body: String, card: ChatMusic?, replyTo: String?) {
        val me = me() ?: return
        val id = UUID.randomUUID().toString()
        // Strictly later than the previous one, so cards sent together keep their order.
        val ts = maxOf(System.currentTimeMillis(), lastSentTs + 1).also { lastSentTs = it }
        // On screen first (the encryption takes a moment on a slow phone), then signed and sent.
        val draft = ChatMessage(
            owner = me, peer = peer, id = id, mine = true, ts = ts,
            text = Stickers.clean(body.take(MAX_TEXT)), music = card?.toJson()?.toString(), replyTo = replyTo,
            state = ChatMessage.STATE_SENDING, read = false,
        )
        dao?.insert(draft)
        val event = signMessage(draft) ?: return
        val message = draft.copy(event = event.toJson().toString(), eventId = event.id)
        dao?.update(message)
        deliver(message, event.toJson())
    }

    /** The signed event of one of my messages, as it goes out. */
    private fun signMessage(m: ChatMessage): NostrEvent? {
        val content = payload(K_MSG, m.id).put("ts", m.ts).put("text", m.text)
        ChatMusic.fromJson(m.music)?.let { content.put("m", it.toJson()) }
        m.replyTo?.let { content.put("re", it) }
        return sign(m.peer, KIND_DM, content)
    }

    // ── Forwarding (from a share button elsewhere in the app) ────────────────────────────────────

    private val _forward = MutableStateFlow<List<Outgoing>?>(null)
    /**
     * What waits to be sent to a chat the user picks ("Forward…"): music from a share button, or
     * messages picked in a chat (sent as new messages, without who wrote them). Null when nothing waits.
     */
    val forward: StateFlow<List<Outgoing>?> = _forward

    fun startForward(music: List<ChatMusic>) {
        _forward.value = music.map { Outgoing("", it) }.takeIf { it.isNotEmpty() }
    }

    fun startForwardMessages(messages: List<ChatMessage>) {
        _forward.value = messages.sortedBy { it.ts }
            .map { Outgoing(it.text, ChatMusic.fromJson(it.music)) }
            .filter { it.text.isNotBlank() || it.music != null }
            .takeIf { it.isNotEmpty() }
    }

    fun cancelForward() {
        _forward.value = null
    }

    /** A chat's sticker panel is open: the mini player steps down out of its way (as the keyboard covers it). */
    val stickerPanelOpen = MutableStateFlow(false)

    /** A chat's music panel is open: the mini player steps away for it as well. */
    val musicPanelOpen = MutableStateFlow(false)

    // ── The keyboard's height (the sticker panel takes the same) ─────────────────────────────────

    var keyboardHeightPx: Int
        get() = prefs?.getInt("keyboard", 0) ?: 0
        set(value) {
            prefs?.edit()?.putInt("keyboard", value)?.apply()
        }

    /** Messages on their way (one delivery at a time: a second one replaced the first's answers). */
    private val delivering = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Sends the stored event (again) and marks the message sent at the first relay that took it. */
    private fun deliver(message: ChatMessage, event: JSONObject) {
        if (!delivering.add(message.id)) return
        val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
        RelayPool.listen(event.optString("id")) { _, ok, _ ->
            if (ok && accepted.compareAndSet(false, true)) {
                scope.launch { dao?.setState(message.owner, message.peer, message.id, ChatMessage.STATE_SENT) }
            }
        }
        RelayPool.publishRaw(event)
        scope.launch {
            delay(SEND_TIMEOUT_MS)
            if (!accepted.get()) dao?.setState(message.owner, message.peer, message.id, ChatMessage.STATE_FAILED)
            delivering.remove(message.id)
        }
    }

    /** "Retry" on a message that didn't go: the same event again (signed now if it never was). */
    fun retry(message: ChatMessage) {
        scope.launch {
            val stored = message.event?.let { runCatching { JSONObject(it) }.getOrNull() }
            val event = stored ?: signMessage(message)?.also { e ->
                dao?.update(message.copy(event = e.toJson().toString(), eventId = e.id))
            }?.toJson() ?: return@launch
            dao?.setState(message.owner, message.peer, message.id, ChatMessage.STATE_SENDING)
            deliver(message, event)
        }
    }

    private var retryJob: Job? = null

    /** [retryOutbox] shortly, once for a burst of relays opening together. */
    private fun retrySoon() {
        if (me() == null || retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(1_500)
            retryOutbox()
        }
    }

    private suspend fun retryOutbox() {
        val me = me() ?: return
        dao?.outbox(me)?.forEach { m ->
            m.event?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { deliver(m, it) }
        }
        dao?.reactionOutbox(me)?.forEach { r ->
            val event = r.event?.let { runCatching { JSONObject(it) }.getOrNull() }
            when {
                event != null -> deliverReaction(r, event)
                // Put, but the app went before it was sent.
                r.state == ChatMessage.STATE_SENDING && !reactionJobs.containsKey(r.peer + ":" + r.msgId) -> sendReaction(r)
            }
        }
        // Mine that never got signed (the app went in between): signed and sent now.
        dao?.unsigned(me, System.currentTimeMillis() - 60_000)?.forEach(::retry)
    }

    fun edit(message: ChatMessage, newText: String) {
        val text = Stickers.clean(newText.trim().take(MAX_TEXT))
        if (!message.mine || text.isEmpty() || text == message.text) return
        scope.launch {
            dao?.update(message.copy(text = text, edited = true))
            sign(message.peer, KIND_DM, payload(K_EDIT, message.id).put("ts", System.currentTimeMillis()).put("text", text))
                ?.let { RelayPool.publish(it) }
        }
    }

    /** Gone here; [forBoth] (own messages) also from the friend's phone, and relays are asked to drop it. */
    fun delete(message: ChatMessage, forBoth: Boolean) {
        scope.launch {
            dao?.update(message.copy(text = "", music = null, deleted = true, event = null))
            dao?.dropReactions(message.owner, message.peer, message.id)
            if (!forBoth || !message.mine) return@launch
            sign(message.peer, KIND_DM, payload(K_DEL, message.id).put("ts", System.currentTimeMillis()))?.let { RelayPool.publish(it) }
            val sk = FriendsHub.secretKeyOrNull() ?: return@launch
            message.eventId?.let { eventId ->
                RelayPool.publish(Nostr.sign(sk, KIND_DELETE, listOf(listOf("e", eventId)), ""))
            }
        }
    }

    // ── Reactions ────────────────────────────────────────────────────────────────────────────────

    /** The set waiting to go per message: quick taps send one event (the newest) after a pause. */
    private val reactionJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /**
     * Puts [key] on [message] or takes it off (see [ChatReactions.toggle]). Shown at once; the whole
     * set goes to the friend a moment later, so quick taps send one event.
     */
    fun react(message: ChatMessage, key: String) {
        val me = me() ?: return
        if (message.deleted) return
        scope.launch {
            val dao = dao ?: return@launch
            val old = dao.reaction(me, message.peer, message.id, me)
            val keys = ChatReactions.toggle(old?.keyList.orEmpty(), key)
            if (key in keys) ChatReactions.used(key)
            val ts = maxOf(System.currentTimeMillis(), (old?.ts ?: 0L) + 1)
            dao.putReaction(ChatReaction(me, message.peer, message.id, me, keys.joinToString("\n"), ts, ChatMessage.STATE_SENDING))
            val jobKey = message.peer + ":" + message.id
            reactionJobs.remove(jobKey)?.cancel()
            reactionJobs[jobKey] = scope.launch {
                delay(REACT_DEBOUNCE_MS)
                reactionJobs.remove(jobKey)
                dao.reaction(me, message.peer, message.id, me)?.let { sendReaction(it) }
            }
        }
    }

    /** Signs my current set on a message and sends it. */
    private suspend fun sendReaction(current: ChatReaction) {
        val content = JSONObject().put("t", TYPE_REACT).put("v", PROTOCOL).put("id", current.msgId)
            .put("ts", current.ts).put("r", JSONArray(current.keyList))
        val event = sign(current.peer, KIND_DM, content) ?: return
        val stored = current.copy(event = event.toJson().toString())
        dao?.putReaction(stored)
        Log.i(LOG_TAG, "react send msg=${current.msgId.take(8)} n=${current.keyList.size} ev=${event.id.take(8)}")
        deliverReaction(stored, event.toJson())
    }

    /** Sends a reaction's event; it counts as sent at the first relay that takes it. */
    private fun deliverReaction(r: ChatReaction, event: JSONObject) {
        val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
        RelayPool.listen(event.optString("id")) { relay, ok, reason ->
            Log.i(LOG_TAG, "react ack ${relay.removePrefix("wss://").take(24)} ${if (ok) "ok" else "refused:" + reason.take(40)}")
            if (ok && accepted.compareAndSet(false, true)) {
                scope.launch { dao?.setReactionState(r.owner, r.peer, r.msgId, r.ts, ChatMessage.STATE_SENT) }
            }
        }
        RelayPool.publishRaw(event)
        scope.launch {
            delay(SEND_TIMEOUT_MS)
            if (!accepted.get()) dao?.setReactionState(r.owner, r.peer, r.msgId, r.ts, ChatMessage.STATE_FAILED)
        }
    }

    /** "Retry" on my reactions to a message that didn't go. */
    fun retryReaction(r: ChatReaction) {
        val event = r.event?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        scope.launch {
            dao?.setReactionState(r.owner, r.peer, r.msgId, r.ts, ChatMessage.STATE_SENDING)
            deliverReaction(r, event)
        }
    }

    /** A friend's reactions to a message: their whole set now, kept if it is newer than what is there. */
    private suspend fun applyReaction(me: String, author: String, event: NostrEvent, o: JSONObject) {
        val dao = dao ?: return
        val id = o.optString("id").takeIf { it.isNotBlank() && it.length <= 64 } ?: return
        val ts = o.optLong("ts").takeIf { it > 0 }?.coerceAtMost(event.createdAt * 1000 + 60_000) ?: (event.createdAt * 1000)
        val list = o.optJSONArray("r") ?: run {
            Log.i(LOG_TAG, "react recv msg=${id.take(8)} invalid:no list")
            return
        }
        val keys = (0 until list.length()).map { list.optString(it) }
            .filter(ChatReactions::isWellFormed).distinct().take(ChatReactions.MAX)
        if (dao.get(me, author, id)?.deleted == true) {
            Log.i(LOG_TAG, "react recv msg=${id.take(8)} tombstoned")
            return
        }
        val old = dao.reaction(me, author, id, author)
        // The newer word wins; the same moment twice: the event id decides, the same on every phone.
        if (old != null && (old.ts > ts || old.ts == ts && (old.event ?: "") >= event.id)) {
            Log.i(LOG_TAG, "react recv msg=${id.take(8)} stale")
            return
        }
        // Their row keeps the deciding event id in [ChatReaction.event] (it is never sent from here).
        dao.putReaction(ChatReaction(me, author, id, author, keys.joinToString("\n"), ts, ChatMessage.STATE_RECEIVED, event.id))
        Log.i(LOG_TAG, "react recv msg=${id.take(8)} applied n=${keys.size}")
    }

    // ── Reading, typing ──────────────────────────────────────────────────────────────────────────

    @Volatile
    private var appVisible = false
    private var typingSub: String? = null

    fun appVisible(visible: Boolean) {
        appVisible = visible
        hold("ui", visible)
        // A profile made since the start gets its background checks too.
        if (visible) appContext?.let(::scheduleCheck)
        _open.value?.let { if (visible) markRead(it) }
    }

    /** A conversation came on screen (null: it closed). Its messages are read, its typing is shown. */
    @Synchronized
    fun opened(peer: String?) {
        _open.value = peer
        typingSub?.let(RelayPool::close)
        typingSub = null
        val me = me() ?: return
        if (peer == null) return
        appContext?.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        markRead(peer)
        val filter = JSONObject()
            .put("kinds", JSONArray().put(KIND_TYPING))
            .put("authors", JSONArray().put(peer))
            .put("#p", JSONArray().put(me))
            .put("since", System.currentTimeMillis() / 1000 - 5)
        typingSub = RelayPool.subscribe(listOf(filter)) { onEvent(it) }
    }

    fun markRead(peer: String) {
        val me = me() ?: return
        scope.launch {
            val dao = dao ?: return@launch
            val newest = dao.newestUnread(me, peer) ?: return@launch
            dao.markIncomingRead(me, peer)
            sendRead(peer, newest)
            notifyNew()
        }
    }

    private fun sendRead(peer: String, upTo: Long) {
        if (!FriendsHub.privacy.value.readReceipts) return
        sign(peer, KIND_DM, payload(K_READ, UUID.randomUUID().toString()).put("upTo", upTo))?.let { RelayPool.publish(it) }
    }

    private var lastTypingSent = 0L

    /** Called as the text changes: the friend sees "typing…", at most one signal per few seconds. */
    fun typingNow(peer: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTypingSent < TYPING_EVERY_MS || !FriendsHub.privacy.value.readReceipts) return
        lastTypingSent = now
        scope.launch {
            sign(peer, KIND_TYPING, payload(K_TYPING, "t").put("ts", System.currentTimeMillis()))?.let { RelayPool.publish(it) }
        }
    }

    // ── Who added me, and with what app ──────────────────────────────────────────────────────────

    private fun loadPeers(): Map<String, PeerStatus> = runCatching {
        val o = JSONObject(prefs?.getString("peers_${me()}", "{}") ?: "{}")
        o.keys().asSequence().associateWith { k ->
            val v = o.getJSONObject(k)
            // "c" (has chats, yes/no) from before the protocol number was kept.
            val protocol = if (v.has("p")) v.optInt("p") else if (v.optBoolean("c")) 1 else 0
            PeerStatus(v.optBoolean("a"), protocol, v.optString("ver").takeIf { it.isNotBlank() })
        }
    }.getOrDefault(emptyMap())

    private fun savePeers(me: String, peers: Map<String, PeerStatus>) {
        val out = JSONObject()
        peers.forEach { (k, v) ->
            val o = JSONObject().put("a", v.addedMe).put("c", v.hasChat).put("p", v.protocol)
            v.version?.let { o.put("ver", it) }
            out.put(k, o)
        }
        prefs?.edit()?.putString("peers_$me", out.toString())?.apply()
    }

    /** Anything a friend sends tells which chat format their app speaks: never less than known. */
    private fun notePeerProtocol(peer: String, protocol: Int) {
        val me = me() ?: return
        val known = _peers.value[peer]
        if (protocol <= 0 || (known != null && known.protocol >= protocol)) return
        val updated = _peers.value + (peer to (known ?: PeerStatus(addedMe = true, protocol = 0)).copy(protocol = protocol))
        _peers.value = updated
        savePeers(me, updated)
    }

    /** A version as a friend's app tells it: short and plain, or nothing. */
    private fun cleanVersion(raw: String?): String? = raw?.trim()?.takeIf { VERSION_RE.matches(it) }

    /**
     * Reads each friend's hello to this profile: there, it means they added me; with "chat", their
     * app has chats. Friends who added me hear my hello again once, so they learn mine has chats.
     */
    suspend fun refreshPeers() {
        val me = me() ?: return
        val sk = FriendsHub.secretKeyOrNull() ?: return
        val friends = FriendsHub.friends.value.map { it.pubkey }
        if (friends.isEmpty()) return
        val newest = java.util.concurrent.ConcurrentHashMap<String, NostrEvent>()
        var ends = 0
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val filter = JSONObject()
            .put("kinds", JSONArray().put(Nostr.KIND_APP_DATA))
            .put("authors", JSONArray(friends))
            .put("#d", JSONArray().put(FriendsHub.helloTag(me)))
        val sub = RelayPool.subscribe(listOf(filter), {
            synchronized(done) { ends++; if (ends >= 3) done.complete(Unit) }
        }) { e -> newest.merge(e.pubkey, e) { a, b -> if (b.createdAt > a.createdAt) b else a } }
        withTimeoutOrNull(PEERS_TIMEOUT_MS) { done.await() }
        RelayPool.close(sub)
        val found = newest.mapValues { (author, e) ->
            val plain = e.content.takeIf { it.isNotEmpty() }?.let { Nostr.decrypt(sk, author, it) }
            val json = plain?.let { runCatching { JSONObject(it) }.getOrNull() }
            val known = _peers.value[author]
            // An old hello doesn't lower what their messages already showed.
            val protocol = maxOf(json?.optInt("chat") ?: 0, known?.protocol ?: 0)
            PeerStatus(addedMe = plain != null, protocol = protocol, version = cleanVersion(json?.optString("ver")) ?: known?.version)
        }
        // Only what was heard replaces what was known (a relay that didn't answer forgets nothing).
        val merged = _peers.value + found
        _peers.value = merged
        savePeers(me, merged)
        // My hello again once per version of the app, to every friend who has me: they learn what
        // this one can do (reactions) and which version it is.
        val toldKey = "told_${me}_${com.metrolist.music.BuildConfig.VERSION_NAME}"
        val told = prefs?.getStringSet(toldKey, emptySet()).orEmpty()
        val toTell = found.filter { it.value.addedMe && it.key !in told }.keys
        if (toTell.isNotEmpty()) {
            toTell.forEach(FriendsHub::sayHelloAgain)
            prefs?.edit()?.putStringSet(toldKey, told + toTell)?.apply()
        }
    }

    // ── Notifications ────────────────────────────────────────────────────────────────────────────

    private const val CHANNEL = "chat_messages"
    private const val NOTIFICATION_ID = 5301

    var notificationsOn: Boolean
        get() = prefs?.getBoolean("notify", true) ?: true
        set(value) {
            prefs?.edit()?.putBoolean("notify", value)?.apply()
            appContext?.let { if (value) scheduleCheck(it) else cancelCheck(it) }
        }

    /** One notification for all unread messages, grouped by friend, newest last; gone when all are read. */
    private suspend fun notifyNew() {
        val app = appContext ?: return
        val me = me() ?: return
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        val unread = dao?.unreadIncoming(me).orEmpty().filter { it.peer != _open.value || !appVisible }
        if (unread.isEmpty() || !notificationsOn) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        if (!nm.areNotificationsEnabled()) return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, app.getString(R.string.chat_channel), NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val names = FriendsHub.friends.value.associate { it.pubkey to it.name }
        fun nameOf(peer: String) = when {
            FriendsHub.isDeveloper(peer) -> FriendsHub.DEVELOPER_NAME
            else -> names[peer]?.takeIf { it.isNotBlank() } ?: FriendsHub.data.value[peer]?.name ?: (peer.take(8) + "…")
        }
        val self = Person.Builder().setName(FriendsHub.profile.value?.name?.ifBlank { null } ?: app.getString(R.string.chat_me)).build()
        val style = NotificationCompat.MessagingStyle(self)
        val peers = unread.map { it.peer }.distinct()
        if (peers.size > 1) style.conversationTitle = app.getString(R.string.chat_notif_many, unread.size, peers.size)
        unread.takeLast(12).forEach { m ->
            style.addMessage(preview(app, m), m.ts, Person.Builder().setName(nameOf(m.peer)).setKey(m.peer).build())
        }
        val route = if (peers.size == 1) "chat/${peers[0]}" else "chats"
        val open = PendingIntent.getActivity(
            app, NOTIFICATION_ID,
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.small_icon)
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setNumber(unread.size)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    /** One line for a message: its text with sticker codes, or the music on its card. */
    fun preview(context: Context, m: ChatMessage): String {
        val text = Stickers.plain(m.text).trim()
        val music = ChatMusic.fromJson(m.music)
        return when {
            music != null && text.isNotEmpty() -> "🎵 ${music.label()} · $text"
            music != null -> "🎵 ${music.label()}"
            text.isNotEmpty() -> text
            else -> context.getString(R.string.chat_sticker)
        }
    }

    // ── Background check ─────────────────────────────────────────────────────────────────────────

    private const val ACTION = "com.metrolist.music.CHAT_CHECK"

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 5302,
            Intent(context, ChatAlarmReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The next background look, in about half an hour (the system may move it later, never earlier). */
    fun scheduleCheck(context: Context) {
        val app = context.applicationContext
        if (FriendsHub.secretKeyOrNull() == null) return
        if (!app.getSharedPreferences("chat", Context.MODE_PRIVATE).getBoolean("notify", true)) return
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + CHECK_EVERY_MS + (0..CHECK_EVERY_MS / 6).random()
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(app)) }
            .onFailure { Log.w(LOG_TAG, "schedule failed", it) }
    }

    fun cancelCheck(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(pending(app))
    }

    internal fun runCheck(then: () -> Unit) {
        scope.launch {
            try {
                if (notificationsOn) checkOnce()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "check failed", e)
            } finally {
                then()
            }
        }
    }

    private const val MAX_TEXT = 4_000
    private const val REACT_DEBOUNCE_MS = 1_200L
    private val VERSION_RE = Regex("^[0-9A-Za-z.+-]{1,16}$")
    private const val RELEASE_MS = 3 * 60_000L
    private const val FIRST_LOOK_S = 30L * 24 * 3600
    private const val OVERLAP_S = 10 * 60L
    private const val CHECK_TIMEOUT_MS = 15_000L
    private const val PEERS_TIMEOUT_MS = 8_000L
    private const val SEND_TIMEOUT_MS = 20_000L
    private const val TYPING_EVERY_MS = 4_000L
    const val TYPING_SHOWN_MS = 6_000L
    private const val CHECK_EVERY_MS = 30 * 60_000L
}

class ChatAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // The next look first: if this one dies the chain survives.
        ChatHub.scheduleCheck(app)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        ChatHub.runCheck { pending.finish() }
    }
}
