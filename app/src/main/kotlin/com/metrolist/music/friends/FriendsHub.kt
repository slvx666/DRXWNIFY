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
    enum class Audience { NOBODY, FRIENDS, EVERYONE }

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

    data class Profile(val pubkey: String, val name: String) {
        val code: String get() = Nostr.bech32Encode("npub", pubkey.hexToBytes())
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

    data class Stats(val weekMinutes: Int, val weekPlays: Int, val likedCount: Int, val topArtists: List<Pair<String, Int>>)

    data class FriendData(
        val name: String? = null,
        val now: NowPlaying? = null,
        val history: List<TrackRef>? = null,
        val playlists: List<SharedPlaylist>? = null,
        val stats: Stats? = null,
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
    private val DATA_FIELDS = listOf(F_NOW, F_HISTORY, F_PLAYLISTS, F_STATS)

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
                _profile.value = Profile(Secp256k1.publicKey(sk).toHex(), p.getString("name", null).orEmpty())
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
        // What friends see is refreshed now and then, never more than every few hours.
        if (secretKey != null) scope.launch {
            delay(20_000L)
            publishLibraryIfStale(LIBRARY_REFRESH_MS)
        }
    }

    // ── Profile ──────────────────────────────────────────────────────────────────────────────────

    fun createProfile(name: String) {
        val sk = Secp256k1.newPrivateKey()
        saveKey(sk, name.trim())
        scope.launch { publishProfile(); publishLibrary() }
    }

    /** Restores a profile from its backup key (nsec); false when the key is not valid. */
    fun importProfile(nsec: String, name: String): Boolean {
        val (hrp, bytes) = Nostr.bech32Decode(nsec) ?: return false
        if (hrp != "nsec" || bytes.size != 32) return false
        saveKey(bytes, name.trim())
        scope.launch { publishProfile(); publishLibrary() }
        return true
    }

    fun rename(name: String) {
        val sk = secretKey ?: return
        saveKey(sk, name.trim())
        scope.launch { publishProfile() }
    }

    /** The backup key: whoever has it IS this profile — shown only on request. */
    fun backupKey(): String? = secretKey?.let { Nostr.bech32Encode("nsec", it) }

    private fun saveKey(sk: ByteArray, name: String) {
        secretKey = sk
        prefs?.edit()?.putString("sk", sk.toHex())?.putString("name", name)?.apply()
        _profile.value = Profile(Secp256k1.publicKey(sk).toHex(), name)
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
            lastNow?.let { publishNow(it) }
            restartLive()
        }
    }

    fun removeFriend(pubkey: String) {
        _friends.value = _friends.value.filterNot { it.pubkey == pubkey }
        saveFriends()
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

    @Synchronized
    private fun openLive() {
        val me = _profile.value?.pubkey ?: return
        val friendKeys = _friends.value.map { it.pubkey }
        if (friendKeys.isNotEmpty()) {
            val dValues = JSONArray().put(D_PREFIX + F_PROFILE)
            DATA_FIELDS.forEach { dValues.put(D_PREFIX + it); dValues.put("$D_PREFIX$it:$me") }
            val filter = JSONObject()
                .put("kinds", JSONArray().put(Nostr.KIND_APP_DATA))
                .put("authors", JSONArray(friendKeys))
                .put("#d", dValues)
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
        if (_friends.value.none { it.pubkey == author }) return
        synchronized(this) {
            val current = _data.value[author] ?: FriendData()
            if ((current.stamps[field] ?: 0L) > event.createdAt) return
            val stamps = current.stamps + (field to event.createdAt)
            val json = content.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
            val updated = when (field) {
                F_PROFILE -> current.copy(name = json?.optString("name")?.takeIf { it.isNotBlank() } ?: current.name)
                F_NOW -> current.copy(now = json?.let(::nowFromJson))
                F_HISTORY -> current.copy(history = json?.optJSONArray("tracks")?.let(::refsFromJson))
                F_PLAYLISTS -> current.copy(playlists = json?.optJSONArray("playlists")?.let(::playlistsFromJson))
                F_STATS -> current.copy(stats = json?.let(::statsFromJson))
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
        }
    }

    private fun clearField(field: String, wasAudience: Audience) {
        when (wasAudience) {
            Audience.EVERYONE -> publishPublic(field, "")
            Audience.FRIENDS -> _friends.value.forEach { publishTo(it.pubkey, field, "") }
            Audience.NOBODY -> Unit
        }
    }

    private fun publishProfile() {
        val sk = secretKey ?: return
        val me = _profile.value ?: return
        publishPublic(F_PROFILE, JSONObject().put("name", me.name).toString())
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
    private const val MAX_PLAYLISTS = 10
    private const val MAX_PLAYLIST_TRACKS = 50
    private const val MAX_FRIENDS_ENCRYPTED = 40
}
