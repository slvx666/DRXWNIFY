/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.metrolist.music.R
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.constants.MetadataSource

/** Id of the catalog's liked-songs entry in the library lists (Spotify or Yandex Music alike). */
const val CATALOG_LIKED_ID = "spotify_liked_songs"

@Composable
private fun catalogIsYandex(): Boolean {
    val state by Catalog.state.collectAsState()
    return state.source == MetadataSource.YANDEX
}

/** "Liked songs" of the connected account, named after its service. */
@Composable
fun catalogLikedSongsTitle(): String =
    stringResource(if (catalogIsYandex()) R.string.yandex_liked_songs else R.string.spotify_liked_songs)

/** The connected account's service name: "Spotify" or "Yandex Music". */
@Composable
fun catalogServiceName(): String =
    stringResource(if (catalogIsYandex()) R.string.yandex_music else R.string.filter_spotify)

/** Icon and brand colour of the connected account's service. */
@Composable
fun catalogServiceIcon(): Pair<Int, Color> =
    if (catalogIsYandex()) R.drawable.yandex_music to Color(0xFFFFCC00) else R.drawable.spotify to Color(0xFF1DB954)

/** Plain-code variant for places outside composition (Android Auto). */
fun catalogLikedSongsTitleRes(): Int =
    if (Catalog.source == MetadataSource.YANDEX) R.string.yandex_liked_songs else R.string.spotify_liked_songs
