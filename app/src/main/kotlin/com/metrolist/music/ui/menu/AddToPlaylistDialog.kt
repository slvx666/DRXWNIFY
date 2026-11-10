/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.widget.Toast

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.constants.AddToPlaylistSortDescendingKey
import com.metrolist.music.constants.AddToPlaylistSortTypeKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.PlaylistSortType
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.ui.component.CreatePlaylistDialog
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.PlaylistListItem
import com.metrolist.music.ui.component.SortHeader
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.PlaylistsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.withContext
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.FilterChip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.FilterChipDefaults
import com.metrolist.music.LocalSyncUtils

@Composable
fun AddToPlaylistDialog(
    isVisible: Boolean,
    allowSyncing: Boolean = true,
    initialTextFieldValue: String? = null,
    onGetSong: suspend (Playlist) -> List<String>, // list of song ids. Songs should be inserted to database in this function.
    onGetSongIds: (suspend () -> List<String>)? = null,
    onDismiss: () -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val syncUtils = LocalSyncUtils.current
    val coroutineScope = rememberCoroutineScope()
    val (sortType, onSortTypeChange) = rememberEnumPreference(
        AddToPlaylistSortTypeKey,
        PlaylistSortType.NAME
    )
    val (sortDescending, onSortDescendingChange) = rememberPreference(
        AddToPlaylistSortDescendingKey,
        false
    )
    val playlists by viewModel.allPlaylists.collectAsState()
    val (innerTubeCookie) = rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }
    var showCreatePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showDuplicateDialog by remember {
        mutableStateOf(false)
    }
    var selectedPlaylist by remember {
        mutableStateOf<Playlist?>(null)
    }
    var songIds by remember {
        mutableStateOf<List<String>?>(null)
    }
    var duplicates by remember {
        mutableStateOf(emptyList<String>())
    }
    // How many of the songs each playlist already has (playlists with none are missing).
    var playlistsContainingSong by remember {
        mutableStateOf<Map<String, Int>>(emptyMap())
    }

    suspend fun addSongsAndSync(targetPlaylist: Playlist, ids: List<String>) {
        // Only songs that have a row in the database can go into a playlist.
        val added = ids.count { database.getSongByIdBlocking(it) != null }
        database.addSongToPlaylist(targetPlaylist, ids)
        // Adding used to happen in silence — and a song missing from the database was skipped
        // just as silently. The toast now says what really happened.
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                if (added > 0) {
                    context.getString(R.string.spotify_track_added_to_playlist, targetPlaylist.playlist.name)
                } else {
                    context.getString(R.string.add_to_playlist_failed)
                },
                Toast.LENGTH_SHORT,
            ).show()
        }
        targetPlaylist.playlist.browseId?.let { plist ->
            ids.forEach { songId ->
                syncUtils.registerPendingAdd(plist, songId)
                try {
                    YouTube.addToPlaylist(plist, songId)
                } finally {
                    syncUtils.unregisterPendingAdd(plist, songId)
                }
            }
        }
    }

    LaunchedEffect(isVisible, playlists.isEmpty()) {
        if (!isVisible || playlists.isEmpty()) return@LaunchedEffect
        if (songIds != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            songIds = onGetSongIds?.invoke() ?: onGetSong(playlists.first())
        }
    }
    LaunchedEffect(isVisible, songIds, playlists) {
        if (!isVisible) {
            playlistsContainingSong = emptyMap()
            return@LaunchedEffect
        }
        val ids = songIds ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            playlistsContainingSong = com.metrolist.music.db.PlaylistDuplicates
                .find(database, playlists.map { it.id }, ids)
                .mapValues { it.value.size }
        }
    }

    if (isVisible) {
        ListDialog(
            onDismiss = onDismiss,
        ) {
            item {
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.7f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "buttonScale"
                )
                FilledTonalButton(
                    onClick = { showCreatePlaylistDialog = true},
                    shape = RoundedCornerShape(50),
                    interactionSource = interactionSource,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.add),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.create_playlist),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (playlists.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            PlaylistSortType.entries.forEach { type ->
                                val selected = sortType == type
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSortTypeChange(type) },
                                    shape = RoundedCornerShape(50),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        borderWidth = 0.dp,
                                        selectedBorderWidth = 0.dp,
                                    ),
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                    label = {
                                        Text(
                                            text = stringResource(when (type) {
                                                PlaylistSortType.CREATE_DATE  -> R.string.sort_by_create_date
                                                PlaylistSortType.NAME         -> R.string.sort_by_name
                                                PlaylistSortType.SONG_COUNT   -> R.string.sort_by_song_count
                                                PlaylistSortType.LAST_UPDATED -> R.string.sort_by_last_updated
                                            }),
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    }
                                )
                            }
                        }

                        val arrowBg by animateColorAsState(
                            targetValue = if (sortDescending) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "arrowBg"
                        )
                        val arrowFg by animateColorAsState(
                            targetValue = if (sortDescending) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "arrowFg"
                        )
                        IconToggleButton(
                            checked = sortDescending,
                            onCheckedChange = { onSortDescendingChange(it) },
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(arrowBg)
                                .size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (sortDescending) R.drawable.arrow_downward else R.drawable.arrow_upward
                                ),
                                contentDescription = stringResource(
                                    if (sortDescending) R.string.sort_descending else R.string.sort_ascending
                                ),
                                tint = arrowFg,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            items(playlists) { playlist ->
                val alreadyHas = playlistsContainingSong[playlist.id] ?: 0
                val containsSong = alreadyHas > 0
                val total = songIds?.size ?: 0
                val rowBg by animateColorAsState(
                    targetValue = if (containsSong)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else Color.Transparent,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "playlistBg"
                )
                PlaylistListItem(
                    playlist = playlist,
                    // Plainly said, not just a tint: the track is already there (or how many of them).
                    trailingContent = {
                        if (containsSong) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.done),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = if (total <= 1) {
                                        stringResource(R.string.playlist_already_has)
                                    } else {
                                        stringResource(R.string.playlist_already_has_some, alreadyHas, total)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    },
                    modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(rowBg)
                    .clickable {
                        selectedPlaylist = playlist
                        coroutineScope.launch(Dispatchers.IO) {
                            // Always through onGetSong: it is what writes the song rows. The ids
                            // from onGetSongIds (used to mark playlists that already have the song)
                            // may name a song that is not in the database yet, and such a song
                            // would be skipped without a word.
                            songIds = onGetSong(playlist)
                            duplicates = com.metrolist.music.db.PlaylistDuplicates
                                .find(database, listOf(playlist.id), songIds!!)[playlist.id].orEmpty()
                            if (duplicates.isNotEmpty()) {
                                showDuplicateDialog = true
                            } else {
                                onDismiss()
                                addSongsAndSync(playlist, songIds!!)
                            }
                        }
                    }
                )
            }
        }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            initialTextFieldValue = initialTextFieldValue,
            allowSyncing = allowSyncing
        )
    }

    // duplicate songs warning
        if (showDuplicateDialog) {
            DefaultDialog(
                title = { Text(stringResource(R.string.duplicates)) },
                buttons = {
                    TextButton(
                        onClick = {
                            showDuplicateDialog = false
                            onDismiss()
                            coroutineScope.launch(Dispatchers.IO) {
                                addSongsAndSync(
                                    selectedPlaylist!!,
                                    songIds!!.filter { !duplicates.contains(it) }
                                )
                            }
                        }
                    , shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                        Text(stringResource(R.string.skip_duplicates))
                    }

                    TextButton(
                        onClick = {
                            showDuplicateDialog = false
                            onDismiss()
                            coroutineScope.launch(Dispatchers.IO) {
                                addSongsAndSync(selectedPlaylist!!, songIds!!)
                            }
                        }
                    , shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                        Text(stringResource(R.string.add_anyway))
                    }

                    TextButton(
                        onClick = {
                            showDuplicateDialog = false
                        }
                    , shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
                onDismiss = {
                    showDuplicateDialog = false
                }
            ) {
                Text(
                    text = if (duplicates.size == 1) {
                        stringResource(R.string.duplicates_description_single)
                    } else {
                        stringResource(R.string.duplicates_description_multiple, duplicates.size)
                    },
                    textAlign = TextAlign.Start,
                    modifier = Modifier.align(Alignment.Start)
                )
            }
        }
}
