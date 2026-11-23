/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.friends

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.metrolist.music.utils.AppLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Track in a collaborative playlist.
 * Tracks are never deleted after playing; [played] marks whether it has played.
 * [colorHex] holds the chosen color from the 10-option palette (null if cleared/none).
 */
data class CollabTrack(
    val id: String,
    val title: String,
    val artist: String,
    val cover: String? = null,
    val durationMs: Long = 0,
    val addedByPubkey: String,
    val addedByName: String,
    val addedByAvatar: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val colorHex: String? = null,
    val played: Boolean = false,
) {
    fun toTrackRef(): FriendsHub.TrackRef = FriendsHub.TrackRef(
        title = title,
        artist = artist,
        catalogId = id.takeUnless { it.startsWith(AppLinks.YOUTUBE_PREFIX) },
        mediaId = id.removePrefix(AppLinks.YOUTUBE_PREFIX).takeIf { id.startsWith(AppLinks.YOUTUBE_PREFIX) },
        cover = cover,
        durationMs = durationMs,
        at = addedAt,
    )
}

/**
 * A collaborative playlist (Party Queue / Общий плейлист).
 * At most 3 per user.
 */
data class CollabPlaylist(
    val id: String, // "1", "2", "3"
    val name: String,
    val ownerPubkey: String,
    val roundRobin: Boolean = true,
    val allowByLink: Boolean = true, // Default: Friends + anyone with link
    val maxTracksPerUser: Int = 5,
    val tracks: List<CollabTrack> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val totalCount: Int get() = tracks.size
    val contributorsCount: Int get() = tracks.map { it.addedByPubkey }.distinct().size
}

/**
 * 10 Palette choices: 9 vibrant colors + 1 clear (null).
 */
object CollabPalette {
    val COLORS = listOf(
        Color(0xFFE53935), // 1. Red
        Color(0xFFFB8C00), // 2. Orange
        Color(0xFFFDD835), // 3. Yellow
        Color(0xFF43A047), // 4. Green
        Color(0xFF00897B), // 5. Teal / Mint
        Color(0xFF1E88E5), // 6. Blue
        Color(0xFF8E24AA), // 7. Purple
        Color(0xFFD81B60), // 8. Pink
        Color(0xFF8D6E63), // 9. Brown / Terracotta
    )

    fun hexOf(color: Color): String = String.format("#%06X", (0xFFFFFF and color.toArgb()))

    fun colorOf(hex: String?): Color? {
        if (hex.isNullOrBlank()) return null
        return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
    }
}

/**
 * Collaborative playlist manager using Nostr relays.
 * Zero Cloudflare usage, real-time push over WebSocket, cryptographically authenticated by creator.
 */
object CollabPlaylistManager {
    const val MAX_COLLAB_PLAYLISTS = 3
    const val DEFAULT_MAX_TRACKS_PER_USER = 5
    private const val D_COLLAB_PREFIX = "drxw:collab:"
    private const val TAG_COLLAB_REQ = "drxw_collab_req"
    /** Requests are picked up when the owner was away up to a week. */
    private const val REQUEST_WINDOW_S = 7 * 24 * 3600L
    private const val MAX_HANDLED_REQUESTS = 2000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null

    private val _myPlaylists = MutableStateFlow<List<CollabPlaylist>>(emptyList())
    val myPlaylists: StateFlow<List<CollabPlaylist>> = _myPlaylists.asStateFlow()

    // Remote playlists being watched: (ownerPubkey:playlistId -> Flow)
    private val remotePlaylists = mutableMapOf<String, MutableStateFlow<CollabPlaylist?>>()
    private val remoteSubs = mutableMapOf<String, String>()

    private var reqSubId: String? = null

    /**
     * Every change to [_myPlaylists] reads, edits and writes the list: two requests arriving at once
     * (relays answer on their own threads) each started from the same list and one of them was lost.
     */
    private val lock = Any()

    /**
     * Ids of the request events already applied. Relays send the last days' requests again at every
     * start, and each of them used to be added again: the same tracks piled up after each restart.
     */
    private val handledRequests = LinkedHashSet<String>()

    @Volatile private var lastNoted: String? = null

    /** Per watched playlist, the newest version seen: relays also hand out older copies. */
    private val newestSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun init(context: Context) {
        val p = context.getSharedPreferences("collab_playlists", Context.MODE_PRIVATE)
        prefs = p
        p.getString("handled_requests", null)?.split(',')?.filter { it.isNotBlank() }?.let { synchronized(lock) { handledRequests.addAll(it) } }
        loadSavedPlaylists()
        listenForRequests()
    }

    private fun myPubkey(): String? = FriendsHub.profile.value?.pubkey
    private fun mySecretKey(): ByteArray? = FriendsHub.secretKey

    private fun loadSavedPlaylists() {
        val p = prefs ?: return
        val raw = p.getString("playlists", "[]") ?: "[]"
        val list = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::playlistFromJson) }
        }.getOrDefault(emptyList())
        _myPlaylists.value = list
    }

    private fun saveHandled() {
        val p = prefs ?: return
        val ids = synchronized(lock) {
            while (handledRequests.size > MAX_HANDLED_REQUESTS) handledRequests.remove(handledRequests.first())
            handledRequests.joinToString(",")
        }
        p.edit().putString("handled_requests", ids).apply()
    }

    /** Applies [change] to playlist [id] of this profile under [lock]; saves and publishes the result. */
    private inline fun mutateOwn(id: String, change: (CollabPlaylist) -> CollabPlaylist?): CollabPlaylist? {
        val updated = synchronized(lock) {
            val current = _myPlaylists.value
            val target = current.firstOrNull { it.id == id } ?: return null
            val next = change(target)?.copy(updatedAt = System.currentTimeMillis()) ?: return null
            _myPlaylists.value = current.map { if (it.id == id) next else it }
            next
        }
        savePlaylists()
        notifyLocalPlaylistUpdated(updated)
        publishPlaylistState(updated)
        return updated
    }

    private fun savePlaylists() {
        val p = prefs ?: return
        val a = JSONArray()
        _myPlaylists.value.forEach { a.put(playlistToJson(it)) }
        p.edit().putString("playlists", a.toString()).apply()
    }

    /**
     * Creates a new collaborative playlist for this profile (at most [MAX_COLLAB_PLAYLISTS]).
     */
    fun createPlaylist(name: String, roundRobin: Boolean = true, allowByLink: Boolean = true): CollabPlaylist? {
        val me = myPubkey() ?: return null
        val current = _myPlaylists.value
        if (current.size >= MAX_COLLAB_PLAYLISTS) return null

        // A fresh random id: "1", "2", "3" were handed out again after a delete, so the old playlist's
        // links (and requests still on the relays) landed in the new one.
        val nextId = java.util.UUID.randomUUID().toString().replace("-", "").take(10)

        val playlist = CollabPlaylist(
            id = nextId,
            name = name.ifBlank { "Playlist $nextId" },
            ownerPubkey = me,
            roundRobin = roundRobin,
            allowByLink = allowByLink,
            maxTracksPerUser = DEFAULT_MAX_TRACKS_PER_USER,
            tracks = emptyList(),
            updatedAt = System.currentTimeMillis(),
        )

        _myPlaylists.value = current + playlist
        savePlaylists()
        publishPlaylistState(playlist)
        listenForRequests()
        return playlist
    }

    fun deletePlaylist(id: String) {
        val current = _myPlaylists.value
        val toDelete = current.firstOrNull { it.id == id } ?: return
        _myPlaylists.value = current.filterNot { it.id == id }
        savePlaylists()

        // Clear state on relays
        val sk = mySecretKey() ?: return
        val tags = listOf(listOf("d", "$D_COLLAB_PREFIX$id"), listOf("t", "drxwnify"), listOf("t", "collab"))
        RelayPool.publish(Nostr.sign(sk, Nostr.KIND_APP_DATA, tags, ""))
    }

    private fun notifyLocalPlaylistUpdated(updated: CollabPlaylist) {
        val me = myPubkey() ?: return
        remotePlaylists["$me:${updated.id}"]?.value = updated
    }

    fun updateSettings(
        id: String,
        name: String? = null,
        roundRobin: Boolean? = null,
        allowByLink: Boolean? = null,
        maxTracksPerUser: Int? = null,
    ) {
        mutateOwn(id) { target ->
            target.copy(
                name = name ?: target.name,
                roundRobin = roundRobin ?: target.roundRobin,
                allowByLink = allowByLink ?: target.allowByLink,
                maxTracksPerUser = maxTracksPerUser ?: target.maxTracksPerUser,
            )
        }
    }

    /**
     * Add tracks to own playlist directly in batch.
     */
    fun addTracksToOwn(playlistId: String, newTracks: List<CollabTrack>): Boolean {
        if (newTracks.isEmpty()) return false
        return mutateOwn(playlistId) { it.copy(tracks = it.tracks + newTracks) } != null
    }

    /**
     * Add track to own playlist directly.
     */
    fun addTrackToOwn(playlistId: String, track: CollabTrack): Boolean =
        addTracksToOwn(playlistId, listOf(track))

    /**
     * Remove track by index in own playlist.
     */
    fun removeTrackFromOwn(playlistId: String, index: Int) {
        mutateOwn(playlistId) { target ->
            if (index !in target.tracks.indices) return@mutateOwn null
            target.copy(tracks = target.tracks.toMutableList().apply { removeAt(index) })
        }
    }

    /** Removes several tracks at once (their indices in [CollabPlaylist.tracks]): one publish, not one each. */
    fun removeTracksFromOwn(playlistId: String, indices: Set<Int>) {
        if (indices.isEmpty()) return
        mutateOwn(playlistId) { target -> target.copy(tracks = target.tracks.filterIndexed { i, _ -> i !in indices }) }
    }

    /**
     * Reorder tracks in own playlist.
     */
    fun moveTrackInOwn(playlistId: String, fromIndex: Int, toIndex: Int) {
        mutateOwn(playlistId) { target ->
            if (fromIndex !in target.tracks.indices || toIndex !in target.tracks.indices) return@mutateOwn null
            val updatedTracks = target.tracks.toMutableList()
            updatedTracks.add(toIndex, updatedTracks.removeAt(fromIndex))
            target.copy(tracks = updatedTracks)
        }
    }

    /**
     * Applies color to chosen track indices.
     * [colorHex] is a hex string (e.g. "#E53935"), or null to clear color (10th choice).
     */
    fun applyColor(playlistId: String, indices: Set<Int>, colorHex: String?) {
        mutateOwn(playlistId) { target ->
            target.copy(tracks = target.tracks.mapIndexed { idx, track -> if (idx in indices) track.copy(colorHex = colorHex) else track })
        }
    }

    /**
     * Marks track as played or unplayed without removing it from the list.
     */
    fun setTrackPlayed(playlistId: String, index: Int, played: Boolean) {
        mutateOwn(playlistId) { target ->
            if (index !in target.tracks.indices || target.tracks[index].played == played) return@mutateOwn null
            target.copy(tracks = target.tracks.toMutableList().apply { this[index] = this[index].copy(played = played) })
        }
    }

    /**
     * The owner's player reached a track of one of their playlists: it counts as played, which frees
     * its sender's slot (the per-person limit counts only tracks still waiting). Matched by title and
     * artist, as the player knows the track under its own id.
     */
    fun notePlaying(title: String, artist: String) {
        if (title.isBlank() || _myPlaylists.value.isEmpty()) return
        // Once per track start: the player reports the same track many times (pause, seek, buffering).
        if (lastNoted == "$artist|$title") return
        lastNoted = "$artist|$title"
        fun norm(v: String) = v.lowercase().filter { it.isLetterOrDigit() }
        val t = norm(title)
        val a = norm(artist)
        _myPlaylists.value.forEach { pl ->
            val idx = pl.tracks.indexOfFirst { !it.played && norm(it.title) == t && (a.isEmpty() || norm(it.artist).let { x -> x.contains(a) || a.contains(x) }) }
            if (idx >= 0) setTrackPlayed(pl.id, idx, true)
        }
    }

    /**
     * Fair Round-Robin Queue:
     * Interweaves tracks by contributor in turns so everyone gets heard equally.
     * If [CollabPlaylist.roundRobin] is false, returns chronological list.
     */
    fun computeFairQueue(playlist: CollabPlaylist): List<CollabTrack> {
        if (!playlist.roundRobin || playlist.tracks.size <= 1) return playlist.tracks

        val byUser = playlist.tracks.groupBy { it.addedByPubkey }.values.map { it.toMutableList() }
        val result = ArrayList<CollabTrack>(playlist.tracks.size)
        var round = 0
        while (result.size < playlist.tracks.size) {
            var addedAny = false
            for (userList in byUser) {
                if (round < userList.size) {
                    result.add(userList[round])
                    addedAny = true
                }
            }
            if (!addedAny) break
            round++
        }
        return result
    }

    /**
     * Publishes canonical playlist state to Nostr relays (NIP-78).
     */
    private fun publishPlaylistState(playlist: CollabPlaylist) {
        val sk = mySecretKey() ?: return
        val tags = listOf(
            listOf("d", "$D_COLLAB_PREFIX${playlist.id}"),
            listOf("t", "drxwnify"),
            listOf("t", "collab"),
        )
        val content = playlistToJson(playlist).toString()
        val event = Nostr.sign(sk, Nostr.KIND_APP_DATA, tags, content)
        RelayPool.publish(event)
    }

    /**
     * Listen for incoming track requests from friends/guests on Nostr.
     */
    fun listenForRequests() {
        val me = myPubkey() ?: return
        if (_myPlaylists.value.isEmpty()) {
            reqSubId?.let { RelayPool.close(it); reqSubId = null }
            return
        }
        if (reqSubId != null) return

        val filter = JSONObject()
            .put("kinds", JSONArray().put(Nostr.KIND_APP_DATA))
            .put("#p", JSONArray().put(me))
            .put("#t", JSONArray().put(TAG_COLLAB_REQ))
            .put("since", System.currentTimeMillis() / 1000 - REQUEST_WINDOW_S)

        reqSubId = RelayPool.subscribe(listOf(filter)) { event ->
            handleIncomingRequest(event)
        }
    }

    private fun handleIncomingRequest(event: NostrEvent) {
        if (!event.isValid()) return
        if (synchronized(lock) { !handledRequests.add(event.id) }) return
        saveHandled()
        val senderPubkey = event.pubkey
        val json = runCatching { JSONObject(event.content) }.getOrNull() ?: return
        val playlistId = json.optString("playlistId")
        val action = json.optString("action")
        val playlist = _myPlaylists.value.firstOrNull { it.id == playlistId } ?: return

        // Check access: if allowByLink is false, must be friend
        if (!playlist.allowByLink) {
            val isFriend = FriendsHub.friends.value.any { it.pubkey == senderPubkey } ||
                    FriendsHub.isDeveloper(senderPubkey)
            if (!isFriend) return
        }

        when (action) {
            "add", "add_batch" -> {
                val senderName = json.optString("senderName", "Friend").take(40)
                val senderAvatar = json.optString("senderAvatar").takeIf { it.startsWith("http") }
                val incoming = if (action == "add") {
                    listOfNotNull(json.optJSONObject("track"))
                } else {
                    val arr = json.optJSONArray("tracks") ?: JSONArray()
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                }
                mutateOwn(playlistId) { current ->
                    val waiting = current.tracks.count { it.addedByPubkey == senderPubkey && !it.played }
                    val slots = (current.maxTracksPerUser - waiting).coerceAtLeast(0)
                    val now = System.currentTimeMillis()
                    val added = incoming.take(slots).mapIndexedNotNull { i, o ->
                        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                        CollabTrack(
                            id = id,
                            title = o.optString("t", "Unknown").take(200),
                            artist = o.optString("a", "Unknown").take(200),
                            cover = o.optString("c").takeIf { it.startsWith("http") },
                            durationMs = o.optLong("d", 0L),
                            addedByPubkey = senderPubkey,
                            addedByName = senderName,
                            addedByAvatar = senderAvatar,
                            addedAt = now + i,
                        )
                    }
                    if (added.isEmpty()) null else current.copy(tracks = current.tracks + added)
                }
            }
            "remove" -> {
                val trackId = json.optString("trackId")
                if (trackId.isBlank()) return
                // Contributor can only remove their own unplayed track
                mutateOwn(playlistId) { current ->
                    val idx = current.tracks.indexOfFirst { it.id == trackId && it.addedByPubkey == senderPubkey && !it.played }
                    if (idx < 0) null else current.copy(tracks = current.tracks.toMutableList().apply { removeAt(idx) })
                }
            }
        }
    }

    /**
     * Watch a remote collaborative playlist owned by [ownerPubkey] with [id].
     * Returns a [StateFlow] of the playlist state.
     */
    fun watchPlaylist(ownerPubkey: String, id: String): StateFlow<CollabPlaylist?> {
        val key = "$ownerPubkey:$id"
        val existing = remotePlaylists[key]
        if (existing != null) return existing

        val flow = MutableStateFlow<CollabPlaylist?>(null)
        remotePlaylists[key] = flow

        // If it's my own, immediately provide from _myPlaylists
        if (ownerPubkey == myPubkey()) {
            val own = _myPlaylists.value.firstOrNull { it.id == id }
            flow.value = own
        }

        // Subscribe to Nostr relays
        val filter = JSONObject()
            .put("kinds", JSONArray().put(Nostr.KIND_APP_DATA))
            .put("authors", JSONArray().put(ownerPubkey))
            .put("#d", JSONArray().put("$D_COLLAB_PREFIX$id"))

        val subId = RelayPool.subscribe(listOf(filter)) { event ->
            if (event.pubkey == ownerPubkey && event.isValid()) {
                // Each relay sends the copy it has; an older one arriving last showed an old list.
                val newer = newestSeen.compute(key) { _, seen -> maxOf(seen ?: 0L, event.createdAt) } == event.createdAt
                if (!newer) return@subscribe
                // An empty event is the playlist deleted by its owner.
                flow.value = runCatching { JSONObject(event.content) }.getOrNull()?.let(::playlistFromJson)
            }
        }
        remoteSubs[key] = subId
        return flow
    }

    fun unwatchPlaylist(ownerPubkey: String, id: String) {
        val key = "$ownerPubkey:$id"
        remoteSubs.remove(key)?.let { RelayPool.close(it) }
        remotePlaylists.remove(key)
        newestSeen.remove(key)
    }

    /**
     * Send an add-tracks request to the host in batch.
     */
    fun sendAddTracksRequest(
        ownerPubkey: String,
        playlistId: String,
        tracks: List<FriendsHub.TrackRef>,
    ) {
        if (tracks.isEmpty()) return
        val sk = mySecretKey() ?: return
        val me = FriendsHub.profile.value ?: return

        // If I am the owner, add directly in batch
        if (ownerPubkey == me.pubkey) {
            val collabTracks = tracks.mapIndexed { idx, track ->
                CollabTrack(
                    id = track.catalogId ?: (AppLinks.YOUTUBE_PREFIX + track.mediaId.orEmpty()),
                    title = track.title,
                    artist = track.artist,
                    cover = track.cover,
                    durationMs = track.durationMs,
                    addedByPubkey = me.pubkey,
                    addedByName = me.name,
                    addedByAvatar = me.avatar,
                    addedAt = System.currentTimeMillis() + idx,
                )
            }
            addTracksToOwn(playlistId, collabTracks)
            return
        }

        val payload = JSONObject().apply {
            put("action", "add_batch")
            put("playlistId", playlistId)
            put("senderPubkey", me.pubkey)
            put("senderName", me.name)
            me.avatar?.let { put("senderAvatar", it) }
            val arr = JSONArray()
            tracks.forEach { track ->
                arr.put(JSONObject().apply {
                    put("id", track.catalogId ?: (AppLinks.YOUTUBE_PREFIX + track.mediaId.orEmpty()))
                    put("t", track.title)
                    put("a", track.artist)
                    track.cover?.let { put("c", it) }
                    put("d", track.durationMs)
                })
            }
            put("tracks", arr)
        }

        RelayPool.publish(Nostr.sign(sk, Nostr.KIND_APP_DATA, requestTags(ownerPubkey, playlistId), payload.toString()))
    }

    /**
     * A request's tags. Its own "d": app data is replaceable per author and "d", so without one each
     * new request of a guest replaced the previous one on the relays — of several sent while the
     * owner was away only the last arrived.
     */
    private fun requestTags(ownerPubkey: String, playlistId: String): List<List<String>> = listOf(
        listOf("d", "drxw:collabreq:" + java.util.UUID.randomUUID().toString()),
        listOf("p", ownerPubkey),
        listOf("t", TAG_COLLAB_REQ),
        listOf("collab_id", playlistId),
    )

    /**
     * Send an add-track request to the host.
     */
    fun sendAddTrackRequest(
        ownerPubkey: String,
        playlistId: String,
        track: FriendsHub.TrackRef,
    ) {
        sendAddTracksRequest(ownerPubkey, playlistId, listOf(track))
    }

    /**
     * Send a remove-track request to the host (for contributor's own track).
     */
    fun sendRemoveTrackRequest(ownerPubkey: String, playlistId: String, trackId: String) {
        val sk = mySecretKey() ?: return
        val me = myPubkey() ?: return

        if (ownerPubkey == me) {
            val pl = _myPlaylists.value.firstOrNull { it.id == playlistId } ?: return
            val idx = pl.tracks.indexOfFirst { it.id == trackId }
            if (idx >= 0) removeTrackFromOwn(playlistId, idx)
            return
        }

        val payload = JSONObject().apply {
            put("action", "remove")
            put("playlistId", playlistId)
            put("trackId", trackId)
            put("senderPubkey", me)
        }

        RelayPool.publish(Nostr.sign(sk, Nostr.KIND_APP_DATA, requestTags(ownerPubkey, playlistId), payload.toString()))
    }

    fun generateShareLink(ownerPubkey: String, playlistId: String, title: String): String {
        return "${AppLinks.SITE}/collab/$ownerPubkey/$playlistId?n=${android.net.Uri.encode(title)}"
    }

    fun parseCollabLink(uri: android.net.Uri): Pair<String, String>? {
        val isSite = uri.host == "drxwnify.pages.dev" && uri.path.orEmpty().startsWith("/collab/")
        val isScheme = uri.scheme == "drxwnify" && (uri.host == "collab" || uri.path.orEmpty().startsWith("/collab"))
        if (!isSite && !isScheme) return null

        val segments = uri.pathSegments.orEmpty()
        return if (isSite) {
            // /collab/<pubkey>/<id>
            val pub = segments.getOrNull(1) ?: return null
            val id = segments.getOrNull(2) ?: return null
            pub to id
        } else {
            // drxwnify://collab/<pubkey>/<id>
            val pub = segments.getOrNull(0) ?: uri.host.takeIf { it != "collab" } ?: return null
            val id = segments.getOrNull(1) ?: return null
            pub to id
        }
    }

    // ── JSON Codec ─────────────────────────────────────────────────────────────────────────────

    fun playlistToJson(p: CollabPlaylist): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("owner", p.ownerPubkey)
        put("rr", p.roundRobin)
        put("link", p.allowByLink)
        put("max", p.maxTracksPerUser)
        put("updated", p.updatedAt)
        val arr = JSONArray()
        p.tracks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("t", t.title)
                put("a", t.artist)
                t.cover?.let { put("c", it) }
                put("d", t.durationMs)
                put("by", t.addedByPubkey)
                put("by_n", t.addedByName)
                t.addedByAvatar?.let { put("by_a", it) }
                put("at", t.addedAt)
                t.colorHex?.let { put("col", it) }
                put("p", t.played)
            })
        }
        put("tracks", arr)
    }

    fun playlistFromJson(o: JSONObject): CollabPlaylist? = runCatching {
        val tracksArr = o.optJSONArray("tracks") ?: JSONArray()
        val tracks = (0 until tracksArr.length()).mapNotNull { i ->
            tracksArr.optJSONObject(i)?.let { t ->
                CollabTrack(
                    id = t.getString("id"),
                    title = t.optString("t", "Unknown"),
                    artist = t.optString("a", "Unknown"),
                    cover = t.optString("c").takeIf { it.isNotBlank() },
                    durationMs = t.optLong("d", 0L),
                    addedByPubkey = t.optString("by"),
                    addedByName = t.optString("by_n", "Friend"),
                    addedByAvatar = t.optString("by_a").takeIf { it.isNotBlank() },
                    addedAt = t.optLong("at", 0L),
                    colorHex = t.optString("col").takeIf { it.isNotBlank() },
                    played = t.optBoolean("p", false),
                )
            }
        }
        CollabPlaylist(
            id = o.getString("id"),
            name = o.optString("name", "Shared Playlist"),
            ownerPubkey = o.getString("owner"),
            roundRobin = o.optBoolean("rr", true),
            allowByLink = o.optBoolean("link", true),
            maxTracksPerUser = o.optInt("max", DEFAULT_MAX_TRACKS_PER_USER),
            tracks = tracks,
            updatedAt = o.optLong("updated", System.currentTimeMillis()),
        )
    }.getOrNull()
}
