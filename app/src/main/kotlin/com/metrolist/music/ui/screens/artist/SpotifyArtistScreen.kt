/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.artist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.playback.queues.SpotifyPlaylistQueue
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.ItemThumbnail
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.viewmodels.SpotifyArtistViewModel
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyAlbum

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyArtistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: SpotifyArtistViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = com.metrolist.music.ui.component.LocalMenuState.current

    val artist by viewModel.artist.collectAsState()
    val topTracks by viewModel.topTracks.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val singles by viewModel.singles.collectAsState()
    val isFollowing by viewModel.isFollowing.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val lazyListState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            item(key = "header") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    AsyncImage(
                        model = artist?.images?.firstOrNull()?.url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(160.dp)
                            .clip(CircleShape),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = artist?.name.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Monthly listeners (from Spotify), shown when available.
                    artist?.monthlyListeners?.let { listeners ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.spotify_monthly_listeners,
                                "%,d".format(listeners),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (topTracks.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        // P8/P9/P10/P12: one consistent profile. Always the same three actions —
                        // Play, Shuffle (no immediate repeat), and Follow/Subscribe. The Follow
                        // button is shown even before the follow state is known (isFollowing == null),
                        // so the layout never degrades to a lone "Play" button.
                        // Remember the last shuffle start index so pressing Shuffle again never
                        // replays the same track.
                        var lastShuffleIndex by remember { mutableStateOf(-1) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    playerConnection.playQueue(
                                        com.metrolist.music.playback.queues.ArtistTopTracksQueue(
                                            artistId = viewModel.artistId,
                                            topTracks = topTracks,
                                            startIndex = 0,
                                            mapper = viewModel.mapper,
                                        )
                                    )
                                },
                            ) {
                                Icon(painterResource(R.drawable.play), null, Modifier.size(20.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.play))
                            }
                            Spacer(Modifier.width(8.dp))
                            // Shuffle: start from a random track, guaranteed different from the
                            // previous shuffle press.
                            OutlinedButton(
                                onClick = {
                                    val count = topTracks.size
                                    val start = if (count <= 1) {
                                        0
                                    } else {
                                        var idx = (0 until count).random()
                                        if (idx == lastShuffleIndex) idx = (idx + 1) % count
                                        idx
                                    }
                                    lastShuffleIndex = start
                                    playerConnection.playQueue(
                                        com.metrolist.music.playback.queues.ArtistTopTracksQueue(
                                            artistId = viewModel.artistId,
                                            topTracks = topTracks,
                                            startIndex = start,
                                            mapper = viewModel.mapper,
                                        )
                                    )
                                },
                            ) {
                                Icon(painterResource(R.drawable.shuffle), null, Modifier.size(20.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.shuffle))
                            }
                            Spacer(Modifier.width(8.dp))
                            // Follow/Subscribe on Spotify. Always visible; when follow state is not
                            // yet known we show the outlined "Follow" affordance (tapping resolves it).
                            if (isFollowing == true) {
                                Button(onClick = { viewModel.toggleFollow() }) {
                                    Icon(painterResource(R.drawable.done), null, Modifier.size(20.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(R.string.following))
                                }
                            } else {
                                OutlinedButton(onClick = { viewModel.toggleFollow() }) {
                                    Icon(painterResource(R.drawable.subscribe), null, Modifier.size(20.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(R.string.follow))
                                }
                            }
                        }
                    }
                }
            }

            if (isLoading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (error != null) {
                item(key = "error") {
                    Column(
                        Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(error ?: "", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { viewModel.retry() }) {
                            Text(stringResource(R.string.retry_button))
                        }
                    }
                }
            }

            if (topTracks.isNotEmpty()) {
                item(key = "top_header") { SectionHeader(stringResource(R.string.artist_top_tracks)) }
                itemsIndexed(
                    items = topTracks.take(10),
                    key = { i, t -> "top_${t.id}_$i" },
                ) { index, track ->
                    ListItem(
                        title = track.name,
                        subtitle = joinByBullet(
                            track.artists.joinToString { it.name },
                            makeTimeString(track.durationMs.toLong()),
                        ),
                        thumbnailContent = {
                            ItemThumbnail(
                                thumbnailUrl = SpotifyMapper.getTrackThumbnail(track),
                                isActive = false,
                                isPlaying = false,
                                shape = RoundedCornerShape(ThumbnailCornerRadius),
                                modifier = Modifier.size(ListThumbnailSize),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    playerConnection.playQueue(
                                        com.metrolist.music.playback.queues.ArtistTopTracksQueue(
                                            artistId = viewModel.artistId,
                                            topTracks = topTracks,
                                            startIndex = index,
                                            mapper = viewModel.mapper,
                                        )
                                    )
                                },
                                onLongClick = {
                                    menuState.show {
                                        com.metrolist.music.ui.menu.SpotifyTrackMenu(
                                            track = track,
                                            mapper = viewModel.mapper,
                                            onDismiss = menuState::dismiss,
                                            navController = navController,
                                        )
                                    }
                                },
                            ),
                    )
                }
            }

            if (albums.isNotEmpty()) {
                item(key = "albums_header") { SectionHeader(stringResource(R.string.albums)) }
                item(key = "albums_row") {
                    AlbumRow(albums) { navController.navigate("spotify_album/${it.id}") }
                }
            }

            if (singles.isNotEmpty()) {
                item(key = "singles_header") { SectionHeader(stringResource(R.string.singles_and_eps)) }
                item(key = "singles_row") {
                    AlbumRow(singles) { navController.navigate("spotify_album/${it.id}") }
                }
            }
        }
    }

    TopAppBar(
        title = { Text(artist?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun AlbumRow(albums: List<SpotifyAlbum>, onClick: (SpotifyAlbum) -> Unit) {
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
        items(albums, key = { it.id }) { album ->
            Column(
                modifier = Modifier
                    .width(140.dp)
                    .padding(end = 12.dp)
                    .clickable { onClick(album) },
            ) {
                AsyncImage(
                    model = album.images.firstOrNull()?.url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(140.dp)
                        .clip(RoundedCornerShape(ThumbnailCornerRadius)),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                album.releaseDate?.take(4)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
