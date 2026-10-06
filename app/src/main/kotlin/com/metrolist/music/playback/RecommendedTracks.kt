/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.common.MediaItem

/**
 * Queue items the app picked by itself (radio after an album, "For you", similar artists) rather
 * than the user. Such a track is not let in when its audio is below [MIN_KBPS]: a 63 kbps VK rip
 * in the middle of a radio is worse than the next song.
 */
object RecommendedTracks {
    const val MIN_KBPS = 127

    private const val MAX_REMEMBERED = 4_000

    private val ids: MutableSet<String> = java.util.Collections.newSetFromMap(
        object : java.util.LinkedHashMap<String, Boolean>(256, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > MAX_REMEMBERED
        },
    )

    fun mark(item: MediaItem) = synchronized(ids) { ids.add(item.mediaId) }

    fun mark(items: List<MediaItem>) = synchronized(ids) { items.forEach { ids.add(it.mediaId) } }

    /** A new queue chosen by the user: what the old radio recommended is the user's choice now. */
    fun clear() = synchronized(ids) { ids.clear() }

    fun isRecommended(mediaId: String): Boolean = synchronized(ids) { mediaId in ids }

    /** True when [mediaId] was recommended and its known bitrate ([bitrateBps], > 0) is too low. */
    fun tooLow(mediaId: String, bitrateBps: Int): Boolean =
        bitrateBps in 1 until MIN_KBPS * 1000 && isRecommended(mediaId)
}
