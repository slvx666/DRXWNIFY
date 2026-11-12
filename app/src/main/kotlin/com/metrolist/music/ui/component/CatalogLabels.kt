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
private fun catalogSource(): MetadataSource? {
    val state by Catalog.state.collectAsState()
    return state.source
}

fun likedSongsTitleRes(source: MetadataSource?): Int = when (source) {
    MetadataSource.YANDEX -> R.string.yandex_liked_songs
    MetadataSource.VK -> R.string.vk_liked_songs
    else -> R.string.spotify_liked_songs
}

fun serviceNameRes(source: MetadataSource?): Int = when (source) {
    MetadataSource.YANDEX -> R.string.yandex_music
    MetadataSource.VK -> R.string.vk_music_name
    else -> R.string.filter_spotify
}

fun serviceIconRes(source: MetadataSource?): Int = when (source) {
    MetadataSource.YANDEX -> R.drawable.yandex_music
    MetadataSource.VK -> R.drawable.vk_music
    else -> R.drawable.spotify
}

/** "Liked songs" of the connected account, named after its service. */
@Composable
fun catalogLikedSongsTitle(): String = stringResource(likedSongsTitleRes(catalogSource()))

/** The connected account's service name: "Spotify", "Yandex Music" or "VK Music". */
@Composable
fun catalogServiceName(): String = stringResource(serviceNameRes(catalogSource()))

/** Icon and brand colour of the connected account's service. */
@Composable
fun catalogServiceIcon(): Pair<Int, Color> {
    val source = catalogSource()
    return serviceIconRes(source) to com.metrolist.music.ui.theme.ServiceColors.brand(source ?: MetadataSource.SPOTIFY)
}

/** Plain-code variant for places outside composition (Android Auto). */
fun catalogLikedSongsTitleRes(): Int = likedSongsTitleRes(Catalog.source)
