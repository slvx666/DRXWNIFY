/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.metrolist.music.R
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.FallbackIds
import com.metrolist.music.resolver.SourceSearch
import com.metrolist.music.resolver.VkMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * Where a heart puts a track. Most tracks go to an account as well: Liked Songs on Spotify or Yandex
 * Music, or the user's VK music for a VK track. The rest (another source's upload, no account for
 * it) can only be kept in the app's own "Local" collection — those get their own heart, with a
 * plus, so the two are never confused.
 */
object LikeTarget {
    /** The catalog (Spotify / Yandex Music) id behind [mediaId], from memory only. */
    fun catalogIdFast(mediaId: String?): String? {
        if (mediaId.isNullOrBlank() || SourceSearch.isSourceTrack(mediaId)) return null
        return SpotifyMetadataRegistry.get(mediaId)?.id?.takeIf { it.isNotBlank() }
            ?: FallbackIds.catalogIdOf(mediaId)
    }

    fun catalogId(database: MusicDatabase, mediaId: String?): String? {
        catalogIdFast(mediaId)?.let { return it }
        if (mediaId.isNullOrBlank() || SourceSearch.isSourceTrack(mediaId)) return null
        return runCatching { database.getSpotifyMatchByYouTubeId(mediaId)?.spotifyId }.getOrNull()
    }

    /** True when the account owning [catalogId] is linked (not whether its token is fresh right now). */
    fun accountLinkedFor(catalogId: String): Boolean {
        val state = Catalog.state.value
        return if (Catalog.isYandexId(catalogId)) state.yandexConnected else state.spotifyConnected
    }

    /** Answer from memory only; null when it needs the database. */
    fun syncsToAccountFast(mediaId: String?): Boolean? {
        if (mediaId.isNullOrBlank()) return null
        if (SourceSearch.providerOf(mediaId) == AudioProviderId.VK) return VkMusic.isReady
        if (SourceSearch.isSourceTrack(mediaId)) return false
        return catalogIdFast(mediaId)?.let(::accountLinkedFor)
    }

    fun syncsToAccount(database: MusicDatabase, mediaId: String?): Boolean {
        syncsToAccountFast(mediaId)?.let { return it }
        return catalogId(database, mediaId)?.let(::accountLinkedFor) ?: false
    }

    fun icon(liked: Boolean, syncs: Boolean): Int = when {
        syncs && liked -> R.drawable.favorite
        syncs -> R.drawable.favorite_border
        liked -> R.drawable.favorite_local
        else -> R.drawable.favorite_local_border
    }
}

/**
 * Whether the heart for [mediaId] also saves to an account (see [LikeTarget]). [catalogId], when the
 * caller knows it (a catalog track in a list), decides it directly.
 */
@Composable
fun rememberLikeSyncs(database: MusicDatabase, mediaId: String?, catalogId: String? = null): Boolean {
    if (catalogId != null) return LikeTarget.accountLinkedFor(catalogId)
    val syncs by produceState(LikeTarget.syncsToAccountFast(mediaId) ?: true, mediaId) {
        value = withContext(Dispatchers.IO) { LikeTarget.syncsToAccount(database, mediaId) }
    }
    return syncs
}

/**
 * The one answer to "is this track liked", shared by every heart (full player, mini player, menus):
 * liked in the app, or liked on the account that owns it (a like made in Spotify itself has no flag
 * in the app). Episodes show whether they are saved.
 */
@Composable
fun rememberTrackLiked(database: MusicDatabase, mediaId: String?, catalogId: String? = null): Boolean {
    val song by remember(mediaId) { mediaId?.let { database.song(it) } ?: flowOf(null) }
        .collectAsState(initial = null)
    val resolvedCatalogId by produceState(catalogId ?: LikeTarget.catalogIdFast(mediaId), mediaId, catalogId) {
        value = catalogId ?: withContext(Dispatchers.IO) { LikeTarget.catalogId(database, mediaId) }
    }
    LaunchedEffect(resolvedCatalogId) {
        resolvedCatalogId?.let { SpotifyLikeCache.ensureLoaded(listOf(it)) }
    }
    val likedOnAccount by SpotifyLikeCache.liked.collectAsState()
    val entity = song?.song
    if (entity?.isEpisode == true) return entity.inLibrary != null
    return entity?.liked == true || resolvedCatalogId?.let { it in likedOnAccount } == true
}
