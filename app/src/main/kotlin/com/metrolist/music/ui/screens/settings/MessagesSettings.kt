/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.friends.ChatReactions
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.screens.friends.QuickReactionPicker
import com.metrolist.music.ui.screens.friends.ReactionGlyph
import com.metrolist.music.ui.utils.backToMain

/** Settings → Messages: the reaction a double tap on a message puts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesSettings(navController: NavController) {
    val quick by ChatReactions.quick.collectAsState()
    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.chat_quick_reaction), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.settings_messages_quick_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ReactionGlyph(quick, 36.dp)
        }
        QuickReactionPicker(selected = quick, onPick = ChatReactions::setQuick)
    }
    TopAppBar(
        title = { Text(stringResource(R.string.settings_messages)) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}
