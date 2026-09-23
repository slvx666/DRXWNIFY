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

    suspend fun myPlaylists(): Result<List<VkPlaylist>> =
        runCatching { provider?.myPlaylists().orEmpty() }.onSuccess(::remember)

    suspend fun searchPlaylists(query: String): Result<List<VkPlaylist>> =
        runCatching { provider?.searchPlaylists(query).orEmpty() }.onSuccess(::remember)

    suspend fun myTracks(): Result<List<ProviderMatch>> =
        runCatching { provider?.myTracks().orEmpty() }

    suspend fun tracks(playlist: VkPlaylist): Result<List<ProviderMatch>> =
        runCatching { provider?.playlistTracks(playlist).orEmpty() }
}
