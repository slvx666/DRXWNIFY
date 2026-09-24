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
        savedCopies.containsKey(playlist.key)
    }

    suspend fun searchPlaylists(query: String): Result<List<VkPlaylist>> =
        runCatching { provider?.searchPlaylists(query).orEmpty() }.onSuccess(::remember)

    suspend fun myTracks(): Result<List<ProviderMatch>> =
        runCatching { provider?.myTracks().orEmpty() }

    suspend fun tracks(playlist: VkPlaylist): Result<List<ProviderMatch>> =
        runCatching { provider?.playlistTracks(playlist).orEmpty() }
}
