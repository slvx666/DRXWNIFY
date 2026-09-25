/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.R
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.ui.component.Material3MenuItemData
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/** A YouTube Music song's menu: the app's one track menu ([PlayerMenu]) plus "remove from history". */
@Composable
fun YouTubeSongMenu(
    song: SongItem,
    navController: NavController,
    onDismiss: () -> Unit,
    onHistoryRemoved: () -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    val removeFromHistory = stringResource(R.string.remove_from_history)
    val extraItems = buildList {
        val token = song.historyRemoveToken
        if (token != null) {
            add(
                Material3MenuItemData(
                    title = { Text(text = removeFromHistory) },
                    icon = { Icon(painter = painterResource(R.drawable.delete), contentDescription = null) },
                    onClick = {
                        coroutineScope.launch {
                            YouTube.feedback(listOf(token))
                                .onFailure { Timber.e(it, "Failed to remove ${song.id} from YTM history") }
                            delay(500)
                            onHistoryRemoved()
                            onDismiss()
                        }
                    },
                ),
            )
        }
    }

    PlayerMenu(
        mediaMetadata = song.toMediaMetadata(),
        navController = navController,
        isCurrentTrack = false,
        extraItems = extraItems,
        onDismiss = onDismiss,
    )
}
