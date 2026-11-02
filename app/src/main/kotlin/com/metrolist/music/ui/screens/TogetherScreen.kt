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
import androidx.compose.animation.togetherWith
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
    val unread by androidx.compose.runtime.remember { com.metrolist.music.friends.ChatHub.unreadTotal() }.collectAsState(0)
    LaunchedEffect(invite) { if (invite != null) tab = TAB_FRIENDS }
    // Something to forward (a share button's "To chats"): only the chats, a tap on one sends it there.
    val forwarding by com.metrolist.music.friends.ChatHub.forward.collectAsState()
    LaunchedEffect(forwarding) { if (forwarding != null) tab = TAB_CHATS }
    fun cancelForward() {
        com.metrolist.music.friends.ChatHub.cancelForward()
        if (showBack) navController.navigateUp()
    }
    androidx.activity.compose.BackHandler(enabled = forwarding != null) { cancelForward() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = {
                androidx.compose.animation.AnimatedContent(forwarding != null, label = "title") { f ->
                    Text(stringResource(if (f) R.string.chat_forward_title else R.string.together))
                }
            },
            navigationIcon = {
                if (forwarding != null) {
                    androidx.compose.material3.IconButton(onClick = ::cancelForward) {
                        Icon(painterResource(R.drawable.close), contentDescription = null)
                    }
                } else if (showBack) {
                    IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                }
            },
        )
        // Shown left to right: Chats, Friends, Listen together (the constants keep their old values).
        val order = listOf(TAB_CHATS, TAB_FRIENDS, TAB_LISTEN)
        // The tabs lie side by side in a pager: the neighbours stay built, so going to one is only a
        // slide (building Friends while it slid in made it stutter). They are built a moment after
        // the screen itself, to keep its own opening (and coming back from a chat) light.
        val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = order.indexOf(tab).coerceAtLeast(0)) { order.size }
        var neighbours by androidx.compose.runtime.remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(600)
            neighbours = 1
        }
        // A tab picked (tapped, an invite, a forward): the pager goes there; a swipe: the tab follows.
        LaunchedEffect(tab) {
            val page = order.indexOf(tab).coerceAtLeast(0)
            if (pager.currentPage != page) {
                pager.animateScrollToPage(page, animationSpec = androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            }
        }
        LaunchedEffect(pager.settledPage) { tab = order[pager.settledPage] }
        androidx.compose.animation.AnimatedVisibility(forwarding == null) {
        PrimaryTabRow(selectedTabIndex = pager.targetPage) {
            Tab(
                selected = tab == TAB_CHATS,
                onClick = { tab = TAB_CHATS },
                text = {
                    BadgedBox(badge = { if (unread > 0) Badge { Text(unread.toString()) } }) {
                        Text(stringResource(R.string.chats_title))
                    }
                },
            )
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
        }
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            beyondViewportPageCount = neighbours,
            // Forwarding shows only the chats.
            userScrollEnabled = forwarding == null,
            key = { order[it] },
            modifier = Modifier.weight(1f),
        ) { page ->
            Box(Modifier.fillMaxSize()) {
                when (order[page]) {
                    TAB_LISTEN -> ListenTogetherScreen(navController, embedded = true)
                    TAB_CHATS -> com.metrolist.music.ui.screens.friends.ChatsContent(navController)
                    else -> FriendsContent(navController, topPadding = 12.dp)
                }
            }
        }
    }
}

const val TAB_FRIENDS = 0
const val TAB_LISTEN = 1
const val TAB_CHATS = 2
