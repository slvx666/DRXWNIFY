/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.friends

import android.content.Context
import android.content.SharedPreferences
import com.metrolist.music.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * The suggestion box: an idea, a bug or a track that can't be found, sent to the developer as an
 * encrypted direct message (Nostr kind 4, NIP-04) over the relays the friends already use — no
 * server and no bot token in the app. Signed with the friends profile when there is one, else with
 * a key made for this alone (anonymous). The developer's account reads them in the app's inbox (or
 * in any Nostr client with that key).
 */
object Suggestions {
    private const val KIND_DM = 4
    private const val TYPE = "drxw-suggest"
    private const val MAX_TEXT = 2_000
    private const val MAX_PER_HOUR = 5
    private const val INBOX_DAYS = 180L

    enum class Topic { IDEA, BUG, TRACK, OTHER }

    data class Suggestion(
        val id: String,
        val from: String,
        val name: String?,
        val topic: Topic,
        val text: String,
        val version: String?,
        val at: Long,
    )

    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: context.applicationContext.getSharedPreferences("suggestions", Context.MODE_PRIVATE).also { prefs = it }

    /** Too many sent in the last hour (a stuck button must not flood the developer). */
    fun limited(context: Context): Boolean {
        val hourAgo = System.currentTimeMillis() - 60 * 60_000L
        return prefs(context).getString("sent", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.count { it > hourAgo } >= MAX_PER_HOUR
    }

    /** Sends [text]; false when nothing was sent (empty, or the hourly limit). */
    fun send(context: Context, topic: Topic, text: String): Boolean {
        val body = text.trim().take(MAX_TEXT)
        if (body.isEmpty() || limited(context)) return false
        val p = prefs(context)
        val key = FriendsHub.secretKeyOrNull() ?: anonymousKey(p)
        val payload = JSONObject()
            .put("t", TYPE)
            .put("topic", topic.name.lowercase())
            .put("text", body)
            .put("v", BuildConfig.VERSION_NAME)
            .apply { FriendsHub.profile.value?.name?.takeIf { it.isNotBlank() }?.let { put("name", it) } }
        val content = Nostr.encrypt(key, FriendsHub.DEVELOPER_PUBKEY, payload.toString())
        RelayPool.publish(Nostr.sign(key, KIND_DM, listOf(listOf("p", FriendsHub.DEVELOPER_PUBKEY)), content))
        val hourAgo = System.currentTimeMillis() - 60 * 60_000L
        val recent = p.getString("sent", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.filter { it > hourAgo } +
            System.currentTimeMillis()
        p.edit().putString("sent", recent.joinToString(",")).apply()
        return true
    }

    private fun anonymousKey(p: SharedPreferences): ByteArray {
        p.getString("anonKey", null)?.let { return it.hexToBytes() }
        val sk = Secp256k1.newPrivateKey()
        p.edit().putString("anonKey", sk.toHex()).apply()
        return sk
    }

    // ── The developer's inbox ────────────────────────────────────────────────────────────────────

    private val _inbox = MutableStateFlow<List<Suggestion>>(emptyList())
    val inbox: StateFlow<List<Suggestion>> = _inbox

    private val _unseen = MutableStateFlow(0)

    /** Suggestions newer than the last look at the inbox (shown as a badge in Settings). */
    val unseen: StateFlow<Int> = _unseen

    private var subscription: String? = null

    val isDeveloper: Boolean get() = FriendsHub.profile.value?.isDeveloper == true

    private var holders = 0

    /** Starts receiving (developer only) while held. Messages from the last half year come first, then live ones. */
    @Synchronized
    fun openInbox(context: Context) {
        if (!isDeveloper) return
        holders++
        if (subscription != null) return
        val sk = FriendsHub.secretKeyOrNull() ?: return
        val p = prefs(context)
        val filter = JSONObject()
            .put("kinds", org.json.JSONArray().put(KIND_DM))
            .put("#p", org.json.JSONArray().put(FriendsHub.DEVELOPER_PUBKEY))
            .put("since", System.currentTimeMillis() / 1000 - INBOX_DAYS * 24 * 3600)
        subscription = RelayPool.subscribe(listOf(filter)) { event ->
            val plain = Nostr.decrypt(sk, event.pubkey, event.content) ?: return@subscribe
            val o = runCatching { JSONObject(plain) }.getOrNull()?.takeIf { it.optString("t") == TYPE } ?: return@subscribe
            val suggestion = Suggestion(
                id = event.id,
                from = event.pubkey,
                name = o.optString("name").takeIf { it.isNotBlank() },
                topic = Topic.entries.firstOrNull { it.name.equals(o.optString("topic"), ignoreCase = true) } ?: Topic.OTHER,
                text = o.optString("text"),
                version = o.optString("v").takeIf { it.isNotBlank() },
                at = event.createdAt * 1000,
            )
            synchronized(this) {
                if (_inbox.value.any { it.id == suggestion.id }) return@subscribe
                _inbox.value = (_inbox.value + suggestion).sortedByDescending { it.at }
                val seenAt = p.getLong("seenAt", 0L)
                _unseen.value = _inbox.value.count { it.at > seenAt }
            }
        }
    }

    @Synchronized
    fun closeInbox() {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders > 0) return
        subscription?.let(RelayPool::close)
        subscription = null
    }

    /** A look at the inbox a while after start, for the badge (the developer's phone only). */
    suspend fun peek(context: Context) {
        if (!isDeveloper) return
        openInbox(context)
        kotlinx.coroutines.delay(60_000L)
        closeInbox()
    }

    fun markSeen(context: Context) {
        prefs(context).edit().putLong("seenAt", System.currentTimeMillis()).apply()
        _unseen.value = 0
    }

    // ── Replies from the developer ───────────────────────────────────────────────────────────────

    private const val REPLY_TYPE = "drxw-reply"
    private const val REPLY_LISTEN_MS = 25_000L

    /** The developer's answer to one of this phone's suggestions; [quote] is what it answers. */
    data class Reply(val id: String, val text: String, val quote: String?, val at: Long)

    private val _replies = MutableStateFlow<List<Reply>>(emptyList())

    /** Answers not shown yet, oldest first (shown at start, one sheet each, like release notes). */
    val replies: StateFlow<List<Reply>> = _replies

    /** Suggestion ids the developer already answered (from this phone's inbox view). */
    fun repliedIds(context: Context): Set<String> = prefs(context).getStringSet("replied", emptySet()).orEmpty()

    /**
     * Developer only: answers [to] with an encrypted direct message to the key it came from — the
     * sender's phone shows it at its next start.
     */
    fun reply(context: Context, to: Suggestion, text: String): Boolean {
        val body = text.trim().take(MAX_TEXT)
        val sk = FriendsHub.secretKeyOrNull()
        if (body.isEmpty() || sk == null || !isDeveloper) return false
        val payload = JSONObject()
            .put("t", REPLY_TYPE)
            .put("text", body)
            .put("re", to.id)
            .put("q", to.text.take(400))
        val content = Nostr.encrypt(sk, to.from, payload.toString())
        RelayPool.publish(Nostr.sign(sk, KIND_DM, listOf(listOf("p", to.from), listOf("e", to.id)), content))
        val p = prefs(context)
        p.edit().putStringSet("replied", repliedIds(context) + to.id).apply()
        return true
    }

    /**
     * Looks for the developer's answers to this phone's suggestions (to the friends key and to the
     * anonymous one), for a little while after start. There is no push server: an answer shows up
     * the next time the app is opened.
     */
    suspend fun checkReplies(context: Context) {
        val p = prefs(context)
        val keys = listOfNotNull(FriendsHub.secretKeyOrNull(), p.getString("anonKey", null)?.hexToBytes())
            .distinctBy { it.toHex() }
            .filterNot { Secp256k1.publicKey(it).toHex() == FriendsHub.DEVELOPER_PUBKEY }
        if (keys.isEmpty()) return
        val subscriptions = keys.map { sk ->
            val filter = JSONObject()
                .put("kinds", org.json.JSONArray().put(KIND_DM))
                .put("authors", org.json.JSONArray().put(FriendsHub.DEVELOPER_PUBKEY))
                .put("#p", org.json.JSONArray().put(Secp256k1.publicKey(sk).toHex()))
                .put("since", System.currentTimeMillis() / 1000 - INBOX_DAYS * 24 * 3600)
            RelayPool.subscribe(listOf(filter)) { event ->
                if (event.pubkey != FriendsHub.DEVELOPER_PUBKEY) return@subscribe
                val plain = Nostr.decrypt(sk, event.pubkey, event.content) ?: return@subscribe
                val o = runCatching { JSONObject(plain) }.getOrNull()?.takeIf { it.optString("t") == REPLY_TYPE } ?: return@subscribe
                val reply = Reply(
                    id = event.id,
                    text = o.optString("text"),
                    quote = o.optString("q").takeIf { it.isNotBlank() },
                    at = event.createdAt * 1000,
                )
                synchronized(this) {
                    val seen = p.getStringSet("seenReplies", emptySet()).orEmpty()
                    if (reply.id in seen || _replies.value.any { it.id == reply.id } || reply.text.isBlank()) return@subscribe
                    _replies.value = (_replies.value + reply).sortedBy { it.at }
                }
            }
        }
        kotlinx.coroutines.delay(REPLY_LISTEN_MS)
        subscriptions.forEach(RelayPool::close)
    }

    fun markReplySeen(context: Context, id: String) {
        val p = prefs(context)
        p.edit().putStringSet("seenReplies", p.getStringSet("seenReplies", emptySet()).orEmpty() + id).apply()
        _replies.value = _replies.value.filterNot { it.id == id }
    }
}
