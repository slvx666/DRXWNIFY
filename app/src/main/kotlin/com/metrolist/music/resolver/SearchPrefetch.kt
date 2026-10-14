/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver

import android.content.Context
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.constants.VkAccessTokenKey
import com.metrolist.music.utils.YTPlayerUtils
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.extensions.toEnum
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Finds the audio of the first search results before they are tapped, the way Lane's tracks come
 * with their stream: the source is picked and, for YouTube, the stream address fetched while the
 * user is still looking at the list, so the tap starts the sound at once.
 *
 * Without the user's own VK login the background search leaves VK out and goes to YouTube: the
 * shared account's tracks per day are few, and VK pauses an account that searches a lot.
 */
object SearchPrefetch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    /** Prepares the first of [tracks]; a newer search cancels what an older one still had to do. */
    fun start(context: Context, tracks: List<SpotifyTrack>) {
        val targets = tracks.filter { it.id.isNotBlank() }.take(PREFETCH_COUNT)
        job?.cancel()
        if (targets.isEmpty()) return
        val appContext = context.applicationContext
        job = scope.launch {
            // The list may still change (another letter typed): only a search that stays is worked on.
            delay(START_DELAY_MS)
            val ownVk = appContext.dataStore.get(VkAccessTokenKey, "").isNotBlank()
            val exclude = if (ownVk) setOf(AudioProviderId.SOULSEEK) else setOf(AudioProviderId.VK, AudioProviderId.SOULSEEK)
            val quality = appContext.dataStore.get(AudioQualityKey).toEnum(com.metrolist.music.constants.AudioQuality.HIGH)
            val connectivity = appContext.getSystemService(android.net.ConnectivityManager::class.java)
            for (track in targets) {
                val match = runCatching { AudioFallbackEngine.resolve(AudioQuery.from(track), exclude) }.getOrNull() ?: continue
                // The race is done: playing the track doesn't run it again.
                AudioFallbackEngine.markQualityChecked(track.id)
                if (match.provider == AudioProviderId.YOUTUBE && connectivity != null) {
                    YTPlayerUtils.playerResponseForPlayback(match.trackId, audioQuality = quality, connectivityManager = connectivity)
                }
            }
        }
    }

    private const val PREFETCH_COUNT = 2
    private const val START_DELAY_MS = 700L
}
