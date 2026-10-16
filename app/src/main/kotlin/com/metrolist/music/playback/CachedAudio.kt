/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata

/**
 * True when the cache holds every byte of [key] and knows how long it is: only then can it be
 * played without an address to fetch from. A partly cached track (its warmed-up start) still needs
 * the stream's address for the rest.
 */
@UnstableApi
fun Cache.holdsWhole(key: String): Boolean {
    val length = ContentMetadata.getContentLength(getContentMetadata(key))
    return length > 0 && isCached(key, 0, length)
}
