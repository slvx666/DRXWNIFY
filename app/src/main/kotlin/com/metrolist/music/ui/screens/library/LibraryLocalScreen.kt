/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.playback.SpotifyLikeCache
import com.metrolist.music.viewmodels.LibraryPlaylistsViewModel

/**
 * P5 — the "Local" (Локальные) library tab. A Spotify-style rundown of the on-device / linked
 * auto-collections: Downloaded, Cached, Uploaded and Spotify Liked Songs, each with its track
 * count (like the Liked Songs row). Rendered as a simple list so it reads like Spotify's
 * "Your Library → Local".
 */
@Composable
fun LibraryLocalScreen(
    navController: NavController,
    filterContent: @Composable (() -> Unit)? = null,
    viewModel: LibraryPlaylistsViewModel = hiltViewModel(),
) {
    val downloadedPreview by viewModel.downloadedPreview.collectAsState()
    val localLikedPreview by viewModel.localLikedPreview.collectAsState()
    val uploadedPreview by viewModel.uploadedPreview.collectAsState()
    val spotifyLiked by SpotifyLikeCache.liked.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            // Keep the library tab chips at the top so Local isn't a dead end (P5 regression fix).
            filterContent?.let { chips ->
                item(key = "filter") { chips() }
            }
            // Hearts with a plus: tracks kept in the app because no account can take them.
            item(key = "local_liked") {
                LocalRow(
                    icon = R.drawable.favorite_local,
                    title = stringResource(R.string.local_liked),
                    count = localLikedPreview.count.takeIf { it > 0 },
                    onClick = { navController.navigate("auto_playlist/local_liked") },
                )
            }
            item(key = "downloaded") {
                LocalRow(
                    icon = R.drawable.offline,
                    title = stringResource(R.string.offline),
                    count = downloadedPreview.count,
                    onClick = { navController.navigate("auto_playlist/downloaded") },
                )
            }
            item(key = "cached") {
                LocalRow(
                    icon = R.drawable.cached,
                    title = stringResource(R.string.cached_playlist),
                    count = null,
                    onClick = { navController.navigate("cache_playlist/cached") },
                )
            }
            item(key = "uploaded") {
                LocalRow(
                    icon = R.drawable.upload,
                    title = stringResource(R.string.uploaded_playlist),
                    count = uploadedPreview.count,
                    onClick = { navController.navigate("auto_playlist/uploaded") },
                )
            }
            item(key = "spotify_liked") {
                LocalRow(
                    icon = R.drawable.favorite,
                    title = stringResource(R.string.spotify_liked_songs),
                    count = spotifyLiked.size.takeIf { it > 0 },
                    onClick = { navController.navigate("spotify_liked_songs") },
                )
            }
        }
    }
}

@Composable
private fun LocalRow(
    icon: Int,
    title: String,
    count: Int?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(Modifier),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count != null) {
                Text(
                    text = pluralStringResource(R.plurals.n_song, count, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
