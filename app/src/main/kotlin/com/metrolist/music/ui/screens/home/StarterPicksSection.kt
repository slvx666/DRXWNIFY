/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.home

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.home.StarterPicks
import com.metrolist.music.ui.component.NavigationTitle
import com.metrolist.music.ui.component.SpotifyAlbumSectionRow
import com.metrolist.music.ui.component.SpotifyArtistSectionRow
import com.metrolist.music.ui.component.SpotifyTrackSectionRow
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyTrack

/** The line saying what a newcomer's page is, above its "For you" squares. */
fun LazyListScope.starterPicksNote() {
    item(key = "starter_note") {
        Text(
            text = stringResource(R.string.starter_picks_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/**
 * The home page of a newcomer (see [StarterPicks]) after its "For you" squares: the albums worth a
 * listen today, the genre and "More like" rows, the artists to start with.
 */
fun LazyListScope.starterPicksItems(
    picks: StarterPicks.Shown,
    horizontalItemWidth: Dp,
    isPlaying: Boolean,
    currentMediaId: String?,
    onPlay: (queueId: String, tracks: List<SpotifyTrack>, index: Int) -> Unit,
    onAlbum: (SpotifyAlbum) -> Unit,
    onAlbumPlay: (SpotifyAlbum) -> Unit,
    onArtist: (SpotifyArtist) -> Unit,
) {
    if (picks.today.isNotEmpty()) {
        item(key = "starter_today_title") { NavigationTitle(title = stringResource(R.string.starter_today)) }
        item(key = "starter_today") {
            SpotifyAlbumSectionRow(albums = picks.today, onAlbumClick = onAlbum, onAlbumPlay = onAlbumPlay)
        }
    }
    val rows = picks.genreRows.map { stringResource0(R.string.home_more_in_genre, it.title) to it } +
        picks.artistRows.map { stringResource0(R.string.home_more_like, it.title) to it }
    rows.forEachIndexed { i, (title, row) ->
        item(key = "starter_row_title_$i") { NavigationTitle(title = title()) }
        item(key = "starter_row_$i") {
            SpotifyTrackSectionRow(
                tracks = row.tracks,
                horizontalItemWidth = horizontalItemWidth,
                isPlaying = isPlaying,
                currentMediaId = currentMediaId,
                onTrackClick = { track -> onPlay("starter_row_$i", row.tracks, row.tracks.indexOf(track).coerceAtLeast(0)) },
                onTrackLongClick = { },
            )
        }
    }
    if (picks.artists.isNotEmpty()) {
        item(key = "starter_artists_title") { NavigationTitle(title = stringResource(R.string.starter_artists)) }
        item(key = "starter_artists") { SpotifyArtistSectionRow(artists = picks.artists, onArtistClick = onArtist) }
    }
}

/** A title resolved later, inside composition. */
private fun stringResource0(id: Int, arg: String): @Composable () -> String = { stringResource(id, arg) }
