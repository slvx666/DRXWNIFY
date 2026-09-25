/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavController
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.models.SpotifyTrack

/** A catalog (Spotify / Yandex Music) track's menu: the app's one track menu ([PlayerMenu]). */
@Composable
fun SpotifyTrackMenu(
    track: SpotifyTrack,
    mapper: SpotifyYouTubeMapper,
    onDismiss: () -> Unit,
    navController: NavController? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
) {
    // The track's metadata (and its catalog identity, for likes, links and downloads) at once;
    // playback resolves the audio later.
    val metadata = remember(track.id) { mapper.quickMetadata(track) }
    LaunchedEffect(track.id) {
        com.metrolist.music.playback.SpotifyLikeCache.ensureLoaded(listOf(track.id))
    }
    PlayerMenu(
        mediaMetadata = metadata,
        navController = navController ?: return,
        isCurrentTrack = false,
        spotifyTrack = track,
        onRemoveFromPlaylist = onRemoveFromPlaylist,
        onDismiss = onDismiss,
    )
}
