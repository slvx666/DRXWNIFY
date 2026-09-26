/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver

import com.metrolist.music.resolver.providers.VkAudioProvider
import com.metrolist.music.resolver.providers.VkPlaylist
import java.util.concurrent.ConcurrentHashMap

/**
 * VK albums and playlists for the UI: the account's own ones and the ones VK finds for a query.
 * Their tracks are ordinary source tracks ([SourceSearch]), so playing and downloading them needs
 * nothing VK-specific.
 */
object VkMusic {
    private val provider: VkAudioProvider?
        get() = AudioFallbackEngine.provider(AudioProviderId.VK) as? VkAudioProvider

    /** Signed in and switched on. */
    val isReady: Boolean
        get() = ResolverPreferences.isEnabled(AudioProviderId.VK) && provider?.isReady() == true

    /** Playlists seen on screen, so a playlist page can be opened by its key alone. */
    private val known = ConcurrentHashMap<String, VkPlaylist>()

    fun remember(playlists: List<VkPlaylist>) = playlists.forEach { known[it.key] = it }

    fun playlist(key: String): VkPlaylist? = known[key]

    /**
     * Albums/playlists of other owners that the user added to their VK music, by the original's
     * key → the address of the user's copy (needed to remove it again).
     */
    private val savedCopies = ConcurrentHashMap<String, Pair<Long, Long>>()
    val savedKeys = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /** Bumped whenever the user's VK music changes here; open VK lists reload on it. */
    val libraryVersion = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Whether the user's own list was read at least once this session (so [savedKeys] is real). */
    @Volatile
    var libraryLoaded = false
        private set

    suspend fun myPlaylists(): Result<List<VkPlaylist>> =
        runCatching { provider?.myPlaylists().orEmpty() }.onSuccess { list ->
            remember(list)
            savedCopies.clear()
            list.forEach { p ->
                if (p.libraryOwnerId != null && p.libraryId != null) savedCopies[p.key] = p.libraryOwnerId to p.libraryId
            }
            savedKeys.value = savedCopies.keys.toSet()
            libraryLoaded = true
        }

    /** The user's VK id (to tell their own playlists, which can't be "saved", from others'). */
    suspend fun myId(): Long? = runCatching { provider?.myId() }.getOrNull()

    /** Saves [playlist] to, or removes it from, the user's VK music; true when it is saved afterwards. */
    suspend fun toggleSaved(playlist: VkPlaylist): Result<Boolean> = runCatching {
        val vk = provider ?: error("VK is not connected")
        val copy = savedCopies[playlist.key]
        if (copy != null) {
            vk.unfollow(copy.first, copy.second)
            savedCopies.remove(playlist.key)
        } else {
            savedCopies[playlist.key] = vk.follow(playlist)
        }
        savedKeys.value = savedCopies.keys.toSet()
        val saved = savedCopies.containsKey(playlist.key)
        // Re-read the user's list: it holds the real address of the copy (needed to remove it
        // again), and every open Library → VK list refreshes from it.
        myPlaylists()
        libraryVersion.value++
        saved
    }

    suspend fun searchPlaylists(query: String): Result<List<VkPlaylist>> =
        runCatching { provider?.searchPlaylists(query).orEmpty() }.onSuccess(::remember)

    suspend fun myTracks(): Result<List<ProviderMatch>> =
        runCatching { provider?.myTracks().orEmpty() }

    suspend fun tracks(playlist: VkPlaylist): Result<List<ProviderMatch>> =
        runCatching { provider?.playlistTracks(playlist).orEmpty() }

    /**
     * Mirrors a like of a VK track into the user's VK music: liked → added to "My music",
     * unliked → the added copy removed again. The copy's address is remembered on the device,
     * since removing needs it.
     */
    suspend fun setTrackSaved(context: android.content.Context, trackId: String, saved: Boolean): Result<Unit> =
        runCatching {
            val vk = provider ?: error("VK is not connected")
            val prefs = context.getSharedPreferences(SAVED_TRACKS_PREFS, android.content.Context.MODE_PRIVATE)
            val me = myOwnerId ?: myId()?.also { myOwnerId = it }
            val owner = trackId.substringBefore('_').toLongOrNull()
            if (saved) {
                // Already the user's own track (from their VK music): nothing to add.
                if (prefs.contains(trackId) || (me != null && owner == me)) return@runCatching
                val (copyOwner, copy) = vk.addTrack(trackId)
                if (copy != 0L) prefs.edit().putString(trackId, "${copyOwner}_$copy").apply()
            } else {
                val copy = prefs.getString(trackId, null)
                if (copy != null) {
                    val copyOwner = copy.substringBeforeLast('_').toLongOrNull() ?: return@runCatching
                    val id = copy.substringAfterLast('_').toLongOrNull() ?: return@runCatching
                    vk.deleteTrack(copyOwner, id)
                    prefs.edit().remove(trackId).apply()
                } else if (me != null && owner == me) {
                    // A track of the user's own VK music: removed from it directly.
                    trackId.split('_').getOrNull(1)?.toLongOrNull()?.let { vk.deleteTrack(me, it) }
                }
            }
            removedOwn = if (saved) removedOwn - trackId else removedOwn + trackId
            _savedTracks.value = if (saved) _savedTracks.value + trackId else _savedTracks.value - trackId
            libraryVersion.value++
        }

    /**
     * Where a heart on a VK track goes: into the user's VK music (true) or, like the other sources,
     * only into the app's "Local" (false). Settings → Music sources.
     */
    @Volatile
    var likesToAccount: Boolean = true

    @Volatile
    private var myOwnerId: Long? = null

    /** The user's own tracks removed from their VK music in this session. */
    @Volatile
    private var removedOwn: Set<String> = emptySet()

    private val _savedTracks = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /** VK tracks the app put into the user's VK music (the user's own tracks count as saved too). */
    val savedTracks: kotlinx.coroutines.flow.StateFlow<Set<String>> = _savedTracks

    /** Loads what is known on the device about saved tracks, and who the user is. */
    suspend fun loadSavedTracks(context: android.content.Context) {
        val prefs = context.getSharedPreferences(SAVED_TRACKS_PREFS, android.content.Context.MODE_PRIVATE)
        _savedTracks.value = prefs.all.keys.toSet()
        if (isReady && myOwnerId == null) myOwnerId = myId()
    }

    /** True when [trackId] ("owner_audio[_key]") is in the user's VK music. */
    fun isTrackSaved(trackId: String, saved: Set<String> = _savedTracks.value): Boolean {
        if (trackId in saved) return true
        if (trackId in removedOwn) return false
        val me = myOwnerId ?: return false
        return trackId.substringBefore('_').toLongOrNull() == me
    }

    private const val SAVED_TRACKS_PREFS = "vk_saved_tracks"

    /**
     * Album or playlist for [playlist], by its tracks ([VkAudioProvider.isAlbumByTracks]); the
     * answer is kept on the device, so each playlist is looked at once.
     */
    suspend fun isAlbum(context: android.content.Context, playlist: VkPlaylist): Boolean? {
        val prefs = context.getSharedPreferences(KIND_PREFS, android.content.Context.MODE_PRIVATE)
        if (prefs.contains(playlist.key)) return prefs.getBoolean(playlist.key, false)
        val album = runCatching { provider?.isAlbumByTracks(playlist) }.getOrNull() ?: return null
        prefs.edit().putBoolean(playlist.key, album).apply()
        return album
    }

    /** Known answers only (no network): for showing the list right away. */
    fun knownKind(context: android.content.Context, playlist: VkPlaylist): Boolean? {
        val prefs = context.getSharedPreferences(KIND_PREFS, android.content.Context.MODE_PRIVATE)
        return if (prefs.contains(playlist.key)) prefs.getBoolean(playlist.key, false) else null
    }

    private const val KIND_PREFS = "vk_playlist_kind"
}
