/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver

import java.util.concurrent.ConcurrentHashMap

/**
 * Sources that lately failed to deliver sound in time while the internet itself was fine (VK
 * segments that never came over a slow route). For a while such a source doesn't hold up the
 * choice: the track starts from another source that has it instead of waiting for this one — it
 * used to cost half a minute of silence per track before YouTube played it anyway. It is still
 * used when nothing else has the track.
 */
object ProviderHealth {
    private const val COOLDOWN_MS = 10 * 60_000L

    private val slowUntil = ConcurrentHashMap<AudioProviderId, Long>()

    fun noteSlow(id: AudioProviderId) {
        if (id == AudioProviderId.YOUTUBE) return
        slowUntil[id] = System.currentTimeMillis() + COOLDOWN_MS
        AudioDiagnostics.warn("$id didn't send the audio in time — not waited for during the next ${COOLDOWN_MS / 60_000} min")
    }

    fun isSlow(id: AudioProviderId): Boolean = (slowUntil[id] ?: 0L) > System.currentTimeMillis()

    /** A real outage, not the source: nothing is held against it. */
    fun forgetAll() = slowUntil.clear()
}
