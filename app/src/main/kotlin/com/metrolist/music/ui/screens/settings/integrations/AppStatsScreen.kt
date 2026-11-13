/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.catalog.MonthReview
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.PreferenceGroupTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.YearMonth

/**
 * The app's own statistics, in Integrations: the month in review (any of the last months with
 * listening, as a story), and plain numbers from what is already on the phone — listening, likes,
 * playlists, friends and messages. Read once when the page opens; nothing is asked from the net.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppStatsScreen(navController: NavController) {
    val database = LocalDatabase.current

    data class Numbers(
        val minutesMonth: Long,
        val minutesAll: Long,
        val listensAll: Int,
        val artistsAll: Int,
        val liked: Int,
        val playlists: Int,
        val friends: Int,
        val written: Int,
        val received: Int,
    )

    var numbers by remember { mutableStateOf<Numbers?>(null) }
    var months by remember { mutableStateOf<List<MonthReview.Review>>(emptyList()) }
    var story by remember { mutableStateOf<MonthReview.Review?>(null) }
    var monthsLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val events = database.events().first()
            val counted = events.filter { com.metrolist.music.portrait.Listening.counts(it.event.playTime, it.song.song.duration * 1000L) }
            val thisMonth = YearMonth.now()
            val (written, received) = com.metrolist.music.friends.ChatHub.messageCounts()
            numbers = Numbers(
                minutesMonth = events.filter { YearMonth.from(it.event.timestamp) == thisMonth }.sumOf { it.event.playTime } / 60_000L,
                minutesAll = events.sumOf { it.event.playTime } / 60_000L,
                listensAll = counted.size,
                artistsAll = counted.mapNotNull { it.song.artists.firstOrNull()?.name }.toSet().size,
                liked = runCatching { database.likedSongsCount().first() }.getOrDefault(0),
                playlists = runCatching { database.playlistsByCreateDateAsc().first().size }.getOrDefault(0),
                friends = com.metrolist.music.friends.FriendsHub.friends.value.size,
                written = written,
                received = received,
            )
            // The months with enough listening, newest first (the last six).
            months = (1..6).mapNotNull { back ->
                runCatching { MonthReview.build(database, thisMonth.minusMonths(back.toLong())) }.getOrNull()
            }
            monthsLoading = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))
        val n = numbers
        if (n == null) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        PreferenceGroupTitle(title = stringResource(R.string.app_stats_reviews))
        if (monthsLoading) {
            // The reviews take a few seconds (genres are looked up): an outline says they are coming.
            com.metrolist.music.ui.component.shimmer.ShimmerHost(showGradient = false, modifier = Modifier.padding(horizontal = 16.dp)) {
                repeat(2) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                        Box(
                            Modifier
                                .padding(end = 14.dp)
                                .size(28.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface),
                        )
                        Column(Modifier.weight(1f)) {
                            com.metrolist.music.ui.component.shimmer.TextPlaceholder(height = 18.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            com.metrolist.music.ui.component.shimmer.TextPlaceholder(height = 12.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.app_stats_reviews_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else if (months.isEmpty()) {
            Text(stringResource(R.string.app_stats_no_review), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
        months.forEachIndexed { i, r ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { story = r }.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(painterResource(R.drawable.stats), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.padding(start = 14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.month_review_title, monthName(r.month)), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.app_stats_review_line, r.minutes, r.topArtists.firstOrNull()?.title ?: "—"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (i == 0) {
                    Button(onClick = { story = r }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(R.string.story_open)) }
                }
            }
        }

        PreferenceGroupTitle(title = stringResource(R.string.app_stats_listening))
        StatLine(stringResource(R.string.app_stats_minutes_month), n.minutesMonth.toString())
        StatLine(stringResource(R.string.app_stats_minutes_all), n.minutesAll.toString())
        StatLine(stringResource(R.string.app_stats_listens_all), n.listensAll.toString())
        StatLine(stringResource(R.string.app_stats_artists_all), n.artistsAll.toString())
        StatLine(stringResource(R.string.app_stats_liked), n.liked.toString())
        StatLine(stringResource(R.string.app_stats_playlists), n.playlists.toString())

        PreferenceGroupTitle(title = stringResource(R.string.app_stats_social))
        StatLine(stringResource(R.string.app_stats_friends), n.friends.toString())
        StatLine(stringResource(R.string.app_stats_written), n.written.toString())
        StatLine(stringResource(R.string.app_stats_received), n.received.toString())
        Spacer(Modifier.height(24.dp))
    }
    story?.let { r -> com.metrolist.music.ui.screens.MonthReviewStory(r) { story = null } }
    TopAppBar(
        title = { Text(stringResource(R.string.app_stats_title)) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = {}) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}
