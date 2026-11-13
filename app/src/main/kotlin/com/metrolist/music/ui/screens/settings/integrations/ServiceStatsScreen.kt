/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.catalog.MonthReview
import com.metrolist.music.catalog.ServiceStats
import com.metrolist.music.constants.MetadataSource
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.PreferenceGroupTitle
import com.metrolist.music.ui.component.serviceNameRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.TextStyle
import java.util.Locale

/**
 * The service's own statistics on the account's page: Spotify's tops (4 weeks / 6 months / all
 * time), Yandex Music's likes by month and most liked artists — and the month in review from the
 * app's history. Not shared with friends.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceStatsScreen(navController: NavController, service: MetadataSource) {
    val database = LocalDatabase.current
    var range by rememberSaveable { mutableStateOf(ServiceStats.Range.SHORT) }
    var spotify by remember { mutableStateOf<ServiceStats.SpotifyTops?>(null) }
    var yandex by remember { mutableStateOf<ServiceStats.YandexStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var review by remember { mutableStateOf<MonthReview.Review?>(null) }

    LaunchedEffect(service, range) {
        loading = true
        error = null
        withContext(Dispatchers.IO) {
            when (service) {
                MetadataSource.SPOTIFY -> ServiceStats.spotifyTops(range)
                    .onSuccess { spotify = it }.onFailure { error = it.message }
                MetadataSource.YANDEX -> ServiceStats.yandexStats()
                    .onSuccess { yandex = it }.onFailure { error = it.message }
                MetadataSource.VK -> Unit
            }
        }
        loading = false
    }
    LaunchedEffect(Unit) {
        review = withContext(Dispatchers.IO) {
            runCatching { MonthReview.build(database, java.time.YearMonth.now().minusMonths(1)) }.getOrNull()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))
        if (service == MetadataSource.SPOTIFY) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ServiceStats.Range.entries.forEach { r ->
                    FilterChip(
                        selected = r == range,
                        onClick = { range = r },
                        label = {
                            Text(
                                stringResource(
                                    when (r) {
                                        ServiceStats.Range.SHORT -> R.string.stats_range_short
                                        ServiceStats.Range.MEDIUM -> R.string.stats_range_medium
                                        ServiceStats.Range.LONG -> R.string.stats_range_long
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        error?.takeIf { !loading && spotify == null && yandex == null }?.let {
            // Spotify answers "too many requests" to its top lists now and then: said plainly, with when to try.
            val text = if (it.contains("rate limit", ignoreCase = true) || it.contains("429")) stringResource(R.string.stats_rate_limited) else stringResource(R.string.stats_failed, it)
            Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
        if (service == MetadataSource.SPOTIFY && !loading) {
            spotify?.let { tops ->
                PreferenceGroupTitle(title = stringResource(R.string.stats_top_artists))
                tops.artists.take(20).forEachIndexed { i, a ->
                    RankRow(i + 1, a.name, null, a.images.firstOrNull()?.url, round = true) { navController.navigate("spotify_artist/${a.id}") }
                }
                PreferenceGroupTitle(title = stringResource(R.string.stats_top_tracks))
                tops.tracks.take(50).forEachIndexed { i, t ->
                    RankRow(i + 1, t.name, t.artists.joinToString { it.name }, t.album?.images?.lastOrNull()?.url ?: t.album?.images?.firstOrNull()?.url) {
                        t.album?.id?.takeIf { it.isNotBlank() }?.let { navController.navigate("spotify_album/$it") }
                    }
                }
            }
        }
        if (service == MetadataSource.YANDEX && !loading) {
            yandex?.let { y ->
                Text(
                    stringResource(R.string.stats_yandex_total, y.total),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                PreferenceGroupTitle(title = stringResource(R.string.stats_likes_by_month))
                MonthBars(y.byMonth)
                PreferenceGroupTitle(title = stringResource(R.string.stats_liked_artists))
                y.artists.forEachIndexed { i, a -> RankRow(i + 1, a.name, a.count.toString(), null, round = true) {} }
            }
        }
        var showStory by remember { mutableStateOf(false) }
        review?.let { r ->
            PreferenceGroupTitle(title = stringResource(R.string.month_review_title, monthName(r.month)))
            MonthReviewBody(r)
            androidx.compose.material3.Button(
                onClick = { showStory = true },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = com.metrolist.music.ui.theme.tuiButtonShape(),
            ) { Text(stringResource(R.string.story_open)) }
            if (showStory) com.metrolist.music.ui.screens.MonthReviewStory(r) { showStory = false }
        }
        Spacer(Modifier.height(24.dp))
    }
    TopAppBar(
        title = { Text(stringResource(R.string.service_stats_title) + " · " + stringResource(serviceNameRes(service))) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = {}) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}

@Composable
private fun RankRow(rank: Int, title: String, subtitle: String?, image: String?, round: Boolean = false, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(rank.toString(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(if (round) CircleShape else RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Likes per month as bars, the newest month last. */
@Composable
private fun MonthBars(months: List<ServiceStats.MonthCount>) {
    val max = months.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().height(140.dp).padding(horizontal = 16.dp),
    ) {
        months.forEach { m ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                Text(if (m.count > 0) m.count.toString() else "", style = MaterialTheme.typography.labelSmall)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((90f * m.count / max).coerceAtLeast(2f).dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(com.metrolist.music.ui.theme.serviceColor(MetadataSource.YANDEX)),
                )
                Text(
                    runCatching { java.time.YearMonth.parse(m.month).month.getDisplayName(TextStyle.NARROW_STANDALONE, Locale.getDefault()) }.getOrDefault(""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

fun monthName(month: java.time.YearMonth): String =
    month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()).replaceFirstChar { it.uppercase() } +
        if (month.year != java.time.Year.now().value) " ${month.year}" else ""

@Composable
fun MonthReviewBody(r: MonthReview.Review) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.month_review_numbers, r.minutes, r.listens, r.newArtists),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        if (r.topArtists.isNotEmpty()) {
            Text(stringResource(R.string.month_review_artists), style = MaterialTheme.typography.titleSmall)
            r.topArtists.take(5).forEachIndexed { i, a -> Text("${i + 1}. ${a.title} · ${a.count}", style = MaterialTheme.typography.bodyMedium) }
        }
        if (r.topTracks.isNotEmpty()) {
            Text(stringResource(R.string.month_review_tracks), style = MaterialTheme.typography.titleSmall)
            r.topTracks.take(5).forEachIndexed { i, t ->
                Text(
                    "${i + 1}. " + listOfNotNull(t.subtitle, t.title).joinToString(" – ") + " · ${t.count}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The month in review, once, when the app is first opened in a new month: the last month's
 * minutes, listens, new artists and tops from the app's own history.
 */
@Composable
fun MonthReviewOffer() {
    val context = LocalContext.current
    val database = LocalDatabase.current
    var review by remember { mutableStateOf<MonthReview.Review?>(null) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(4_000)
        review = withContext(Dispatchers.IO) { runCatching { MonthReview.pending(context, database) }.getOrNull() }
    }
    val r = review ?: return
    val close = {
        MonthReview.markShown(context, r.month)
        review = null
    }
    // As a story in a sheet over nearly the whole screen, like the patch notes.
    com.metrolist.music.ui.screens.MonthReviewStory(r, onDismiss = close)
}
