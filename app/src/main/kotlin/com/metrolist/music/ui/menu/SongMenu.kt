/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.PlaylistSong
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.viewmodels.CachePlaylistViewModel

/**
 * A saved song's menu: the app's one track menu ([PlayerMenu]), plus what only these screens know
 * — removing the song from the history, a playlist or the cache.
 */
@Composable
fun SongMenu(
    originalSong: Song,
    event: Event? = null,
    navController: NavController,
    playlistSong: PlaylistSong? = null,
    playlistBrowseId: String? = null,
    onDismiss: () -> Unit,
    isFromCache: Boolean = false,
    @Suppress("UNUSED_PARAMETER") spotifyId: String? = null,
) {
    val database = LocalDatabase.current
    val syncUtils = LocalSyncUtils.current
    val song = database.song(originalSong.id).collectAsState(initial = originalSong).value ?: originalSong
    val cacheViewModel = if (isFromCache) hiltViewModel<CachePlaylistViewModel>() else null

    val removeFromHistory = stringResource(R.string.remove_from_history)
    val removeFromCache = stringResource(R.string.remove_from_cache)
    val extraItems = buildList {
        if (event != null) {
            add(
                Material3MenuItemData(
                    title = { Text(text = removeFromHistory) },
                    icon = { Icon(painter = painterResource(R.drawable.delete), contentDescription = null) },
                    onClick = {
                        onDismiss()
                        database.query { delete(event) }
                    },
                ),
            )
        }
        if (cacheViewModel != null) {
            add(
                Material3MenuItemData(
                    title = { Text(text = removeFromCache) },
                    icon = { Icon(painter = painterResource(R.drawable.delete), contentDescription = null) },
                    onClick = {
                        onDismiss()
                        cacheViewModel.removeSongFromCache(song.id)
                    },
                ),
            )
        }
    }

    PlayerMenu(
        mediaMetadata = song.toMediaMetadata(),
        navController = navController,
        isCurrentTrack = false,
        onRemoveFromPlaylist = playlistSong?.let { ps ->
            {
                val capturedSetVideoId = ps.map.setVideoId
                database.transaction {
                    move(ps.map.playlistId, ps.map.position, Int.MAX_VALUE)
                    delete(ps.map.copy(position = Int.MAX_VALUE))
                }
                playlistBrowseId?.let { browseId ->
                    syncUtils.scheduleRemoveFromPlaylist(browseId, ps.map.songId, ps.map.playlistId) {
                        capturedSetVideoId
                    }
                }
            }
        },
        extraItems = extraItems,
        onDismiss = onDismiss,
    )
}
