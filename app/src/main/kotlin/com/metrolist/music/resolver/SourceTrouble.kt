/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver

/**
 * Source-wide troubles that are about the network or the account, not about a track: YouTube
 * turning this address away ("confirm you're not a bot", "try again later") and VK pausing search
 * after too many requests. A track that failed while one of them held is told to the user as that,
 * not as a bare "download error" they can do nothing about.
 */
object SourceTrouble {
    /** How long a YouTube refusal is taken to still hold. */
    private const val YOUTUBE_REFUSAL_HOLDS_MS = 20 * 60 * 1000L

    @Volatile
    private var youtubeRefusedAt = 0L

    @Volatile
    var vkSearchPausedUntil = 0L

    fun youtubeRefused() {
        youtubeRefusedAt = System.currentTimeMillis()
    }

    fun youtubeRefusedRecently(): Boolean = System.currentTimeMillis() - youtubeRefusedAt < YOUTUBE_REFUSAL_HOLDS_MS

    fun vkPaused(): Boolean = System.currentTimeMillis() < vkSearchPausedUntil
}
