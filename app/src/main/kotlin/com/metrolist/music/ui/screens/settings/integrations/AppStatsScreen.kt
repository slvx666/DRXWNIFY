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
 * playlists, friends and messages. Nothing is asked from the net. What was read stays in memory
 * ([StatsCache]): coming back shows it at once — past months don't change, the numbers are read again quietly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppStatsScreen(navController: NavController) {
    val database = LocalDatabase.current

    val thisMonthNow = remember { YearMonth.now() }
    val cachedMonths = StatsCache.months?.takeIf { it.first == thisMonthNow }?.second
    var numbers by remember { mutableStateOf(StatsCache.numbers) }
    var months by remember { mutableStateOf(cachedMonths ?: emptyList()) }
    var story by remember { mutableStateOf<MonthReview.Review?>(null) }
    var monthsLoading by remember { mutableStateOf(cachedMonths == null) }

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
            ).also { StatsCache.numbers = it }
            if (!monthsLoading) return@withContext
            // The months with enough listening, newest first (the last six).
            months = (1..6).mapNotNull { back ->
                runCatching { MonthReview.build(database, thisMonth.minusMonths(back.toLong())) }.getOrNull()
            }
            StatsCache.months = thisMonth to months
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
        months.forEach { r ->
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
                // Every month opens as a story: an arrow in a circle says the row is to be tapped.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                ) {
                    Icon(painterResource(R.drawable.navigate_next), contentDescription = stringResource(R.string.story_open), tint = MaterialTheme.colorScheme.primary)
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

private data class Numbers(
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

/** What the statistics page read last, kept while the app lives (the months for the month they were read in). */
private object StatsCache {
    @Volatile var numbers: Numbers? = null
    @Volatile var months: Pair<YearMonth, List<MonthReview.Review>>? = null
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}
