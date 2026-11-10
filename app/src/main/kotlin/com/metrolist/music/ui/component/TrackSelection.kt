/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.height
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.R

/**
 * Picking several tracks of a list to act on them together (AIMP style): the "select" button at the
 * top right turns it on, taps then tick rows instead of playing them, and a bar at the bottom
 * downloads, sends or deletes what is ticked. A long press still opens the track's own menu.
 */
@Stable
class TrackSelection {
    var active by mutableStateOf(false)
        private set
    val keys = mutableStateListOf<String>()

    /** Measured height of the bar, px: the list leaves exactly this much room under its last row. */
    internal var barHeightPx by androidx.compose.runtime.mutableIntStateOf(0)

    fun start() {
        active = true
    }

    fun stop() {
        active = false
        keys.clear()
    }

    fun isSelected(key: String) = key in keys

    fun toggle(key: String) {
        if (!keys.remove(key)) keys.add(key)
    }

    fun toggleAll(all: List<String>) {
        if (keys.size == all.size) keys.clear() else { keys.clear(); keys.addAll(all) }
    }
}

@Composable
fun rememberTrackSelection(): TrackSelection {
    val selection = remember { TrackSelection() }
    BackHandler(enabled = selection.active) { selection.stop() }
    return selection
}

/** The top-right actions: "select" when off; "all" and "done" when on. */
@Composable
fun TrackSelectionActions(selection: TrackSelection, allKeys: List<String>) {
    if (!selection.active) {
        IconButton(onClick = selection::start, enabled = allKeys.isNotEmpty()) {
            Icon(painterResource(R.drawable.select_all), contentDescription = stringResource(R.string.select_tracks))
        }
    } else {
        TextButton(onClick = { selection.toggleAll(allKeys) }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
            Text(stringResource(if (selection.keys.size == allKeys.size) R.string.select_none else R.string.select_all_short))
        }
        IconButton(onClick = selection::stop) {
            Icon(painterResource(R.drawable.close), contentDescription = stringResource(R.string.selection_done))
        }
    }
}

/** The tick in a row: slides in when selecting starts, out when it ends. */
@Composable
fun TrackSelectionCheck(visible: Boolean, checked: Boolean, onToggle: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + androidx.compose.animation.expandHorizontally(tween(220)) + androidx.compose.animation.scaleIn(tween(220), initialScale = 0.6f),
        exit = fadeOut(tween(140)) + androidx.compose.animation.shrinkHorizontally(tween(200)),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** Room under the list for the bar while selecting, so the last row can still be ticked. */
@Composable
fun TrackSelectionSpacer(selection: TrackSelection) {
    val height by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (selection.active) {
            with(androidx.compose.ui.platform.LocalDensity.current) { selection.barHeightPx.toDp() }.takeIf { it > 0.dp } ?: SELECTION_BAR_SPACE
        } else {
            END_OF_LIST_GAP
        },
        animationSpec = tween(240),
        label = "selectionSpacer",
    )
    androidx.compose.foundation.layout.Spacer(Modifier.fillMaxWidth().height(height))
}

private val SELECTION_BAR_SPACE = 110.dp

/** Breathing room so the last row isn't tucked under the mini player's edge. */ val END_OF_LIST_GAP = 16.dp

/**
 * Download / send / block / delete for the ticked tracks, floating at the bottom of the list.
 * "Send" opens the share sheet (to a chat, as files, to a playlist, as a link for one track).
 * [shareMediaIds] turns ticked keys into the ids their downloads are kept under;
 * [songIdsForPlaylist] turns them into song ids, writing their song rows first;
 * [blockKeys] into the keys [com.metrolist.music.playback.BlockedTracks] keeps them under.
 */
@Composable
fun TrackSelectionBar(
    selection: TrackSelection,
    onDownload: (List<String>) -> Unit,
    shareMediaIds: suspend (List<String>) -> List<String>,
    onDelete: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    songIdsForPlaylist: (suspend (List<String>) -> List<String>)? = null,
    blockKeys: (List<String>) -> List<String> = { keys -> keys.map(com.metrolist.music.playback.BlockedTracks::keyOf) },
) {
    var confirmDelete by remember { mutableStateOf<List<String>?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val database = com.metrolist.music.LocalDatabase.current
    val downloadUtil = com.metrolist.music.LocalDownloadUtil.current
    val blockedKeys by com.metrolist.music.playback.BlockedTracks.keys.collectAsState()
    AnimatedVisibility(
        visible = selection.active,
        enter = slideInVertically(tween(280)) { it / 2 } + fadeIn(tween(220)) +
            androidx.compose.animation.scaleIn(tween(280), initialScale = 0.94f),
        exit = slideOutVertically(tween(200)) { it / 2 } + fadeOut(tween(160)),
        modifier = modifier.then(androidx.compose.ui.Modifier.onSizeChanged { if (it.height > 0) selection.barHeightPx = it.height }),
    ) {
        val count = selection.keys.size
        val enabled = count > 0
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                    // The count in a small pill that ticks over as tracks are picked.
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        androidx.compose.animation.AnimatedContent(
                            targetState = count,
                            transitionSpec = {
                                (slideInVertically { if (targetState > initialState) it else -it } + fadeIn()) togetherWith
                                    (slideOutVertically { if (targetState > initialState) -it else it } + fadeOut())
                            },
                            label = "selectedCount",
                        ) { n ->
                            Text(
                                text = n.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                            )
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.selected_word),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Every ticked track already blocked: the button lets them play again instead.
                val allBlocked = enabled && blockKeys(selection.keys.toList()).all { it in blockedKeys }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    SelectionAction(R.drawable.download, R.string.action_download, enabled) { onDownload(selection.keys.toList()) }
                    SelectionAction(R.drawable.share, R.string.send_files, enabled) {
                        val keys = selection.keys.toList()
                        SelectedTracks.openShareSheet(
                            context = context,
                            database = database,
                            downloadUtil = downloadUtil,
                            count = keys.size,
                            songIds = songIdsForPlaylist?.let { lookup -> { lookup(keys) } },
                            mediaIds = { shareMediaIds(keys) },
                        )
                    }
                    SelectionAction(
                        if (allBlocked) R.drawable.play else R.drawable.block,
                        if (allBlocked) R.string.selection_unblock else R.string.selection_block,
                        enabled,
                    ) {
                        val keys = blockKeys(selection.keys.toList())
                        com.metrolist.music.playback.BlockedTracks.set(keys, blocked = !allBlocked)
                        android.widget.Toast.makeText(
                            context,
                            context.resources.getQuantityString(
                                if (allBlocked) R.plurals.tracks_unblocked else R.plurals.tracks_blocked,
                                keys.size,
                                keys.size,
                            ),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        selection.stop()
                    }
                    SelectionAction(R.drawable.delete, R.string.remove_downloads, enabled) { confirmDelete = selection.keys.toList() }
                }
            }
        }
    }

    confirmDelete?.let { keys ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(androidx.compose.ui.res.pluralStringResource(R.plurals.remove_downloads_confirm, keys.size, keys.size)) },
            text = { Text(stringResource(R.string.remove_downloads_note)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    onDelete(keys)
                    selection.stop()
                }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(R.string.remove_downloads)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

/**
 * One action of the bar: an equal share of the width, its label on one line (shrunk to fit when a
 * wide font doesn't leave room — "Удалить" used to break into "Уда / лит / ь").
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.SelectionAction(icon: Int, label: Int, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = com.metrolist.music.ui.theme.tuiButtonShape(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 6.dp),
        modifier = Modifier.weight(1f),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize),
            )
        }
    }
}

/**
 * The scroll-threshold padding for dragging rows of a list that has the selection bar: the bar
 * floats over the bottom of the list, so a row dragged under it scrolls the list on (as in AIMP)
 * instead of stopping at the bar's edge.
 */
@Composable
fun TrackSelection.dragScrollPadding(base: androidx.compose.foundation.layout.PaddingValues): androidx.compose.foundation.layout.PaddingValues {
    val bar = with(androidx.compose.ui.platform.LocalDensity.current) { (if (active) barHeightPx else 0).toDp() }
    return androidx.compose.foundation.layout.PaddingValues(
        top = base.calculateTopPadding(),
        bottom = base.calculateBottomPadding() + bar,
    )
}

/** The drag handle at the start of a row, sliding in while tracks are being picked. */
@Composable
fun SelectionDragHandle(visible: Boolean, handleModifier: Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + androidx.compose.animation.expandHorizontally(tween(240)),
        exit = fadeOut(tween(150)) + androidx.compose.animation.shrinkHorizontally(tween(220)),
    ) {
        Icon(
            painter = painterResource(R.drawable.drag_handle),
            contentDescription = stringResource(R.string.drag_to_reorder),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = handleModifier.padding(end = 10.dp).size(24.dp),
        )
    }
}
