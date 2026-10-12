/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.friends

import android.content.Context
import android.content.SharedPreferences
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.playback.SpotifyYouTubeMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import timber.log.Timber

/**
 * A friend's liked tracks kept as one of the user's own playlists ("added to me"): it follows the
 * friend's likes, refreshed whenever a newer copy of them arrives (the friend publishes them when
 * they change; they are fetched at start and while the Friends screens are open).
 */
object FriendLikesSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var prefs: SharedPreferences? = null
    private var database: MusicDatabase? = null

    /** Friend pubkey → id of the playlist that follows their likes. */
    private val _followed = MutableStateFlow<Map<String, String>>(emptyMap())
    val followed: StateFlow<Map<String, String>> = _followed

    fun init(context: Context, database: MusicDatabase) {
        this.database = database
        val p = context.getSharedPreferences("friend_likes_sync", Context.MODE_PRIVATE)
        prefs = p
        _followed.value = runCatching {
            val o = JSONObject(p.getString("followed", "{}")!!)
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun isFollowed(pubkey: String) = pubkey in _followed.value

    /** Creates the playlist "[title]" that follows [pubkey]'s likes and fills it with [likes]. */
    fun follow(pubkey: String, title: String, likes: FriendsHub.SharedLikes) {
        val db = database ?: return
        scope.launch {
            mutex.withLock {
                if (pubkey in _followed.value) return@withLock
                val playlist = PlaylistEntity(name = title, bookmarkedAt = java.time.LocalDateTime.now(), isEditable = true)
                db.withTransaction { insert(playlist) }
                save(_followed.value + (pubkey to playlist.id))
            }
            sync(pubkey, likes, force = true)
        }
    }

    /** Stops following; the playlist stays as it is. */
    fun unfollow(pubkey: String) {
        save(_followed.value - pubkey)
        prefs?.edit()?.remove("hash_$pubkey")?.apply()
    }

    /** A copy of [pubkey]'s likes arrived (see [FriendsHub]). */
    fun onLikes(pubkey: String, likes: FriendsHub.SharedLikes) {
        if (pubkey !in _followed.value) return
        scope.launch { sync(pubkey, likes, force = false) }
    }

    private suspend fun sync(pubkey: String, likes: FriendsHub.SharedLikes, force: Boolean) = mutex.withLock {
        val db = database ?: return@withLock
        val playlistId = _followed.value[pubkey] ?: return@withLock
        val hash = likes.tracks.map { it.catalogId ?: "${it.artist}|${it.title}" }.hashCode()
        if (!force && prefs?.getInt("hash_$pubkey", 0) == hash) return@withLock
        // Deleted by the user: nothing to follow into any more.
        val playlist = db.playlist(playlistId).first() ?: run {
            unfollow(pubkey)
            return@withLock
        }
        runCatching {
            val mapper = SpotifyYouTubeMapper(db)
            val ids = likes.tracks.mapNotNull { ref ->
                FriendPlayback.asCatalogTrack(ref)?.let { mapper.persistWithoutResolving(it).id }
            }.distinct()
            db.withTransaction {
                clearPlaylist(playlistId)
                addSongToPlaylist(playlist.copy(songCount = 0), ids)
            }
            prefs?.edit()?.putInt("hash_$pubkey", hash)?.apply()
        }.onFailure { Timber.w(it, "FriendLikesSync: updating the playlist of $pubkey failed") }
    }

    private fun save(map: Map<String, String>) {
        _followed.value = map
        prefs?.edit()?.putString("followed", JSONObject(map).toString())?.apply()
    }
}
