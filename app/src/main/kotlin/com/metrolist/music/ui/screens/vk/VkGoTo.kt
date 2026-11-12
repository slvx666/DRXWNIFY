/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.vk

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.catalog.CrossCatalog
import com.metrolist.music.catalog.VkCatalog
import com.metrolist.music.constants.MetadataSource
import com.metrolist.music.ui.component.shimmer.ListItemPlaceHolder
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.ui.component.shimmer.TextPlaceholder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Route of [VkGoToScreen]: the album or the artist of the VK track [fullId]. */
fun vkGoRoute(toArtist: Boolean, fullId: String, title: String, artist: String): String =
    "vk_go/${if (toArtist) "artist" else "album"}/${Uri.encode(fullId)}?title=${Uri.encode(title)}&artist=${Uri.encode(artist)}"

/**
 * Opens the album or the artist of a VK track. An official VK release names both (its album and
 * VK's artist page); an upload by a user names neither, so the same track is looked up in the
 * search catalog (Spotify's public one) and its album / the artist of that name opened there.
 * Meanwhile the page shows a loading outline of what is coming, then gives way to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VkGoToScreen(navController: NavController, toArtist: Boolean, fullId: String, title: String, artist: String) {
    var notFound by remember { mutableStateOf(false) }
    LaunchedEffect(fullId, toArtist) {
        val route = withContext(Dispatchers.IO) { runCatching { findRoute(toArtist, fullId, title, artist) }.getOrNull() }
        if (route == null) {
            notFound = true
            return@LaunchedEffect
        }
        val here = navController.currentDestination?.route
        navController.navigate(route) {
            here?.let { popUpTo(it) { inclusive = true } }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = notFound,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "vkGoTo",
            modifier = Modifier.fillMaxSize(),
        ) { missing ->
            if (missing) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                ) {
                    Icon(
                        painterResource(if (toArtist) R.drawable.artist else R.drawable.album),
                        null,
                        Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(if (toArtist) R.string.vk_go_artist_not_found else R.string.vk_go_album_not_found),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.vk_go_not_found_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { navController.navigateUp() }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                        Text(stringResource(R.string.vk_go_back))
                    }
                }
            } else {
                // The outline of the page being looked for: a cover (round for an artist), the title, tracks.
                ShimmerHost(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(androidx.compose.foundation.layout.WindowInsetsSides.Top))
                        .padding(horizontal = 16.dp),
                ) {
                    Spacer(Modifier.height(24.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.62f)
                            .aspectRatio(1f)
                            .clip(if (toArtist) CircleShape else RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.onSurface),
                    )
                    Spacer(Modifier.height(16.dp))
                    TextPlaceholder(height = 24.dp, shape = RoundedCornerShape(6.dp))
                    TextPlaceholder(shape = RoundedCornerShape(6.dp))
                    Spacer(Modifier.height(12.dp))
                    repeat(6) { ListItemPlaceHolder() }
                }
            }
        }
        TopAppBar(
            title = {
                Text(
                    if (notFound) "" else stringResource(if (toArtist) R.string.vk_go_finding_artist else R.string.vk_go_finding_album),
                    maxLines = 1,
                )
            },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )
    }
}

/** Where the album / artist of the VK track is: a page route, or null when it can't be found. */
private suspend fun findRoute(toArtist: Boolean, fullId: String, title: String, artist: String): String? {
    val info = VkCatalog.trackInfo(fullId)
    val firstArtist = (info?.artists?.firstOrNull()?.name ?: artist.split(", ", " & ", " feat. ", " ft. ").firstOrNull())
        ?.trim()?.takeIf { it.isNotEmpty() }
    if (toArtist) {
        // VK's own artist page when VK has one; else the artist of the same name in the search catalog.
        info?.artists?.firstOrNull { it.id != null }?.let { return "spotify_artist/${VkCatalog.artistId(it.id!!)}" }
        val name = firstArtist ?: return null
        CrossCatalog.spotifyArtistByName(name)?.let { return "spotify_artist/$it" }
        return null
    }
    info?.album?.let { a -> return "spotify_album/${VkCatalog.playlistId(a.ownerId, a.id, a.accessKey)}" }
    // An upload: the same track in the search catalog, and its album.
    val trackTitle = info?.title ?: title
    if (trackTitle.isBlank()) return null
    if (!Catalog.ensureSearchable(MetadataSource.SPOTIFY)) return null
    val trackId = CrossCatalog.trackIn(
        MetadataSource.SPOTIFY,
        trackTitle,
        listOfNotNull(firstArtist),
        info?.durationSec ?: 0,
    ) ?: return null
    val albumId = Catalog.getTrack(trackId).getOrNull()?.album?.id?.takeIf { it.isNotBlank() } ?: return null
    return "spotify_album/$albumId"
}
