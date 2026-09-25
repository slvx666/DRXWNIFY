/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.metrolist.music.R
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.FallbackIds
import com.metrolist.music.resolver.SourceSearch
import com.metrolist.music.resolver.VkMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where a heart puts a track. Most tracks go to an account as well: Liked Songs on Spotify or Yandex
 * Music, or the user's VK music for a VK track. The rest (another source's upload, no account for
 * it) can only be kept in the app's own "Local" collection — those get their own heart, with a
 * plus, so the two are never confused.
 */
object LikeTarget {
    /** Answer from memory only; null when it needs the database. */
    fun syncsToAccountFast(mediaId: String?): Boolean? {
        if (mediaId.isNullOrBlank()) return null
        if (SourceSearch.providerOf(mediaId) == AudioProviderId.VK) return VkMusic.isReady
        if (SourceSearch.isSourceTrack(mediaId)) return false
        val catalogId = SpotifyMetadataRegistry.get(mediaId)?.id?.takeIf { it.isNotBlank() }
            ?: FallbackIds.catalogIdOf(mediaId)
            ?: return null
        return Catalog.canWrite(catalogId)
    }

    fun syncsToAccount(database: MusicDatabase, mediaId: String?): Boolean {
        syncsToAccountFast(mediaId)?.let { return it }
        if (mediaId.isNullOrBlank()) return false
        val catalogId = runCatching { database.getSpotifyMatchByYouTubeId(mediaId)?.spotifyId }.getOrNull()
            ?: return false
        return Catalog.canWrite(catalogId)
    }

    fun icon(liked: Boolean, syncs: Boolean): Int = when {
        syncs && liked -> R.drawable.favorite
        syncs -> R.drawable.favorite_border
        liked -> R.drawable.favorite_local
        else -> R.drawable.favorite_local_border
    }
}

/** Whether the heart for [mediaId] also saves to an account (see [LikeTarget]). */
@Composable
fun rememberLikeSyncs(database: MusicDatabase, mediaId: String?): Boolean {
    val syncs by produceState(LikeTarget.syncsToAccountFast(mediaId) ?: true, mediaId) {
        value = withContext(Dispatchers.IO) { LikeTarget.syncsToAccount(database, mediaId) }
    }
    return syncs
}
