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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null

    private val _myPlaylists = MutableStateFlow<List<CollabPlaylist>>(emptyList())
    val myPlaylists: StateFlow<List<CollabPlaylist>> = _myPlaylists.asStateFlow()

    // Remote playlists being watched: (ownerPubkey:playlistId -> Flow)
    private val remotePlaylists = mutableMapOf<String, MutableStateFlow<CollabPlaylist?>>()
    private val remoteSubs = mutableMapOf<String, String>()

    private var reqSubId: String? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences("collab_playlists", Context.MODE_PRIVATE)
        prefs = p
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

        val usedIds = current.map { it.id }.toSet()
        val nextId = (1..MAX_COLLAB_PLAYLISTS).firstOrNull { it.toString() !in usedIds }?.toString() ?: return null

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
        val current = _myPlaylists.value
        val target = current.firstOrNull { it.id == id } ?: return
        val updated = target.copy(
            name = name ?: target.name,
            roundRobin = roundRobin ?: target.roundRobin,
            allowByLink = allowByLink ?: target.allowByLink,
            maxTracksPerUser = maxTracksPerUser ?: target.maxTracksPerUser,
            updatedAt = System.currentTimeMillis(),
        )
        _myPlaylists.value = current.map { if (it.id == id) updated else it }
        savePlaylists()
        notifyLocalPlaylistUpdated(updated)
        publishPlaylistState(updated)
    }

    /**
     * Add tracks to own playlist directly in batch.
     */
    fun addTracksToOwn(playlistId: String, newTracks: List<CollabTrack>): Boolean {
        if (newTracks.isEmpty()) return false
        val current = _myPlaylists.value
        val target = current.firstOrNull { it.id == playlistId } ?: return false
        val updated = target.copy(
            tracks = target.tracks + newTracks,
            updatedAt = System.currentTimeMillis(),
        )
        _myPlaylists.value = current.map { if (it.id == playlistId) updated else it }
        savePlaylists()
        notifyLocalPlaylistUpdated(updated)
        publishPlaylistState(updated)
        return true
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
        val current = _myPlaylists.value
        val target = current.firstOrNull { it.id == playlistId } ?: return
        if (index !in target.tracks.indices) return
        val updatedTracks = target.tracks.toMutableList().apply { removeAt(index) }
        val updated = target.copy(tracks = updatedTracks, updatedAt = System.currentTimeMillis())
        _myPlaylists.value = current.map { if (it.id == playlistId) updated else it }
        savePlaylists()
        notifyLocalPlaylistUpdated(updated)
        publishPlaylistState(updated)
    }

    /**
     * Reorder tracks in own playlist.
     */
    fun moveTrackInOwn(playlistId: String, fromIndex: Int, toIndex: Int) {
        val current = _myPlaylists.value
        val target = current.firstOrNull { it.id == playlistId } ?: return
        if (fromIndex !in target.tracks.indices || toIndex !in target.tracks.indices) return
        val updatedTracks = target.tracks.toMutableList()
        val item = updatedTracks.removeAt(fromIndex)
        updatedTracks.add(toIndex, item)
        val updated = target.copy(tracks = updatedTracks, updatedAt = System.currentTimeMillis())
        _myPlaylists.value = current.map { if (it.id == playlistId) updated else it }
        savePlaylists()
        notifyLocalPlaylistUpdated(updated)
        publishPlaylistState(updated)
    }

    /**
     * Applies color to chosen track indices.
     * [colorHex] is a hex string (e.g. "#E53935"), or null to clear color (10th choice).
     */
    fun applyColor(playlistId: String, indices: Set<Int>, colorHex: String?) {
        val current = _myPlaylists.value
        val target = current.firstOrNull { it.id == playlistId } ?: return
        val updatedTracks = target.tracks.mapIndexed { idx, track ->
            if (idx in indices) track.copy(colorHex = colorHex) else track
        }
        val updated = target.copy(tracks = updatedTracks, updatedAt = System.currentTimeMillis())
        _myPlaylists.value = current.map { if (it.id == playlistId) updated else it }
        savePlaylists()
        notifyLocalPlaylistUpdated(updated)
        publishPlaylistState(updated)
    }

    /**
     * Marks track as played or unplayed without removing it from the list.
     */
    fun setTrackPlayed(playlistId: String, index: Int, played: Boolean) {
        val current = _myPlaylists.value
        val target = current.firstOrNull { it.id == playlistId } ?: return
        if (index !in target.tracks.indices) return
        val updatedTracks = target.tracks.toMutableList().apply {
            this[index] = this[index].copy(played = played)
        }
        val updated = target.copy(tracks = updatedTracks, updatedAt = System.currentTimeMillis())
        _myPlaylists.value = current.map { if (it.id == playlistId) updated else it }
        savePlaylists()
        notifyLocalPlaylistUpdated(updated)
        publishPlaylistState(updated)
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
            .put("since", System.currentTimeMillis() / 1000 - 3600 * 24)

        reqSubId = RelayPool.subscribe(listOf(filter)) { event ->
            handleIncomingRequest(event)
        }
    }

    private fun handleIncomingRequest(event: NostrEvent) {
        if (!event.isValid()) return
        val senderPubkey = event.pubkey
        val json = runCatching { JSONObject(event.content) }.getOrNull() ?: return
        val playlistId = json.optString("playlistId")
        val action = json.optString("action")

        val current = _myPlaylists.value
        val playlist = current.firstOrNull { it.id == playlistId } ?: return

        // Check access: if allowByLink is false, must be friend
        if (!playlist.allowByLink) {
            val isFriend = FriendsHub.friends.value.any { it.pubkey == senderPubkey } ||
                    FriendsHub.isDeveloper(senderPubkey)
            if (!isFriend) return
        }

        when (action) {
            "add", "add_batch" -> {
                val senderName = json.optString("senderName", "Friend").take(40)
                val senderAvatar = json.optString("senderAvatar").takeIf { it.isNotBlank() }
                val existingCount = playlist.tracks.count { it.addedByPubkey == senderPubkey && !it.played }
                val remainingSlots = (playlist.maxTracksPerUser - existingCount).coerceAtLeast(0)
                if (remainingSlots <= 0) return

                val tracksToAdd = mutableListOf<CollabTrack>()
                if (action == "add") {
                    val trackObj = json.optJSONObject("track")
                    if (trackObj != null) {
                        tracksToAdd.add(
                            CollabTrack(
                                id = trackObj.getString("id"),
                                title = trackObj.optString("t", "Unknown"),
                                artist = trackObj.optString("a", "Unknown"),
                                cover = trackObj.optString("c").takeIf { it.startsWith("http") },
                                durationMs = trackObj.optLong("d", 0L),
                                addedByPubkey = senderPubkey,
                                addedByName = senderName,
                                addedByAvatar = senderAvatar,
                                addedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                } else {
                    val tracksArr = json.optJSONArray("tracks") ?: JSONArray()
                    for (i in 0 until minOf(tracksArr.length(), remainingSlots)) {
                        val trackObj = tracksArr.optJSONObject(i) ?: continue
                        tracksToAdd.add(
                            CollabTrack(
                                id = trackObj.getString("id"),
                                title = trackObj.optString("t", "Unknown"),
                                artist = trackObj.optString("a", "Unknown"),
                                cover = trackObj.optString("c").takeIf { it.startsWith("http") },
                                durationMs = trackObj.optLong("d", 0L),
                                addedByPubkey = senderPubkey,
                                addedByName = senderName,
                                addedByAvatar = senderAvatar,
                                addedAt = System.currentTimeMillis() + i,
                            )
                        )
                    }
                }

                if (tracksToAdd.isEmpty()) return
                val updatedTracks = playlist.tracks + tracksToAdd
                val updated = playlist.copy(tracks = updatedTracks, updatedAt = System.currentTimeMillis())
                _myPlaylists.value = current.map { if (it.id == playlistId) updated else it }
                savePlaylists()
                notifyLocalPlaylistUpdated(updated)
                publishPlaylistState(updated)
            }
            "remove" -> {
                val trackId = json.optString("trackId")
                if (trackId.isBlank()) return
                // Contributor can only remove their own unplayed track
                val idx = playlist.tracks.indexOfFirst { it.id == trackId && it.addedByPubkey == senderPubkey && !it.played }
                if (idx >= 0) {
                    val updatedTracks = playlist.tracks.toMutableList().apply { removeAt(idx) }
                    val updated = playlist.copy(tracks = updatedTracks, updatedAt = System.currentTimeMillis())
                    _myPlaylists.value = current.map { if (it.id == playlistId) updated else it }
                    savePlaylists()
                    notifyLocalPlaylistUpdated(updated)
                    publishPlaylistState(updated)
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
                val pl = runCatching { JSONObject(event.content) }.getOrNull()?.let(::playlistFromJson)
                flow.value = pl
            }
        }
        remoteSubs[key] = subId
        return flow
    }

    fun unwatchPlaylist(ownerPubkey: String, id: String) {
        val key = "$ownerPubkey:$id"
        remoteSubs.remove(key)?.let { RelayPool.close(it) }
        remotePlaylists.remove(key)
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

        val tags = listOf(
            listOf("p", ownerPubkey),
            listOf("t", TAG_COLLAB_REQ),
            listOf("collab_id", playlistId),
        )

        RelayPool.publish(Nostr.sign(sk, Nostr.KIND_APP_DATA, tags, payload.toString()))
    }

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

        val tags = listOf(
            listOf("p", ownerPubkey),
            listOf("t", TAG_COLLAB_REQ),
            listOf("collab_id", playlistId),
        )

        RelayPool.publish(Nostr.sign(sk, Nostr.KIND_APP_DATA, tags, payload.toString()))
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
