/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.friends.FriendsHub
import com.metrolist.music.ui.screens.friends.FriendAvatar
import com.metrolist.music.ui.screens.friends.genreColor
import com.metrolist.music.ui.screens.friends.huesFor
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyTrack
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val CardWidth = 136.dp

/** "3 d ago", "yesterday"… for a release date "2025-03-14". */
@Composable
private fun releasedAgo(date: String?): String? {
    val day = runCatching { LocalDate.parse(date?.take(10)) }.getOrNull() ?: return null
    val days = ChronoUnit.DAYS.between(day, LocalDate.now()).toInt().coerceAtLeast(0)
    return when {
        days == 0 -> stringResource(R.string.home_today)
        days == 1 -> stringResource(R.string.home_yesterday)
        days < 14 -> stringResource(R.string.home_days_ago, days)
        days < 60 -> stringResource(R.string.home_weeks_ago, days / 7)
        else -> stringResource(R.string.home_months_ago, days / 30)
    }
}

@Composable
private fun releaseType(album: SpotifyAlbum): String = when {
    album.albumType.equals("single", ignoreCase = true) && album.totalTracks in 4..7 -> stringResource(R.string.home_type_ep)
    album.albumType.equals("single", ignoreCase = true) -> stringResource(R.string.home_type_single)
    album.albumType.equals("ep", ignoreCase = true) -> stringResource(R.string.home_type_ep)
    else -> stringResource(R.string.home_type_album)
}

/** New releases of the favourite artists: cover, title, artist, and how fresh it is. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NewReleasesRow(albums: List<SpotifyAlbum>, onOpen: (SpotifyAlbum) -> Unit, modifier: Modifier = Modifier, showAge: Boolean = true) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        items(albums, key = { "nr_${it.id}" }) { album ->
            val cover = album.images.firstOrNull { (it.width ?: 0) in 250..700 }?.url ?: album.images.firstOrNull()?.url
            Column(
                Modifier
                    .width(CardWidth)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius))
                    .combinedClickable(onClick = { onOpen(album) }),
            ) {
                Box {
                    AsyncImage(
                        cover, album.name, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(CardWidth).clip(RoundedCornerShape(ThumbnailCornerRadius)),
                    )
                    releasedAgo(album.releaseDate)?.takeIf { showAge }?.let { ago ->
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        ) {
                            Text(
                                ago,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(album.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        releaseType(album),
                        album.releaseDate?.take(4)?.takeIf { !showAge && it.length == 4 },
                        album.artists.joinToString { it.name }.ifBlank { null },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A row of track covers with titles: a tap plays the row from that track. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackCardsRow(
    tracks: List<SpotifyTrack>,
    currentId: String?,
    onPlay: (Int) -> Unit,
    onMenu: (SpotifyTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        itemsIndexed(tracks, key = { _, t -> "tc_${t.id}" }) { index, track ->
            val active = currentId != null && currentId.endsWith(track.id)
            Column(
                Modifier
                    .width(CardWidth)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius))
                    .combinedClickable(onClick = { onPlay(index) }, onLongClick = { onMenu(track) }),
            ) {
                AsyncImage(
                    SpotifyMapper.getTrackThumbnail(track), track.name, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(CardWidth).clip(RoundedCornerShape(ThumbnailCornerRadius)),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    track.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artists.joinToString { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The listener's genres as big coloured tiles; each opens the genre's playlist. */
@Composable
fun GenreTilesRow(genres: List<String>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val hues = huesFor(genres)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier,
    ) {
        items(genres, key = { "gt_$it" }) { genre ->
            val color = genreColor(genre, hues[genre])
            Box(
                Modifier
                    .size(width = 150.dp, height = 88.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.45f))))
                    .combinedClickableCompat { onOpen(genre) }
                    .padding(12.dp),
            ) {
                Text(
                    genre,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

/** What a friend plays now, or played last. */
data class FriendListening(
    val pubkey: String,
    val name: String,
    val avatar: String?,
    val track: FriendsHub.TrackRef,
    val now: Boolean,
    val at: Long,
)

@Composable
private fun friendWhen(entry: FriendListening): String {
    if (entry.now) return stringResource(R.string.home_friend_now)
    val minutes = ((System.currentTimeMillis() - entry.at) / 60_000L).toInt().coerceAtLeast(1)
    return when {
        minutes < 60 -> stringResource(R.string.home_minutes_ago, minutes)
        minutes < 48 * 60 -> stringResource(R.string.home_hours_ago, minutes / 60)
        else -> stringResource(R.string.home_days_ago, minutes / (24 * 60))
    }
}

/** Friends' tracks: a tap plays it, a long press opens the friend. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FriendsListeningRow(
    entries: List<FriendListening>,
    onPlay: (FriendListening) -> Unit,
    onOpenFriend: (FriendListening) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        items(entries, key = { "fl_${it.pubkey}" }) { entry ->
            Column(
                Modifier
                    .width(CardWidth)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius))
                    .combinedClickable(onClick = { onPlay(entry) }, onLongClick = { onOpenFriend(entry) }),
            ) {
                Box {
                    AsyncImage(
                        entry.track.cover, entry.track.title, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(CardWidth).clip(RoundedCornerShape(ThumbnailCornerRadius))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(2.dp),
                    ) {
                        FriendAvatar(entry.pubkey, entry.name, entry.avatar, 30.dp)
                    }
                    if (entry.now) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF3DDC84)),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(entry.track.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    entry.name + " · " + friendWhen(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The round artist picture next to a "More like" title. */
@Composable
fun ArtistThumb(url: String?, size: Dp = 40.dp) {
    AsyncImage(
        url, null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** Friends' tracks to show: playing now first, then the latest of each, from the last three days. */
fun friendsListening(
    friends: List<FriendsHub.Friend>,
    data: Map<String, FriendsHub.FriendData>,
    myKey: String?,
    nameOf: (String, String?) -> String,
): List<FriendListening> {
    val now = System.currentTimeMillis()
    return friends.filter { it.pubkey != myKey }.mapNotNull { f ->
        val d = data[f.pubkey] ?: return@mapNotNull null
        val name = nameOf(f.pubkey, d.name ?: f.name.ifBlank { null })
        val playing = d.now?.takeIf { it.playing && now - it.at < 20 * 60_000L }
        if (playing != null) {
            FriendListening(f.pubkey, name, d.avatar, playing.track, now = true, at = playing.at)
        } else {
            val last = d.history?.maxByOrNull { it.at }?.takeIf { now - it.at < 3 * 24 * 60 * 60_000L }
                ?: d.now?.takeIf { now - it.at < 3 * 24 * 60 * 60_000L }?.let { it.track.copy(at = it.at) }
            last?.let { FriendListening(f.pubkey, name, d.avatar, it, now = false, at = it.at) }
        }
    }.sortedWith(compareByDescending<FriendListening> { it.now }.thenByDescending { it.at }).take(12)
}
