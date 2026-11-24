/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.playlist

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.friends.CollabPalette
import com.metrolist.music.friends.CollabPlaylist
import com.metrolist.music.friends.CollabPlaylistManager
import com.metrolist.music.friends.CollabTrack
import com.metrolist.music.friends.FriendPlayback
import com.metrolist.music.friends.FriendsHub
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.screens.friends.FriendAvatar
import com.metrolist.music.ui.screens.friends.MusicPickerPanel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CollabPlaylistScreen(
    navController: NavController,
    pubkey: String,
    playlistId: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val myProfile by FriendsHub.profile.collectAsState()
    val isOwner = myProfile?.pubkey == pubkey

    // Watch this collaborative playlist (own or remote)
    val playlistFlow = remember(pubkey, playlistId) { CollabPlaylistManager.watchPlaylist(pubkey, playlistId) }
    val playlist by playlistFlow.collectAsState()

    DisposableEffect(pubkey, playlistId) {
        onDispose {
            if (!isOwner) CollabPlaylistManager.unwatchPlaylist(pubkey, playlistId)
        }
    }

    var showMusicPicker by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showPaletteSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var trackToDelete by remember { mutableStateOf<CollabTrack?>(null) }

    // Color Marking & Multi-Select Mode
    var isColoringMode by rememberSaveable { mutableStateOf(false) }
    var selectedColorHex by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedIndices = remember { mutableStateListOf<Int>() }

    // Normal multi-select mode (for deleting/queueing)
    var inSelectMode by rememberSaveable { mutableStateOf(false) }

    fun exitColoringMode() {
        isColoringMode = false
        if (!inSelectMode) selectedIndices.clear()
        selectedColorHex = null
    }

    BackHandler(enabled = isColoringMode || inSelectMode) {
        if (isColoringMode) exitColoringMode()
        if (inSelectMode) {
            inSelectMode = false
            selectedIndices.clear()
        }
    }

    // The list shows the order it plays in (round robin interleaves the senders): it showed the order
    // of adding while a tap played the fair queue, so tapping a track started a different one.
    val rawTracks = playlist?.tracks.orEmpty()
    val currentTracks = remember(playlist) {
        playlist?.let { if (it.roundRobin) CollabPlaylistManager.computeFairQueue(it) else it.tracks }.orEmpty()
    }

    /** A shown row's index in the stored list (what edits address). */
    fun rawIndexOf(track: CollabTrack): Int =
        rawTracks.indexOfFirst { it === track }.takeIf { it >= 0 } ?: rawTracks.indexOf(track)

    fun rawIndices(shown: Collection<Int>): Set<Int> =
        shown.mapNotNull { currentTracks.getOrNull(it)?.let(::rawIndexOf)?.takeIf { i -> i >= 0 } }.toSet()

    fun share() {
        val pl = playlist
        val name = pl?.name.orEmpty()
        val link = CollabPlaylistManager.generateShareLink(pubkey, playlistId, name)
        com.metrolist.music.ui.component.ShareSheet.open(
            title = name.ifBlank { context.getString(R.string.collab_playlists_title) },
            music = listOf(
                com.metrolist.music.friends.ChatMusic(
                    kind = com.metrolist.music.utils.AppLinks.Kind.COLLAB,
                    id = "$pubkey/$playlistId",
                    title = name,
                    by = myProfile?.name.orEmpty(),
                    count = pl?.totalCount ?: 0,
                ),
            ),
            linkText = if (name.isBlank()) link else "$name\n$link",
        )
    }

    fun playQueue(startIndex: Int = 0) {
        val pl = playlist ?: return
        val connection = playerConnection ?: return
        val queueTracks = if (pl.roundRobin) CollabPlaylistManager.computeFairQueue(pl) else pl.tracks
        if (queueTracks.isEmpty()) return
        scope.launch {
            connection.playQueue(FriendPlayback.listQueue(queueTracks.map { it.toTrackRef() }, startIndex.coerceIn(0, queueTracks.lastIndex)))
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = playlist?.name ?: stringResource(R.string.collab_playlists_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                    playlist?.let {
                        Text(
                            text = if (it.roundRobin) stringResource(R.string.collab_party_round_robin) else stringResource(R.string.friends_tracks_count, it.totalCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
            actions = {
                // Colour marks are the owner's: a guest's taps on the palette changed nothing.
                if (isOwner) {
                    IconButton(onClick = {
                        showPaletteSheet = !showPaletteSheet
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.palette),
                            contentDescription = stringResource(R.string.collab_palette_title),
                            tint = if (isColoringMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // Selection button
                IconButton(onClick = {
                    if (isColoringMode) exitColoringMode()
                    inSelectMode = !inSelectMode
                    if (!inSelectMode) selectedIndices.clear()
                }) {
                    Icon(
                        painter = painterResource(R.drawable.select_all),
                        contentDescription = stringResource(R.string.select_tracks),
                        tint = if (inSelectMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Share button (opens share choices)
                IconButton(onClick = ::share) {
                    Icon(painterResource(R.drawable.share), contentDescription = stringResource(R.string.collab_share_link))
                }

                // Settings (owner only) or info
                if (isOwner) {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(painterResource(R.drawable.tune), contentDescription = null)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(),
        )

        // Palette Strip (10 options: 1st is clear color, followed by 9 vibrant colors)
        AnimatedVisibility(visible = showPaletteSheet) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        stringResource(R.string.collab_palette_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // 1st option: Clear color (transparent circle with diagonal red slash)
                        item {
                            val isSelected = isColoringMode && selectedColorHex == null
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.5.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        shape = CircleShape,
                                    )
                                    .clickable {
                                        selectedColorHex = null
                                        isColoringMode = true
                                        showPaletteSheet = false
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Canvas(modifier = Modifier.size(24.dp)) {
                                    drawLine(
                                        color = Color(0xFFE53935),
                                        start = Offset(0f, size.height),
                                        end = Offset(size.width, 0f),
                                        strokeWidth = 2.5.dp.toPx(),
                                    )
                                }
                            }
                        }

                        // 9 vibrant colors
                        items(CollabPalette.COLORS) { color ->
                            val hex = CollabPalette.hexOf(color)
                            val isSelected = isColoringMode && selectedColorHex == hex
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.5.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Black.copy(alpha = 0.25f),
                                        shape = CircleShape,
                                    )
                                    .clickable {
                                        selectedColorHex = hex
                                        isColoringMode = true
                                        showPaletteSheet = false
                                    },
                            )
                        }
                    }
                }
            }
        }

        // Coloring action bar
        AnimatedVisibility(visible = isColoringMode) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    val activeColor = CollabPalette.colorOf(selectedColorHex)
                    if (activeColor != null) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(activeColor)
                        )
                    } else {
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, MaterialTheme.colorScheme.onPrimaryContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(modifier = Modifier.size(16.dp)) {
                                drawLine(color = Color.Red, start = Offset(0f, size.height), end = Offset(size.width, 0f), strokeWidth = 2.dp.toPx())
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.collab_selected_count, selectedIndices.size),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        if (selectedIndices.size == currentTracks.size) selectedIndices.clear()
                        else {
                            selectedIndices.clear()
                            selectedIndices.addAll(currentTracks.indices)
                        }
                    }) {
                        Text(if (selectedIndices.size == currentTracks.size) stringResource(R.string.collab_deselect_all) else stringResource(R.string.select_all_short))
                    }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = {
                            if (isOwner) {
                                CollabPlaylistManager.applyColor(playlistId, rawIndices(selectedIndices), selectedColorHex)
                            }
                            exitColoringMode()
                        },
                        enabled = selectedIndices.isNotEmpty() && isOwner,
                    ) {
                        Text(stringResource(R.string.collab_palette_apply))
                    }
                }
            }
        }

        // Normal multi-select action bar
        AnimatedVisibility(visible = inSelectMode && !isColoringMode) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        stringResource(R.string.collab_selected_count, selectedIndices.size),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        if (selectedIndices.size == currentTracks.size) selectedIndices.clear()
                        else {
                            selectedIndices.clear()
                            selectedIndices.addAll(currentTracks.indices)
                        }
                    }) {
                        Text(if (selectedIndices.size == currentTracks.size) stringResource(R.string.collab_deselect_all) else stringResource(R.string.select_all_short))
                    }
                    if (selectedIndices.isNotEmpty()) {
                        IconButton(onClick = {
                            val selTracks = selectedIndices.sorted().mapNotNull { currentTracks.getOrNull(it) }
                            if (selTracks.isNotEmpty()) {
                                playerConnection?.let { conn ->
                                    scope.launch {
                                        conn.playQueue(FriendPlayback.listQueue(selTracks.map { it.toTrackRef() }, 0))
                                    }
                                }
                            }
                        }) {
                            Icon(painterResource(R.drawable.play), contentDescription = stringResource(R.string.collab_play_selected), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        if (isOwner) {
                            IconButton(onClick = {
                                showPaletteSheet = true
                            }) {
                                Icon(painterResource(R.drawable.palette), contentDescription = stringResource(R.string.collab_palette_title), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            IconButton(onClick = {
                                // All at once: one by one it published the whole playlist per track.
                                CollabPlaylistManager.removeTracksFromOwn(playlistId, rawIndices(selectedIndices))
                                selectedIndices.clear()
                                inSelectMode = false
                            }) {
                                Icon(painterResource(R.drawable.delete), contentDescription = stringResource(R.string.collab_delete_selected), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    IconButton(onClick = {
                        inSelectMode = false
                        selectedIndices.clear()
                    }) {
                        Icon(painterResource(R.drawable.close), contentDescription = stringResource(android.R.string.cancel), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }

        Box(Modifier.weight(1f)) {
            if (currentTracks.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.queue_music),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.collab_empty_tracks),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.collab_empty_tracks_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() + 80.dp,
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(currentTracks, key = { index, item -> "${item.id}_${item.addedAt}_$index" }) { index, track ->
                        val inAnySelect = inSelectMode || isColoringMode
                        val isSelected = index in selectedIndices
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (inAnySelect) {
                                            if (isSelected) selectedIndices.remove(index) else selectedIndices.add(index)
                                        } else {
                                            playQueue(index)
                                        }
                                    },
                                    onLongClick = {
                                        if (!inAnySelect) {
                                            inSelectMode = true
                                            selectedIndices.clear()
                                            selectedIndices.add(index)
                                        } else {
                                            if (isOwner || track.addedByPubkey == myProfile?.pubkey) {
                                                trackToDelete = track
                                            }
                                        }
                                    },
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                // Already played: still listed, but no longer waiting.
                                .alpha(if (track.played) 0.5f else 1f),
                        ) {
                            if (inAnySelect) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        if (checked) selectedIndices.add(index) else selectedIndices.remove(index)
                                    },
                                )
                                Spacer(Modifier.width(6.dp))
                            }

                            // Left color strip marker (if marked with a color)
                            val color = CollabPalette.colorOf(track.colorHex)
                            if (color != null) {
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(color)
                                )
                                Spacer(Modifier.width(8.dp))
                            }

                            // Track Cover
                            AsyncImage(
                                model = track.cover,
                                contentDescription = track.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(Modifier.width(12.dp))

                            // Title, Artist, and "Added by" chip
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = track.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    Spacer(Modifier.width(6.dp))
                                    if (track.addedByAvatar != null) {
                                        FriendAvatar(track.addedByPubkey, track.addedByName, track.addedByAvatar, 14.dp)
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = track.addedByName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            // Action button (delete if owner or author)
                            if (isOwner || track.addedByPubkey == myProfile?.pubkey) {
                                IconButton(onClick = { trackToDelete = track }) {
                                    Icon(
                                        painter = painterResource(R.drawable.delete),
                                        contentDescription = stringResource(R.string.collab_delete_track),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Floating action button to Add Track
            FloatingActionButton(
                onClick = { showMusicPicker = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 20.dp,
                        bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() + 20.dp,
                    ),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(painterResource(R.drawable.add), contentDescription = stringResource(R.string.collab_add_track))
            }
        }
    }

    // A guest's tracks go to the owner's app as a request: "sent" at once, then "added" as soon as
    // they show up in the playlist — or, if nothing came back in a while, that the owner's app
    // will add them when it is opened (it used to say so every time, the owner online or not).
    var awaiting by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun keyOf(title: String, artist: String) = (title + "|" + artist).lowercase().filter { it.isLetterOrDigit() || it == '|' }
    androidx.compose.runtime.LaunchedEffect(awaiting, playlist) {
        if (awaiting.isEmpty()) return@LaunchedEffect
        val have = playlist?.tracks.orEmpty().mapTo(HashSet()) { keyOf(it.title, it.artist) }
        if (awaiting.all { it in have }) {
            awaiting = emptySet()
            Toast.makeText(context, R.string.collab_track_added_toast, Toast.LENGTH_SHORT).show()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(20_000)
        if (awaiting.isNotEmpty()) {
            awaiting = emptySet()
            Toast.makeText(context, R.string.collab_request_waiting_toast, Toast.LENGTH_LONG).show()
        }
    }

    // Music Picker Panel
    MusicPickerPanel(
        visible = showMusicPicker,
        onDismiss = { showMusicPicker = false },
        onSend = { pickedList ->
            showMusicPicker = false
            if (pickedList.isNotEmpty()) {
                CollabPlaylistManager.sendAddTracksRequest(pubkey, playlistId, pickedList.map { it.trackRef() })
                if (!isOwner) awaiting = pickedList.map { it.trackRef() }.mapTo(HashSet()) { keyOf(it.title, it.artist) }
                // A guest's tracks are a request: the owner's app adds them (and may be offline, or the
                // guest's limit reached), so "added" was a promise it couldn't keep.
                Toast.makeText(
                    context,
                    if (isOwner) R.string.collab_track_added_toast else R.string.collab_request_sent_toast,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
    )

    // Delete single track confirmation dialog
    trackToDelete?.let { track ->
        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            title = { Text(stringResource(R.string.collab_delete_track)) },
            text = { Text("${track.artist} — ${track.title}") },
            confirmButton = {
                TextButton(onClick = {
                    val idx = rawIndexOf(track)
                    if (isOwner && idx >= 0) {
                        CollabPlaylistManager.removeTrackFromOwn(playlistId, idx)
                    } else {
                        CollabPlaylistManager.sendRemoveTrackRequest(pubkey, playlistId, track.id)
                    }
                    trackToDelete = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { trackToDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // Settings dialog (Owner only)
    if (showSettingsDialog && isOwner && playlist != null) {
        val pl = playlist!!
        var roundRobin by remember { mutableStateOf(pl.roundRobin) }
        var allowByLink by remember { mutableStateOf(pl.allowByLink) }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text(stringResource(R.string.settings)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    // Access toggle: Friends & Link vs Friends only (default: Friends + Link)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { allowByLink = !allowByLink }
                            .padding(vertical = 8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.collab_access_toggle), fontWeight = FontWeight.SemiBold)
                            Text(
                                if (allowByLink) stringResource(R.string.collab_access_friends_and_link)
                                else stringResource(R.string.collab_access_friends_only),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = allowByLink, onCheckedChange = { allowByLink = it })
                    }

                    Spacer(Modifier.height(12.dp))

                    // Round Robin toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { roundRobin = !roundRobin }
                            .padding(vertical = 8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.collab_party_round_robin), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.collab_party_round_robin_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = roundRobin, onCheckedChange = { roundRobin = it })
                    }

                    Spacer(Modifier.height(16.dp))

                    // Delete whole playlist button
                    OutlinedButton(
                        onClick = {
                            showSettingsDialog = false
                            showDeleteConfirm = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(painterResource(R.drawable.delete), null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.collab_delete_playlist), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    CollabPlaylistManager.updateSettings(
                        id = playlistId,
                        roundRobin = roundRobin,
                        allowByLink = allowByLink,
                    )
                    showSettingsDialog = false
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // Delete playlist confirmation
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.collab_delete_playlist)) },
            text = { Text(stringResource(R.string.collab_delete_playlist_confirm, playlist?.name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    CollabPlaylistManager.deletePlaylist(playlistId)
                    showDeleteConfirm = false
                    navController.navigateUp()
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
