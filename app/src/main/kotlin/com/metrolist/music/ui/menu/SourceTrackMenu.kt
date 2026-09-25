/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavController
import com.metrolist.music.resolver.ProviderMatch
import com.metrolist.music.resolver.SourceSearch

/**
 * Menu for a track found by the experimental source search: the app's one track menu ([PlayerMenu]).
 * What such a track can't do (a radio, another version) is left out there by itself.
 */
@Composable
fun SourceTrackMenu(
    match: ProviderMatch,
    onDismiss: () -> Unit,
    navController: NavController? = null,
) {
    // Registers the track, so it plays, downloads and is described like any other.
    val metadata = remember(match) {
        SourceSearch.mediaItemOf(match)
        SourceSearch.metadataOf(match)
    }
    PlayerMenu(
        mediaMetadata = metadata,
        navController = navController ?: return,
        isCurrentTrack = false,
        onDismiss = onDismiss,
    )
}
