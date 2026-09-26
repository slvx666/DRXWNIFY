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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.FallbackIds
import com.metrolist.music.resolver.SourceSearch
import com.metrolist.music.resolver.VkMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * Where a heart puts a track. Catalog tracks (Spotify / Yandex Music, and what plays for them) are
 * liked as usual — in the app and on the account. Tracks found in the audio sources themselves are
 * kept in the app's "Local" and get the heart with a plus, except VK tracks while VK likes go to the
 * user's VK music (Settings → Music sources).
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

    /** The VK track id ("owner_audio[_key]") of a VK source track, else null. */
    fun vkTrackIdOf(mediaId: String?): String? {
        if (SourceSearch.providerOf(mediaId) != AudioProviderId.VK) return null
        return FallbackIds.catalogIdOf(mediaId ?: return null)?.substringAfter("VK:")
    }

    /** False only for the tracks whose heart keeps them in the app alone (the heart with a plus). */
    fun syncsToAccount(mediaId: String?): Boolean {
        if (mediaId.isNullOrBlank() || !SourceSearch.isSourceTrack(mediaId)) return true
        return vkTrackIdOf(mediaId) != null && VkMusic.isReady && VkMusic.likesToAccount
    }

    @Suppress("UNUSED_PARAMETER")
    fun syncsToAccount(database: MusicDatabase, mediaId: String?): Boolean = syncsToAccount(mediaId)

    fun icon(liked: Boolean, syncs: Boolean): Int = when {
        syncs && liked -> R.drawable.favorite
        syncs -> R.drawable.favorite_border
        liked -> R.drawable.favorite_local
        else -> R.drawable.favorite_local_border
    }
}

/** Whether the heart for [mediaId] is the usual one (see [LikeTarget]); false = the heart with a plus. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun rememberLikeSyncs(database: MusicDatabase, mediaId: String?, catalogId: String? = null): Boolean =
    LikeTarget.syncsToAccount(mediaId)

/**
 * The one answer to "is this track liked", shared by every heart (full player, mini player, menus):
 * liked in the app, on the account that owns it (a like made in Spotify itself has no flag in the
 * app), or — for a VK track — in the user's VK music. Episodes show whether they are saved.
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
    val vkSaved by VkMusic.savedTracks.collectAsState()
    val entity = song?.song
    if (entity?.isEpisode == true) return entity.inLibrary != null
    val vkTrackId = LikeTarget.vkTrackIdOf(mediaId)
    val savedInVk = vkTrackId != null && VkMusic.likesToAccount && VkMusic.isTrackSaved(vkTrackId, vkSaved)
    return entity?.liked == true || savedInVk || resolvedCatalogId?.let { it in likedOnAccount } == true
}

/** The small heart next to a liked track in a list (with the plus when it is kept in the app only). */
@Composable
fun LikedBadge(mediaId: String) {
    val database = com.metrolist.music.LocalDatabase.current
    if (!rememberTrackLiked(database, mediaId)) return
    androidx.compose.material3.Icon(
        painter = androidx.compose.ui.res.painterResource(LikeTarget.icon(true, LikeTarget.syncsToAccount(mediaId))),
        contentDescription = null,
        tint = androidx.compose.material3.MaterialTheme.colorScheme.error,
        modifier = androidx.compose.ui.Modifier
            .padding(end = 2.dp)
            .size(18.dp),
    )
}
