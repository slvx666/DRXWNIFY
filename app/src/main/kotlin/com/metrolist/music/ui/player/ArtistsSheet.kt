/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.SpotifyMetadataRegistry
import com.metrolist.music.utils.SPOTIFY_ID_PREFIX
import com.metrolist.music.utils.isSpotifyId
import com.metrolist.music.utils.stripSpotifyPrefix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The artists of what plays, in the order the line shows them: the catalog track's (real ids)
 * when it is known, else the item's own. Each is (name, id); the id may be missing.
 */
fun playerArtistLinks(mediaMetadata: MediaMetadata): List<Pair<String, String?>> =
    mediaMetadata.id
        ?.let { SpotifyMetadataRegistry.get(it) }
        ?.artists
        ?.filter { it.name.isNotBlank() }
        ?.map { it.name to it.id?.let { id -> "$SPOTIFY_ID_PREFIX$id" } }
        ?.takeIf { it.isNotEmpty() }
        ?: mediaMetadata.artists.filter { it.name.isNotBlank() }.map { it.name to it.id }

/** The page route of an artist id: a catalog artist's or a YouTube one's. */
fun artistRoute(id: String): String =
    if (id.isSpotifyId()) "spotify_artist/${id.stripSpotifyPrefix()}" else "artist/$id"

/**
 * The artists of a track from the bottom, as the share sheet comes up: top to bottom in the
 * order they stand in the line, each with its picture; a tap opens that artist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistsSheet(
    artists: List<Pair<String, String?>>,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.artists),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Spacer(Modifier.size(8.dp))
            artists.forEach { (name, id) ->
                val picture by produceState<String?>(null, id) {
                    val catalogId = id?.takeIf { it.isSpotifyId() }?.stripSpotifyPrefix() ?: return@produceState
                    value = withContext(Dispatchers.IO) {
                        com.metrolist.music.catalog.Catalog.artist(catalogId).getOrNull()?.images
                            ?.let { imgs -> imgs.firstOrNull { (it.width ?: 0) in 120..400 } ?: imgs.firstOrNull() }?.url
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !id.isNullOrBlank()) { id?.let(onOpen) }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Icon(painterResource(R.drawable.artist), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (picture != null) {
                            AsyncImage(
                                model = picture,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(48.dp).clip(CircleShape),
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (id.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
