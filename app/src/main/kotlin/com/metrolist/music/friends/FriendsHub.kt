/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.friends

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Player
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.extensions.metadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Friends without a server of our own: every phone has a key made on the phone itself (its
 * "account"), and what a user shares — what plays now, recent tracks, playlists, a week's
 * statistics — goes to free public Nostr relays as signed events. "Friends only" data is encrypted
 * for each friend separately, so the relays (and anyone else) can't read it. Nothing to set up,
 * pay for or maintain; a relay disappearing is covered by the others.
 *
 * Adding a friend = pasting their code (npub). They get a request and see it in their app.
 */
object FriendsHub {
    /**
     * Who a field is shared with, narrowest first (the order is used to tell a narrowed choice).
     * [VERIFIED] is the developer's own: only the people they marked as verified.
     */
    enum class Audience { NOBODY, VERIFIED, FRIENDS, EVERYONE }

    data class Privacy(
        val nowPlaying: Audience = Audience.FRIENDS,
        val history: Audience = Audience.FRIENDS,
        val playlists: Audience = Audience.FRIENDS,
        val stats: Audience = Audience.FRIENDS,
        /** Others can find the profile by its name (a public Nostr profile). */
        val discoverable: Boolean = false,
        /** Friends may follow this playback live ("listen together"). */
        val listenAlong: Boolean = true,
    )

    /** [avatar]: a small JPEG as base64, sent inside the profile event (there is no server for files). */
    data class Profile(val pubkey: String, val name: String, val avatar: String? = null) {
        val code: String get() = Nostr.bech32Encode("npub", pubkey.hexToBytes())
        val isDeveloper: Boolean get() = pubkey == DEVELOPER_PUBKEY
    }

    /** An invite opened from a link (drxwnify.pages.dev/add/<npub>), waiting for "accept" or "cancel". */
    data class Invite(val pubkey: String, val name: String)

    /**
     * The developer's public profile (drxwnslvt): shown to everyone at the top of Friends with the
     * avatar from "About", whatever the network returns, and followed for what it shares publicly.
     */
    const val DEVELOPER_PUBKEY = "e114d6d2c422913fbb0c5b525cf927015aaecdfbea72fadd5c1dd2693f1c7f74"
    const val DEVELOPER_NAME = "drxwnslvt"

    fun isDeveloper(pubkey: String) = pubkey == DEVELOPER_PUBKEY

    /** Base url of invite links; the site turns them into "open in the app". */
    const val INVITE_BASE = "https://drxwnify.pages.dev/add/"

    fun inviteLink(): String? {
        val me = _profile.value ?: return null
        val name = java.net.URLEncoder.encode(me.name, "UTF-8")
        return INVITE_BASE + me.code + if (me.name.isNotBlank()) "?n=$name" else ""
    }

    /** An invite from [uri] (the site link or drxwnify://add/<npub>), or null. */
    fun parseInvite(uri: android.net.Uri): Invite? {
        val isSite = uri.host == "drxwnify.pages.dev" && uri.path.orEmpty().startsWith("/add/")
        val isScheme = uri.scheme == "drxwnify" && (uri.host == "add" || uri.path.orEmpty().startsWith("/add"))
        if (!isSite && !isScheme) return null
        val pub = parseCode(uri.toString()) ?: return null
        return Invite(pub, uri.getQueryParameter("n").orEmpty().take(40))
    }

    private val _invite = MutableStateFlow<Invite?>(null)
    val invite: StateFlow<Invite?> = _invite

    fun openInvite(invite: Invite) {
        if (invite.pubkey == _profile.value?.pubkey) return
        _invite.value = invite
    }

    fun answerInvite(accept: Boolean) {
        val invite = _invite.value ?: return
        _invite.value = null
        if (accept) addFriend(invite.pubkey, invite.name)
    }

    data class Friend(val pubkey: String, val name: String, val addedAt: Long)

    data class TrackRef(
        val title: String,
        val artist: String,
        val catalogId: String?,
        val mediaId: String?,
        val cover: String?,
        val durationMs: Long = 0,
        val at: Long = 0,
    )

    data class NowPlaying(val track: TrackRef, val positionMs: Long, val playing: Boolean, val at: Long, val listenAlong: Boolean) {
        fun positionNow(): Long = if (playing) positionMs + (System.currentTimeMillis() - at).coerceAtLeast(0) else positionMs
    }

    data class SharedPlaylist(val name: String, val total: Int, val tracks: List<TrackRef>)

    /** A friend's liked tracks: the newest ones (up to [MAX_LIKES]) of [total]; [source] = the account's service. */
    data class SharedLikes(val total: Int, val source: String?, val tracks: List<TrackRef>)

    data class Stats(val weekMinutes: Int, val weekPlays: Int, val likedCount: Int, val topArtists: List<Pair<String, Int>>)

    data class FriendData(
        val name: String? = null,
        val avatar: String? = null,
        val now: NowPlaying? = null,
        val history: List<TrackRef>? = null,
        val playlists: List<SharedPlaylist>? = null,
        val stats: Stats? = null,
        val likes: SharedLikes? = null,
        val portrait: com.metrolist.music.portrait.ProfilePortrait? = null,
        /** Newest event per field (created_at, s), so an older copy never replaces a newer one. */
        val stamps: Map<String, Long> = emptyMap(),
    )

    data class FriendRequest(val pubkey: String, val name: String, val at: Long)

    private const val D_PREFIX = "drxw:"
    private const val F_PROFILE = "profile"
    private const val F_NOW = "now"
    private const val F_HISTORY = "history"
    private const val F_PLAYLISTS = "playlists"
    private const val F_STATS = "stats"
    private const val F_HELLO = "hello"

    /** Liked tracks of the account (or of the app without one), packed: see [packLikes]. */
    private const val F_LIKES = "likes"

    /** The musical portrait (periods, months, genre tags), rebuilt once a day: see [PortraitBuilder]. */
    private const val F_PORTRAIT = "portrait"

    /**
     * The developer's mark on a profile ("verified"): sent encrypted to that one person only. It
     * opens drxwnslvt's album reviews for them; nobody sees the mark itself but the developer.
     */
    private const val F_VERIFIED = "verified"
    private val DATA_FIELDS = listOf(F_NOW, F_HISTORY, F_PLAYLISTS, F_STATS, F_LIKES, F_PORTRAIT)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null
    private var database: MusicDatabase? = null
    private var appContext: Context? = null

    private val _profile = MutableStateFlow<Profile?>(null)
    val profile: StateFlow<Profile?> = _profile

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends: StateFlow<List<Friend>> = _friends

    private val _privacy = MutableStateFlow(Privacy())
    val privacy: StateFlow<Privacy> = _privacy

    private val _data = MutableStateFlow<Map<String, FriendData>>(emptyMap())
    val data: StateFlow<Map<String, FriendData>> = _data

    private val _requests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val requests: StateFlow<List<FriendRequest>> = _requests

    private val _listeningTo = MutableStateFlow<String?>(null)
    val listeningTo: StateFlow<String?> = _listeningTo

    /** Developer side: the profiles he marked. */
    private val _verified = MutableStateFlow<Set<String>>(emptySet())
    val verified: StateFlow<Set<String>> = _verified

    /** Friends pinned to the top of the list (long press), in the order they were pinned. On this phone only. */
    private val _pinned = MutableStateFlow<List<String>>(emptyList())
    val pinned: StateFlow<List<String>> = _pinned

    /** Pins [pubkey] to the top of the friends list, or unpins it; returns whether it is pinned now. */
    fun togglePinned(pubkey: String): Boolean {
        val now = _pinned.value
        val updated = if (pubkey in now) now - pubkey else now + pubkey
        _pinned.value = updated
        prefs?.edit()?.putString("pinned", JSONArray(updated).toString())?.apply()
        return pubkey in updated
    }

    /** This profile was marked by the developer (or is the developer): drxwnslvt's reviews are open. */
    private val _reviewsAccess = MutableStateFlow(false)
    val reviewsAccess: StateFlow<Boolean> = _reviewsAccess

    private fun refreshAccess() {
        _reviewsAccess.value = _profile.value?.isDeveloper == true || prefs?.getBoolean("iAmVerified", false) == true
    }

    @Volatile
    private var secretKey: ByteArray? = null

    internal fun databaseOrNull(): MusicDatabase? = database

    fun init(context: Context, database: MusicDatabase) {
        appContext = context.applicationContext
        this.database = database
        val p = context.getSharedPreferences("friends", Context.MODE_PRIVATE)
        prefs = p
        p.getString("sk", null)?.let { hex ->
            runCatching {
                val sk = hex.hexToBytes()
                secretKey = sk
                _profile.value = Profile(Secp256k1.publicKey(sk).toHex(), p.getString("name", null).orEmpty(), p.getString("avatar", null))
            }
        }
        _friends.value = runCatching {
            JSONArray(p.getString("friends", "[]")).let { a ->
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Friend(o.getString("p"), o.optString("n"), o.optLong("t"))
                }
            }
        }.getOrDefault(emptyList())
        _privacy.value = runCatching { privacyFromJson(JSONObject(p.getString("privacy", "{}")!!)) }.getOrDefault(Privacy())
        // The developer's account is public by design: once, everything is opened to everyone.
        if (_profile.value?.isDeveloper == true && !p.getBoolean("devPublic", false)) {
            p.edit().putBoolean("devPublic", true).apply()
            val open = Privacy(Audience.EVERYONE, Audience.EVERYONE, Audience.EVERYONE, Audience.EVERYONE, discoverable = true, listenAlong = true)
            _privacy.value = open
            p.edit().putString("privacy", privacyToJson(open).toString()).apply()
            scope.launch { delay(5_000L); publishProfile(); publishLibrary() }
        }
        dropSelfFromFriends()
        ensureDeveloperFriend()
        _verified.value = p.getStringSet("verifiedSet", emptySet()).orEmpty()
        // This profile's own portrait, as last built: on "how others see me" at once.
        _profile.value?.pubkey?.let { me ->
            p.getString("portraitPacked", null)?.let(com.metrolist.music.portrait.PortraitCodec::unpack)?.let { portrait ->
                _data.value = _data.value + (me to (_data.value[me] ?: FriendData()).copy(portrait = portrait))
            }
        }
        _pinned.value = runCatching {
            JSONArray(p.getString("pinned", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
        }.getOrDefault(emptyList())
        refreshAccess()
        // What friends see is refreshed now and then, never more than every few hours.
        if (secretKey != null) scope.launch {
            delay(20_000L)
            publishLibraryIfStale(LIBRARY_REFRESH_MS)
        }
        // The developer's mark is looked up once per start, so the library shows the reviews
        // without the Friends screen having been opened.
        if (secretKey != null) scope.launch {
            delay(8_000L)
            checkVerification()
        }
        // The musical portrait: rebuilt and sent once a day, a minute after start.
        if (secretKey != null) scope.launch {
            delay(60_000L)
            publishPortraitIfStale()
        }
        // Liked tracks of the friends whose likes are kept as a playlist: fetched at start, at most hourly.
        if (secretKey != null) scope.launch {
            delay(30_000L)
            fetchFollowedLikes()
        }
    }

    /** Builds this profile's portrait (shown on it at once) and sends it, at most once in [PORTRAIT_REFRESH_MS]. */
    suspend fun publishPortraitIfStale(force: Boolean = false) = withContext(Dispatchers.IO) {
        val db = database ?: return@withContext
        val context = appContext ?: return@withContext
        val me = _profile.value ?: return@withContext
        val p = prefs ?: return@withContext
        val sameVersion = p.getInt("portraitVersion", 0) == com.metrolist.music.portrait.PortraitBuilder.VERSION
        if (!force && sameVersion && System.currentTimeMillis() - p.getLong("portraitAt", 0L) < PORTRAIT_REFRESH_MS) return@withContext
        runCatching {
            val portrait = com.metrolist.music.portrait.PortraitBuilder.build(context, db)
            val packed = com.metrolist.music.portrait.PortraitCodec.pack(portrait)
            p.edit().putLong("portraitAt", System.currentTimeMillis()).putString("portraitPacked", packed)
                .putInt("portraitVersion", com.metrolist.music.portrait.PortraitBuilder.VERSION).apply()
            synchronized(this@FriendsHub) {
                val current = _data.value[me.pubkey] ?: FriendData()
                _data.value = _data.value + (me.pubkey to current.copy(portrait = portrait))
            }
            val audience = _privacy.value.stats
            if (audience != Audience.NOBODY) publishField(F_PORTRAIT, packed, audience, null)
        }.onFailure { Timber.w(it, "FriendsHub: the portrait failed") }
    }

    data class Found(val pubkey: String, val name: String, val avatar: String?)

    /**
     * Drxwnify profiles whose name contains [query]: every profile publishes its name (for its
     * friends), but only those who allowed it ("can be found by name") are shown.
     */
    suspend fun searchByName(query: String): List<Found> = withContext(Dispatchers.IO) {
        val needle = query.trim().lowercase()
        if (needle.length < 2) return@withContext emptyList()
        val me = _profile.value?.pubkey
        val found = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Found>>()
        val filter = JSONObject()
            .put("kinds", JSONArray().put(Nostr.KIND_APP_DATA))
            .put("#d", JSONArray().put(D_PREFIX + F_PROFILE))
            .put("#t", JSONArray().put("drxwnify"))
            .put("limit", 500)
        val id = RelayPool.subscribe(listOf(filter)) { event ->
            val o = runCatching { JSONObject(event.content) }.getOrNull() ?: return@subscribe
            val name = o.optString("name").trim()
            val allowed = o.optBoolean("find", false) || event.pubkey == DEVELOPER_PUBKEY
            if (!allowed || name.isBlank() || event.pubkey == me || needle !in name.lowercase()) return@subscribe
            val previous = found[event.pubkey]
            if (previous == null || previous.first < event.createdAt) {
                found[event.pubkey] = event.createdAt to Found(event.pubkey, name, o.optString("pic").takeIf { it.isNotBlank() && it.length <= MAX_AVATAR_CHARS })
            }
        }
        delay(SEARCH_WAIT_MS)
        RelayPool.close(id)
        found.values.map { it.second }
            .sortedWith(compareBy<Found> { !it.name.lowercase().startsWith(needle) }.thenBy { it.name.length })
            .take(20)
    }

    private fun fetchFollowedLikes() {
        val me = _profile.value ?: return
        val followed = FriendLikesSync.followed.value.keys.filter { key -> _friends.value.any { it.pubkey == key } }
        if (followed.isEmpty()) return
        val p = prefs ?: return
        if (System.currentTimeMillis() - p.getLong("likesFetchAt", 0L) < LIKES_FETCH_MS) return
        p.edit().putLong("likesFetchAt", System.currentTimeMillis()).apply()
        val filter = JSONObject()
            .put("kinds", JSONArray().put(Nostr.KIND_APP_DATA))
            .put("authors", JSONArray(followed))
            .put("#d", JSONArray().put(D_PREFIX + F_LIKES).put("$D_PREFIX$F_LIKES:${me.pubkey}"))
        val id = RelayPool.subscribe(listOf(filter), ::onEvent)
        scope.launch {
            delay(30_000L)
            RelayPool.close(id)
        }
    }

    private fun checkVerification() {
        val me = _profile.value ?: return
        if (me.isDeveloper) return
        val filter = JSONObject()
            .put("kinds", JSONArray().put(Nostr.KIND_APP_DATA))
            .put("authors", JSONArray().put(DEVELOPER_PUBKEY))
            .put("#d", JSONArray().put("$D_PREFIX$F_VERIFIED:${me.pubkey}"))
        val id = RelayPool.subscribe(listOf(filter), ::onEvent)
        scope.launch {
            delay(25_000L)
            RelayPool.close(id)
        }
    }

    /** Developer only: marks (or unmarks) [pubkey] — it opens the reviews for that profile. */
    fun setVerified(pubkey: String, on: Boolean) {
        if (_profile.value?.isDeveloper != true) return
        val updated = if (on) _verified.value + pubkey else _verified.value - pubkey
        _verified.value = updated
        prefs?.edit()?.putStringSet("verifiedSet", updated)?.apply()
        scope.launch {
            publishTo(pubkey, F_VERIFIED, if (on) JSONObject().put("v", 1).toString() else "")
            // What is shared with "verified" only now reaches them — or is taken back.
            val privacy = _privacy.value
            val verifiedOnly = listOf(
                F_NOW to privacy.nowPlaying,
                F_HISTORY to privacy.history,
                F_PLAYLISTS to privacy.playlists,
                F_LIKES to privacy.playlists,
                F_STATS to privacy.stats,
                F_PORTRAIT to privacy.stats,
            ).filter { it.second == Audience.VERIFIED }
            if (verifiedOnly.isEmpty()) return@launch
            if (on) {
                publishLibrary(onlyFor = pubkey)
                if (privacy.stats == Audience.VERIFIED) {
                    prefs?.getString("portraitPacked", null)?.let { publishField(F_PORTRAIT, it, privacy.stats, pubkey) }
                }
                lastNow?.let { publishNow(it) }
            } else {
                verifiedOnly.forEach { (field, _) -> publishTo(pubkey, field, "") }
            }
        }
    }

    fun isVerified(pubkey: String) = pubkey in _verified.value

    // ── Profile ──────────────────────────────────────────────────────────────────────────────────

    fun createProfile(name: String) {
        val sk = Secp256k1.newPrivateKey()
        saveKey(sk, name.trim())
        ensureDeveloperFriend()
        scope.launch { publishProfile(); publishLibrary() }
    }

    /**
     * Everyone starts with the developer among their friends — once: removed, it stays removed. Added
     * quietly (no request): what the developer shares is public anyway.
     */
    /**
     * A profile is never its own friend. It happened when the developer key was imported on a phone
     * that had the developer among its friends: that row then showed an empty "friend" — the phone
     * doesn't listen to itself.
     */
    private fun dropSelfFromFriends() {
        val me = _profile.value?.pubkey ?: return
        if (_friends.value.none { it.pubkey == me }) return
        _friends.value = _friends.value.filterNot { it.pubkey == me }
        saveFriends()
    }

    private fun ensureDeveloperFriend() {
        val p = prefs ?: return
        val me = _profile.value ?: return
        if (me.isDeveloper) return
        if (!p.getBoolean("devAdded", false)) {
            p.edit().putBoolean("devAdded", true).apply()
            if (_friends.value.none { it.pubkey == DEVELOPER_PUBKEY }) {
                _friends.value = _friends.value + Friend(DEVELOPER_PUBKEY, DEVELOPER_NAME, System.currentTimeMillis())
                saveFriends()
            }
        }
        sayHelloToDeveloper()
    }

    /**
     * The quiet add above sent the developer nothing, and adding someone already among the friends
     * does nothing: nobody could ever appear in the developer's list. So, once per profile, the
     * developer gets the request a normal add sends (while still among this profile's friends).
     */
    private fun sayHelloToDeveloper() {
        val p = prefs ?: return
        val me = _profile.value ?: return
        if (me.isDeveloper || p.getString("devHelloFor", null) == me.pubkey) return
        if (_friends.value.none { it.pubkey == DEVELOPER_PUBKEY }) return
        scope.launch {
            // Give the relays a moment to connect after start.
            delay(10_000L)
            if (_profile.value?.pubkey != me.pubkey || _friends.value.none { it.pubkey == DEVELOPER_PUBKEY }) return@launch
            publishTo(DEVELOPER_PUBKEY, F_HELLO, JSONObject().put("name", _profile.value?.name.orEmpty()).toString())
            publishLibrary(onlyFor = DEVELOPER_PUBKEY)
            p.edit().putString("devHelloFor", me.pubkey).apply()
        }
    }

    /**
     * Restores a profile from its backup key (nsec), also replacing the current one; false when the
     * key is not valid. A blank [name] keeps the current one (the developer key gets its own name).
     */
    fun importProfile(nsec: String, name: String): Boolean {
        val (hrp, bytes) = Nostr.bech32Decode(nsec.trim()) ?: return false
        if (hrp != "nsec" || bytes.size != 32) return false
        val pub = runCatching { Secp256k1.publicKey(bytes).toHex() }.getOrNull() ?: return false
        val finalName = when {
            pub == DEVELOPER_PUBKEY -> DEVELOPER_NAME
            name.isNotBlank() -> name.trim()
            else -> _profile.value?.name.orEmpty()
        }
        if (pub != _profile.value?.pubkey) prefs?.edit()?.remove("avatar")?.remove("devPublic")?.apply()
        saveKey(bytes, finalName)
        if (pub == DEVELOPER_PUBKEY && prefs?.getBoolean("devPublic", false) != true) {
            prefs?.edit()?.putBoolean("devPublic", true)?.apply()
            setPrivacy(Privacy(Audience.EVERYONE, Audience.EVERYONE, Audience.EVERYONE, Audience.EVERYONE, discoverable = true, listenAlong = true))
        }
        dropSelfFromFriends()
        ensureDeveloperFriend()
        restartLive()
        scope.launch { publishProfile(); publishLibrary() }
        checkVerification()
        return true
    }

    /** Sets (or with null removes) the profile picture: any image, cut to a small square JPEG. */
    fun setAvatar(bitmap: android.graphics.Bitmap?) {
        val encoded = bitmap?.let(::encodeAvatar)
        prefs?.edit()?.apply { if (encoded == null) remove("avatar") else putString("avatar", encoded) }?.apply()
        _profile.value = _profile.value?.copy(avatar = encoded)
        scope.launch { publishProfile() }
    }

    private fun encodeAvatar(source: android.graphics.Bitmap): String {
        val side = minOf(source.width, source.height)
        val square = android.graphics.Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val small = android.graphics.Bitmap.createScaledBitmap(square, AVATAR_PX, AVATAR_PX, true)
        val out = java.io.ByteArrayOutputStream()
        // Relays keep events small: ~6-10 KB at this size and quality.
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, out)
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    fun rename(name: String) {
        val sk = secretKey ?: return
        saveKey(sk, name.trim())
        scope.launch { publishProfile() }
    }

    /** The backup key: whoever has it IS this profile — shown only on request. */
    fun backupKey(): String? = secretKey?.let { Nostr.bech32Encode("nsec", it) }

    private fun saveKey(sk: ByteArray, name: String) {
        val pub = Secp256k1.publicKey(sk).toHex()
        // Another profile: its mark has to be looked up again.
        if (pub != _profile.value?.pubkey) prefs?.edit()?.remove("iAmVerified")?.apply()
        secretKey = sk
        prefs?.edit()?.putString("sk", sk.toHex())?.putString("name", name)?.apply()
        _profile.value = Profile(pub, name, prefs?.getString("avatar", null))
        refreshAccess()
    }

    fun setPrivacy(privacy: Privacy) {
        val before = _privacy.value
        _privacy.value = privacy
        prefs?.edit()?.putString("privacy", privacyToJson(privacy).toString())?.apply()
        scope.launch {
            // What is no longer shared with someone is overwritten with nothing.
            fun narrowed(old: Audience, new: Audience) = new.ordinal < old.ordinal
            if (narrowed(before.nowPlaying, privacy.nowPlaying)) clearField(F_NOW, before.nowPlaying)
            if (narrowed(before.history, privacy.history)) clearField(F_HISTORY, before.history)
            if (narrowed(before.playlists, privacy.playlists)) clearField(F_PLAYLISTS, before.playlists)
            if (narrowed(before.stats, privacy.stats)) clearField(F_STATS, before.stats)
            if (before.discoverable != privacy.discoverable) publishProfile()
            publishLibrary()
            lastNow?.let { publishNow(it) }
        }
    }

    // ── Friends ──────────────────────────────────────────────────────────────────────────────────

    /** The pubkey (hex) in a friend code: an npub, a link containing one, or a 64-char hex key. */
    fun parseCode(code: String): String? {
        val text = code.trim()
        Regex("npub1[02-9ac-hj-np-z]{58}").find(text.lowercase())?.value?.let { npub ->
            val (hrp, bytes) = Nostr.bech32Decode(npub) ?: return null
            if (hrp == "npub" && Secp256k1.isValidPublicKey(bytes)) return bytes.toHex()
        }
        if (Regex("^[0-9a-fA-F]{64}$").matches(text) && Secp256k1.isValidPublicKey(text.hexToBytes())) return text.lowercase()
        return null
    }

    /** Adds [pubkey]; they get a request with this profile's name. */
    fun addFriend(pubkey: String, name: String = "") {
        val me = _profile.value ?: return
        if (pubkey == me.pubkey || _friends.value.any { it.pubkey == pubkey }) return
        val known = name.ifBlank { _data.value[pubkey]?.name ?: _requests.value.firstOrNull { it.pubkey == pubkey }?.name.orEmpty() }
        _friends.value = _friends.value + Friend(pubkey, known, System.currentTimeMillis())
        _requests.value = _requests.value.filterNot { it.pubkey == pubkey }
        saveFriends()
        scope.launch {
            publishTo(pubkey, F_HELLO, JSONObject().put("name", me.name).toString())
            publishLibrary(onlyFor = pubkey)
            prefs?.getString("portraitPacked", null)?.let { packed ->
                if (_privacy.value.stats != Audience.NOBODY) publishField(F_PORTRAIT, packed, _privacy.value.stats, pubkey)
            }
            lastNow?.let { publishNow(it) }
            restartLive()
        }
    }

    fun removeFriend(pubkey: String) {
        _friends.value = _friends.value.filterNot { it.pubkey == pubkey }
        saveFriends()
        if (pubkey in _pinned.value) togglePinned(pubkey)
        if (_listeningTo.value == pubkey) stopListenAlong()
        scope.launch {
            (DATA_FIELDS + F_HELLO).forEach { publishTo(pubkey, it, "") }
            restartLive()
        }
    }

    fun dismissRequest(pubkey: String) {
        _requests.value = _requests.value.filterNot { it.pubkey == pubkey }
        val dismissed = prefs?.getStringSet("dismissed", emptySet()).orEmpty() + pubkey
        prefs?.edit()?.putStringSet("dismissed", dismissed)?.apply()
    }

    private fun saveFriends() {
        val a = JSONArray()
        _friends.value.forEach { a.put(JSONObject().put("p", it.pubkey).put("n", it.name).put("t", it.addedAt)) }
        prefs?.edit()?.putString("friends", a.toString())?.apply()
    }

    // ── Live updates (friends' data, requests) ───────────────────────────────────────────────────

    private var liveUsers = 0
    private val liveSubs = mutableListOf<String>()

    /** Called by screens that show friends; updates flow while at least one is open. */
    @Synchronized
    fun startLive() {
        liveUsers++
        if (liveUsers == 1) openLive()
        scope.launch { publishLibraryIfStale(10 * 60_000L) }
    }

    @Synchronized
    fun stopLive() {
        liveUsers = (liveUsers - 1).coerceAtLeast(0)
        if (liveUsers == 0 && _listeningTo.value == null) closeLive()
    }

    @Synchronized
    private fun restartLive() {
        if (liveSubs.isEmpty()) return
        closeLive()
        openLive()
    }

    /** Newest friends' event applied in this run (created_at, s), and for which friends the live view last asked. */
    @Volatile
    private var newestSeen = 0L
    private var liveFriendKeys: Set<String> = emptySet()

    @Synchronized
    private fun openLive() {
        val me = _profile.value?.pubkey ?: return
        // Friends, the developer, and this profile itself (its public side, for "how others see me").
        val friendKeys = (_friends.value.map { it.pubkey } + DEVELOPER_PUBKEY + me).distinct()
        if (friendKeys.isNotEmpty()) {
            val dValues = JSONArray().put(D_PREFIX + F_PROFILE)
            DATA_FIELDS.forEach { dValues.put(D_PREFIX + it); dValues.put("$D_PREFIX$it:$me") }
            dValues.put("$D_PREFIX$F_VERIFIED:$me")
            if (_profile.value?.isDeveloper == true) _friends.value.forEach { dValues.put("$D_PREFIX$F_VERIFIED:${it.pubkey}") }
            val filter = JSONObject()
                .put("kinds", JSONArray().put(Nostr.KIND_APP_DATA))
                .put("authors", JSONArray(friendKeys))
                .put("#d", dValues)
            // Opened again in this run: what arrived before is still here, only newer events are asked for.
            if (newestSeen > 0 && liveFriendKeys == friendKeys.toSet()) filter.put("since", newestSeen - 60)
            liveFriendKeys = friendKeys.toSet()
            liveSubs += RelayPool.subscribe(listOf(filter), ::onEvent)
        }
        val toMe = JSONObject()
            .put("kinds", JSONArray().put(Nostr.KIND_APP_DATA))
            .put("#p", JSONArray().put(me))
            .put("since", System.currentTimeMillis() / 1000 - 60L * 24 * 3600)
        liveSubs += RelayPool.subscribe(listOf(toMe), ::onEvent)
    }

    @Synchronized
    private fun closeLive() {
        liveSubs.forEach(RelayPool::close)
        liveSubs.clear()
    }

    private fun onEvent(event: NostrEvent) {
        val me = _profile.value?.pubkey ?: return
        val sk = secretKey ?: return
        val d = event.tag("d") ?: return
        if (!d.startsWith(D_PREFIX)) return
        val parts = d.removePrefix(D_PREFIX).split(':')
        val field = parts[0]
        val personal = parts.getOrNull(1)
        if (field == F_VERIFIED && personal != null) {
            onVerification(event, me, sk, personal)
            return
        }
        if (personal != null && personal != me) return
        val content = if (personal != null && event.content.isNotEmpty()) {
            Nostr.decrypt(sk, event.pubkey, event.content) ?: return
        } else {
            event.content
        }
        val author = event.pubkey
        if (field == F_HELLO) {
            val isFriend = _friends.value.any { it.pubkey == author }
            val dismissed = prefs?.getStringSet("dismissed", emptySet()).orEmpty()
            if (content.isEmpty() || isFriend || author in dismissed) return
            val name = runCatching { JSONObject(content).optString("name") }.getOrDefault("")
            if (_requests.value.none { it.pubkey == author }) {
                _requests.value = _requests.value + FriendRequest(author, name, event.createdAt * 1000)
            }
            return
        }
        // Friends, and the developer's public profile which everyone sees.
        if (_friends.value.none { it.pubkey == author } && author != DEVELOPER_PUBKEY && author != me) return
        synchronized(this) {
            val current = _data.value[author] ?: FriendData()
            // The same event again (another relay, another opening of the screen): nothing to redo.
            if ((current.stamps[field] ?: 0L) >= event.createdAt) return
            newestSeen = maxOf(newestSeen, event.createdAt)
            val stamps = current.stamps + (field to event.createdAt)
            val json = content.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
            val updated = when (field) {
                F_PROFILE -> current.copy(
                    name = json?.optString("name")?.takeIf { it.isNotBlank() } ?: current.name,
                    avatar = json?.optString("pic")?.takeIf { it.isNotBlank() && it.length <= MAX_AVATAR_CHARS } ?: current.avatar,
                )
                F_NOW -> current.copy(now = json?.let(::nowFromJson))
                F_HISTORY -> current.copy(history = json?.optJSONArray("tracks")?.let(::refsFromJson))
                F_PLAYLISTS -> current.copy(playlists = json?.optJSONArray("playlists")?.let(::playlistsFromJson))
                F_STATS -> current.copy(stats = json?.let(::statsFromJson))
                F_LIKES -> current.copy(likes = content.takeIf { it.isNotBlank() }?.let(::unpackLikes))
                F_PORTRAIT -> current.copy(portrait = content.takeIf { it.isNotBlank() }?.let(com.metrolist.music.portrait.PortraitCodec::unpack))
                else -> return
            }.copy(stamps = stamps)
            _data.value = _data.value + (author to updated)
            if (field == F_PROFILE && updated.name != null) {
                val friend = _friends.value.firstOrNull { it.pubkey == author }
                if (friend != null && friend.name != updated.name) {
                    _friends.value = _friends.value.map { if (it.pubkey == author) it.copy(name = updated.name) else it }
                    saveFriends()
                }
            }
            if (field == F_NOW && _listeningTo.value == author) updated.now?.let { ListenAlong.follow(it) }
            if (field == F_LIKES) updated.likes?.let { FriendLikesSync.onLikes(author, it) }
        }
    }

    private val verificationStamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun onVerification(event: NostrEvent, me: String, sk: ByteArray, target: String) {
        // Only the newest word on a profile counts (a mark, then its removal).
        val key = "${event.pubkey}:$target"
        if ((verificationStamps[key] ?: 0L) > event.createdAt) return
        verificationStamps[key] = event.createdAt
        when {
            // To me, from the developer: the reviews open (or close again).
            event.pubkey == DEVELOPER_PUBKEY && target == me -> {
                val on = event.content.isNotEmpty() &&
                    Nostr.decrypt(sk, DEVELOPER_PUBKEY, event.content)
                        ?.let { runCatching { JSONObject(it).optInt("v") == 1 }.getOrDefault(false) } == true
                prefs?.edit()?.putBoolean("iAmVerified", on)?.apply()
                refreshAccess()
            }
            // The developer's own marks, read back on another phone with his key.
            event.pubkey == me && _profile.value?.isDeveloper == true -> {
                val on = event.content.isNotEmpty() &&
                    Nostr.decrypt(sk, target, event.content)
                        ?.let { runCatching { JSONObject(it).optInt("v") == 1 }.getOrDefault(false) } == true
                val updated = if (on) _verified.value + target else _verified.value - target
                if (updated != _verified.value) {
                    _verified.value = updated
                    prefs?.edit()?.putStringSet("verifiedSet", updated)?.apply()
                }
            }
        }
    }

    // ── Publishing ───────────────────────────────────────────────────────────────────────────────

    private fun publishTo(friend: String, field: String, content: String) {
        val sk = secretKey ?: return
        val encrypted = if (content.isEmpty()) "" else Nostr.encrypt(sk, friend, content)
        val tags = listOf(listOf("d", "$D_PREFIX$field:$friend"), listOf("p", friend))
        RelayPool.publish(Nostr.sign(sk, Nostr.KIND_APP_DATA, tags, encrypted))
    }

    private fun publishPublic(field: String, content: String) {
        val sk = secretKey ?: return
        val tags = listOf(listOf("d", D_PREFIX + field), listOf("t", "drxwnify"))
        RelayPool.publish(Nostr.sign(sk, Nostr.KIND_APP_DATA, tags, content))
    }

    private fun publishField(field: String, content: String, audience: Audience, onlyFor: String? = null) {
        when (audience) {
            Audience.NOBODY -> Unit
            Audience.EVERYONE -> if (onlyFor == null) publishPublic(field, content)
            Audience.FRIENDS -> _friends.value.map { it.pubkey }
                .filter { onlyFor == null || it == onlyFor }
                .take(MAX_FRIENDS_ENCRYPTED)
                .forEach { publishTo(it, field, content) }
            Audience.VERIFIED -> _verified.value
                .filter { onlyFor == null || it == onlyFor }
                .take(MAX_FRIENDS_ENCRYPTED)
                .forEach { publishTo(it, field, content) }
        }
    }

    private fun clearField(field: String, wasAudience: Audience) {
        when (wasAudience) {
            Audience.EVERYONE -> publishPublic(field, "")
            Audience.FRIENDS -> _friends.value.forEach { publishTo(it.pubkey, field, "") }
            Audience.VERIFIED -> _verified.value.forEach { publishTo(it, field, "") }
            Audience.NOBODY -> Unit
        }
    }

    private fun publishProfile() {
        val sk = secretKey ?: return
        val me = _profile.value ?: return
        publishPublic(
            F_PROFILE,
            JSONObject().put("name", me.name).put("find", _privacy.value.discoverable)
                .apply { me.avatar?.let { put("pic", it) } }.toString(),
        )
        val metadata = if (_privacy.value.discoverable) {
            JSONObject().put("name", me.name).put("about", "Drxwnify").toString()
        } else {
            "{}"
        }
        RelayPool.publish(Nostr.sign(sk, Nostr.KIND_METADATA, emptyList(), metadata))
    }

    @Volatile
    private var lastNow: NowPlaying? = null
    private var nowJob: Job? = null

    /** From the player: what plays now changed (track, play/pause, seek). Debounced. */
    fun playerChanged(player: Player, database: MusicDatabase) {
        if (secretKey == null) return
        val item = player.currentMediaItem ?: return
        val meta = item.metadata
        val mediaId = item.mediaId
        val position = player.currentPosition
        val playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
        val title = meta?.title ?: item.mediaMetadata.title?.toString().orEmpty()
        val artist = meta?.artists?.joinToString { it.name } ?: item.mediaMetadata.artist?.toString().orEmpty()
        val cover = meta?.thumbnailUrl ?: item.mediaMetadata.artworkUri?.toString()
        val duration = player.duration.takeIf { it > 0 } ?: ((meta?.duration ?: 0) * 1000L)
        nowJob?.cancel()
        nowJob = scope.launch {
            delay(NOW_DEBOUNCE_MS)
            val catalogId = runCatching {
                com.metrolist.music.playback.SpotifyMetadataRegistry.catalogIdOf(database, mediaId)
            }.getOrNull()?.takeUnless { it.startsWith("src:") }
            val now = NowPlaying(
                TrackRef(title, artist, catalogId, mediaId, cover, duration),
                positionMs = position,
                playing = playing,
                at = System.currentTimeMillis(),
                listenAlong = _privacy.value.listenAlong,
            )
            lastNow = now
            publishNow(now)
        }
    }

    private fun publishNow(now: NowPlaying) {
        publishField(F_NOW, nowToJson(now).toString(), _privacy.value.nowPlaying)
    }

    private suspend fun publishLibraryIfStale(maxAgeMs: Long) {
        if (secretKey == null) return
        val last = prefs?.getLong("libraryAt", 0L) ?: 0L
        if (System.currentTimeMillis() - last < maxAgeMs) return
        publishLibrary()
    }

    /** History, playlists and statistics, each to whoever the privacy settings allow. */
    suspend fun publishLibrary(onlyFor: String? = null) = withContext(Dispatchers.IO) {
        val db = database ?: return@withContext
        if (secretKey == null) return@withContext
        val privacy = _privacy.value
        runCatching {
            if (privacy.history != Audience.NOBODY) {
                val history = db.events().first()
                    .filter { com.metrolist.music.portrait.Listening.counts(it.event.playTime, it.song.song.duration * 1000L) }
                    .distinctBy { it.song.id }
                    .take(MAX_HISTORY)
                    .map { e -> refOf(db, e.song, e.event.timestamp.toInstant(ZoneOffset.UTC).toEpochMilli()) }
                publishField(F_HISTORY, JSONObject().put("tracks", refsToJson(history)).toString(), privacy.history, onlyFor)
            }
            if (privacy.playlists != Audience.NOBODY) {
                val playlists = db.playlistsByCreateDateAsc().first().take(MAX_PLAYLISTS).map { playlist ->
                    val songs = db.playlistSongs(playlist.id).first()
                    SharedPlaylist(
                        playlist.playlist.name,
                        songs.size,
                        songs.take(MAX_PLAYLIST_TRACKS).map { refOf(db, it.song, 0, withCover = false) },
                    )
                }
                publishField(F_PLAYLISTS, JSONObject().put("playlists", playlistsToJson(playlists)).toString(), privacy.playlists, onlyFor)
            }
            if (privacy.playlists != Audience.NOBODY) publishLikes(db, privacy.playlists, onlyFor)
            if (privacy.stats != Audience.NOBODY) {
                val weekAgo = LocalDateTime.now().minusDays(7)
                val weekEvents = db.events().first().filter { it.event.timestamp.isAfter(weekAgo) }
                val from = weekAgo.toInstant(ZoneOffset.UTC).toEpochMilli()
                val top = db.mostPlayedArtists(from, limit = 5).first().map { it.artist.name to it.songCount }
                val stats = Stats(
                    weekMinutes = (weekEvents.sumOf { it.event.playTime } / 60_000L).toInt(),
                    weekPlays = weekEvents.size,
                    likedCount = db.likedSongsCount().first(),
                    topArtists = top,
                )
                publishField(F_STATS, statsToJson(stats).toString(), privacy.stats, onlyFor)
            }
            if (onlyFor == null) prefs?.edit()?.putLong("libraryAt", System.currentTimeMillis())?.apply()
        }.onFailure { Timber.w(it, "FriendsHub: publishing the library failed") }
    }

    /**
     * The liked tracks, sent again only when they changed (or every few days, so relays keep them):
     * at up to 500 tracks it is the largest field, encrypted once per friend.
     */
    private suspend fun publishLikes(db: MusicDatabase, audience: Audience, onlyFor: String?) {
        val likes = runCatching { myLikes(db) }.getOrNull() ?: return
        val content = packLikesFitting(likes)
        val hash = content.hashCode()
        val p = prefs
        val fresh = p != null && p.getInt("likesHash", 0) == hash &&
            System.currentTimeMillis() - p.getLong("likesAt", 0L) < LIKES_REPUBLISH_MS
        if (onlyFor == null && fresh) return
        publishField(F_LIKES, content, audience, onlyFor)
        if (onlyFor == null) p?.edit()?.putInt("likesHash", hash)?.putLong("likesAt", System.currentTimeMillis())?.apply()
    }

    /** The newest liked tracks of the connected account; without one, the hearts in the app. */
    private suspend fun myLikes(db: MusicDatabase): SharedLikes? {
        val source = com.metrolist.music.catalog.Catalog.source
        if (source != null) {
            val cached = com.metrolist.music.catalog.LikedSongsCache.get(source.name)?.takeIf { it.tracks.isNotEmpty() }
            val (tracks, total) = if (cached != null) {
                cached.tracks.take(MAX_LIKES) to cached.total
            } else {
                val out = mutableListOf<com.metrolist.spotify.models.SpotifyTrack>()
                var total = 0
                var offset = 0
                while (out.size < MAX_LIKES) {
                    val page = com.metrolist.music.catalog.Catalog.likedSongs(limit = 50, offset = offset).getOrNull() ?: break
                    total = page.total
                    out += page.items.map { it.track }.filter { !it.isLocal }
                    offset += 50
                    if (page.items.isEmpty() || offset >= page.total) break
                }
                out.take(MAX_LIKES) to total
            }
            if (tracks.isEmpty()) return null
            return SharedLikes(maxOf(total, tracks.size), source.name, tracks.map(::refOfCatalog))
        }
        val liked = db.likedSongs(com.metrolist.music.constants.SongSortType.CREATE_DATE, true).first()
        if (liked.isEmpty()) return null
        return SharedLikes(liked.size, null, liked.take(MAX_LIKES).map { refOf(db, it, 0) })
    }

    private fun refOfCatalog(track: com.metrolist.spotify.models.SpotifyTrack): TrackRef {
        // A medium cover (~300 px): enough for a list, and far shorter to send than the largest one.
        val cover = track.album?.images?.let { images ->
            images.filter { (it.width ?: 0) in 200..400 }.minByOrNull { it.width ?: 0 }?.url
        } ?: com.metrolist.spotify.SpotifyMapper.getTrackThumbnail(track)
        return TrackRef(
            title = track.name.take(120),
            artist = track.artists.joinToString { it.name }.take(120),
            catalogId = track.id.takeIf { it.isNotBlank() },
            mediaId = null,
            cover = cover?.takeIf { it.startsWith("http") },
            durationMs = track.durationMs.toLong(),
        )
    }

    /** [packLikes] with as many of the newest tracks as fit in one relay event. */
    private fun packLikesFitting(likes: SharedLikes): String {
        var count = likes.tracks.size
        while (true) {
            val packed = packLikes(likes.copy(tracks = likes.tracks.take(count)))
            if (packed.length <= MAX_LIKES_PACKED || count <= 50) return packed
            count = count * 4 / 5
        }
    }

    /**
     * Rows of [title, artist, catalog id, seconds, cover prefix index, cover rest, media id] with the
     * covers' common beginnings listed once, gzipped: 500 tracks fit in one event (~40 KB).
     */
    private fun packLikes(likes: SharedLikes): String {
        val prefixes = mutableListOf<String>()
        val prefixIndex = HashMap<String, Int>()
        val rows = JSONArray()
        likes.tracks.forEach { t ->
            val cover = t.cover
            val cut = cover?.lastIndexOf('/') ?: -1
            val (index, rest) = if (cover != null && cut > 0) {
                val prefix = cover.substring(0, cut + 1)
                prefixIndex.getOrPut(prefix) { prefixes += prefix; prefixes.size - 1 } to cover.substring(cut + 1)
            } else {
                -1 to ""
            }
            rows.put(
                JSONArray().put(t.title).put(t.artist).put(t.catalogId.orEmpty()).put((t.durationMs / 1000).toInt())
                    .put(index).put(rest).put(t.mediaId.orEmpty()),
            )
        }
        val json = JSONObject().put("v", 1).put("total", likes.total).put("src", likes.source.orEmpty())
            .put("p", JSONArray(prefixes)).put("t", rows).toString()
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        return LIKES_PACKED_PREFIX + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun unpackLikes(content: String): SharedLikes? = runCatching {
        val text = if (content.startsWith(LIKES_PACKED_PREFIX)) {
            val bytes = android.util.Base64.decode(content.removePrefix(LIKES_PACKED_PREFIX), android.util.Base64.NO_WRAP)
            java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
        } else {
            content
        }
        val o = JSONObject(text)
        val prefixes = o.optJSONArray("p")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val rows = o.optJSONArray("t") ?: JSONArray()
        val tracks = (0 until rows.length()).mapNotNull { i ->
            val r = rows.optJSONArray(i) ?: return@mapNotNull null
            val index = r.optInt(4, -1)
            TrackRef(
                title = r.optString(0),
                artist = r.optString(1),
                catalogId = r.optString(2).takeIf { it.isNotBlank() },
                mediaId = r.optString(6).takeIf { it.isNotBlank() },
                cover = prefixes.getOrNull(index)?.let { it + r.optString(5) },
                durationMs = r.optInt(3) * 1000L,
            )
        }
        SharedLikes(o.optInt("total", tracks.size), o.optString("src").takeIf { it.isNotBlank() }, tracks)
    }.getOrNull()

    private suspend fun refOf(db: MusicDatabase, song: com.metrolist.music.db.entities.Song, at: Long, withCover: Boolean = true): TrackRef {
        val catalogId = runCatching {
            com.metrolist.music.playback.SpotifyMetadataRegistry.catalogIdOf(db, song.id)
        }.getOrNull()?.takeUnless { it.startsWith("src:") }
        return TrackRef(
            title = song.song.title.take(120),
            artist = song.artists.joinToString { it.name }.take(120),
            catalogId = catalogId,
            mediaId = song.id.takeUnless { it.startsWith("local:") },
            cover = if (withCover) song.song.thumbnailUrl?.takeIf { it.startsWith("http") } else null,
            durationMs = song.song.duration * 1000L,
            at = at,
        )
    }

    // ── Listen along ─────────────────────────────────────────────────────────────────────────────

    fun startListenAlong(pubkey: String) {
        _listeningTo.value = pubkey
        synchronized(this) { if (liveSubs.isEmpty()) openLive() }
        _data.value[pubkey]?.now?.let { ListenAlong.follow(it, force = true) }
    }

    fun stopListenAlong() {
        _listeningTo.value = null
        ListenAlong.reset()
        synchronized(this) { if (liveUsers == 0) closeLive() }
    }

    // ── JSON ─────────────────────────────────────────────────────────────────────────────────────

    private fun refToJson(r: TrackRef) = JSONObject().apply {
        put("t", r.title)
        put("a", r.artist)
        r.catalogId?.let { put("c", it) }
        r.mediaId?.let { put("m", it) }
        r.cover?.let { put("i", it) }
        if (r.durationMs > 0) put("d", r.durationMs)
        if (r.at > 0) put("at", r.at)
    }

    private fun refFromJson(o: JSONObject) = TrackRef(
        title = o.optString("t"),
        artist = o.optString("a"),
        catalogId = o.optString("c").takeIf { it.isNotBlank() },
        mediaId = o.optString("m").takeIf { it.isNotBlank() },
        cover = o.optString("i").takeIf { it.isNotBlank() },
        durationMs = o.optLong("d"),
        at = o.optLong("at"),
    )

    private fun refsToJson(list: List<TrackRef>) = JSONArray().apply { list.forEach { put(refToJson(it)) } }

    private fun refsFromJson(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::refFromJson) }

    private fun nowToJson(n: NowPlaying) = JSONObject()
        .put("track", refToJson(n.track))
        .put("pos", n.positionMs)
        .put("playing", n.playing)
        .put("at", n.at)
        .put("along", n.listenAlong)

    private fun nowFromJson(o: JSONObject): NowPlaying? = o.optJSONObject("track")?.let { t ->
        NowPlaying(refFromJson(t), o.optLong("pos"), o.optBoolean("playing"), o.optLong("at"), o.optBoolean("along", true))
    }

    private fun playlistsToJson(list: List<SharedPlaylist>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("n", it.name).put("total", it.total).put("tracks", refsToJson(it.tracks))) }
    }

    private fun playlistsFromJson(a: JSONArray) = (0 until a.length()).mapNotNull { i ->
        a.optJSONObject(i)?.let { SharedPlaylist(it.optString("n"), it.optInt("total"), it.optJSONArray("tracks")?.let(::refsFromJson).orEmpty()) }
    }

    private fun statsToJson(s: Stats) = JSONObject()
        .put("min", s.weekMinutes)
        .put("plays", s.weekPlays)
        .put("liked", s.likedCount)
        .put("top", JSONArray().apply { s.topArtists.forEach { (name, n) -> put(JSONObject().put("n", name).put("c", n)) } })

    private fun statsFromJson(o: JSONObject) = Stats(
        o.optInt("min"), o.optInt("plays"), o.optInt("liked"),
        o.optJSONArray("top")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { t -> t.optString("n") to t.optInt("c") } } }.orEmpty(),
    )

    private fun privacyToJson(p: Privacy) = JSONObject()
        .put("now", p.nowPlaying.name).put("history", p.history.name).put("playlists", p.playlists.name)
        .put("stats", p.stats.name).put("discoverable", p.discoverable).put("along", p.listenAlong)

    private fun privacyFromJson(o: JSONObject): Privacy {
        fun aud(key: String) = runCatching { Audience.valueOf(o.getString(key)) }.getOrDefault(Audience.FRIENDS)
        return Privacy(aud("now"), aud("history"), aud("playlists"), aud("stats"), o.optBoolean("discoverable", false), o.optBoolean("along", true))
    }

    private const val NOW_DEBOUNCE_MS = 1_500L
    private const val LIBRARY_REFRESH_MS = 6 * 60 * 60_000L
    private const val MAX_HISTORY = 50
    const val MAX_LIKES = 500
    /** Packed likes above this many characters lose their oldest tracks (encryption adds a third). */
    private const val MAX_LIKES_PACKED = 45_000
    private const val LIKES_PACKED_PREFIX = "gz1:"
    private const val LIKES_REPUBLISH_MS = 3L * 24 * 60 * 60_000
    private const val LIKES_FETCH_MS = 60 * 60_000L
    private const val PORTRAIT_REFRESH_MS = 20 * 60 * 60_000L
    private const val SEARCH_WAIT_MS = 4_000L
    private const val MAX_PLAYLISTS = 10
    private const val MAX_PLAYLIST_TRACKS = 50
    private const val MAX_FRIENDS_ENCRYPTED = 40
    private const val AVATAR_PX = 160
    private const val MAX_AVATAR_CHARS = 60_000
}
