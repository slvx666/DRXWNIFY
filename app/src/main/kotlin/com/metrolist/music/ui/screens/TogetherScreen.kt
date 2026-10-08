/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.R
import com.metrolist.music.friends.FriendsHub
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.screens.friends.FriendsContent
import com.metrolist.music.ui.utils.backToMain

/**
 * The "two people" screen: listening together in a room, and friends (profile, what they play,
 * listening along with them). Opened on the Friends tab by an invite link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TogetherScreen(
    navController: NavController,
    showBack: Boolean,
    initialTab: Int = TAB_FRIENDS,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val invite by FriendsHub.invite.collectAsState()
    val requests by FriendsHub.requests.collectAsState()
    LaunchedEffect(invite) { if (invite != null) tab = TAB_FRIENDS }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.together)) },
            navigationIcon = {
                if (showBack) {
                    IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                }
            },
        )
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == TAB_FRIENDS,
                onClick = { tab = TAB_FRIENDS },
                text = {
                    BadgedBox(badge = { if (requests.isNotEmpty()) Badge { Text(requests.size.toString()) } }) {
                        Text(stringResource(R.string.friends_title))
                    }
                },
            )
            Tab(
                selected = tab == TAB_LISTEN,
                onClick = { tab = TAB_LISTEN },
                text = { Text(stringResource(R.string.listen_together)) },
            )
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                TAB_LISTEN -> ListenTogetherScreen(navController, embedded = true)
                else -> FriendsContent(navController, topPadding = 12.dp)
            }
        }
    }
}

const val TAB_FRIENDS = 0
const val TAB_LISTEN = 1
