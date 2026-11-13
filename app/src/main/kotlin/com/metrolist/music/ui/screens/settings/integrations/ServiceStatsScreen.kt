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
 * time), Yandex Music's likes by month and most liked artists — each also as a full-screen
 * wrap-up story. Only what the service gives (the app's own listening is in Statistics). Not
 * shared with friends.
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
    var story by remember { mutableStateOf<MonthReview.Review?>(null) }
    val context = LocalContext.current

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
                val period = stringResource(
                    when (range) {
                        ServiceStats.Range.SHORT -> R.string.story_period_short
                        ServiceStats.Range.MEDIUM -> R.string.story_period_medium
                        ServiceStats.Range.LONG -> R.string.story_period_long
                    },
                )
                if (tops.tracks.isNotEmpty() || tops.artists.isNotEmpty()) {
                    WatchButton { story = spotifyStory(tops, period, database) }
                }
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
                val period = stringResource(R.string.story_period_likes)
                val name = stringResource(serviceNameRes(MetadataSource.YANDEX))
                if (y.artists.isNotEmpty()) WatchButton { story = yandexStory(y, name, period, database) }
                Text(
                    stringResource(R.string.stats_yandex_total, y.total),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                PreferenceGroupTitle(title = stringResource(R.string.stats_likes_by_month))
                MonthBars(y.byMonth)
                PreferenceGroupTitle(title = stringResource(R.string.stats_liked_artists))
                y.artists.forEachIndexed { i, a -> RankRow(i + 1, a.name, a.count.toString(), a.image, round = true) {} }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    story?.let { r -> com.metrolist.music.ui.screens.MonthReviewStory(r) { story = null } }
    TopAppBar(
        title = { Text(stringResource(R.string.service_stats_title) + " · " + stringResource(serviceNameRes(service))) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = {}) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}

/** Opens the service's wrap-up as a full-screen story. */
@Composable
private fun WatchButton(onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = com.metrolist.music.ui.theme.tuiButtonShape(),
    ) {
        Icon(painterResource(R.drawable.play), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.story_watch))
    }
}

private fun biggest(images: List<com.metrolist.spotify.models.SpotifyImage>?): String? =
    images?.maxByOrNull { it.width ?: it.height ?: 0 }?.url ?: images?.firstOrNull()?.url

/**
 * Spotify's tops as a wrap-up story: 21 tracks and 11 artists, no counts (Spotify doesn't tell how
 * many times). A track plays from the app's database when it is there, else found the way any
 * Spotify track is.
 */
private fun spotifyStory(
    tops: ServiceStats.SpotifyTops,
    period: String,
    database: com.metrolist.music.db.MusicDatabase,
): MonthReview.Review {
    val tracks = tops.tracks.filter { it.id.isNotBlank() }.take(21)
    val items = tracks.map { t ->
        MonthReview.Item(title = t.name, subtitle = t.artists.joinToString { it.name }, count = 0, image = biggest(t.album?.images), songId = t.id)
    }
    val artists = tops.artists.take(11).map { a ->
        // The artist's page plays its best placed track, else the top track.
        val own = tops.tracks.firstOrNull { t -> t.artists.any { it.id == a.id || it.name.equals(a.name, ignoreCase = true) } }
        MonthReview.Item(title = a.name, subtitle = null, count = 0, image = biggest(a.images), songId = own?.id ?: tracks.firstOrNull()?.id)
    }
    val byId = tops.tracks.associateBy { it.id }
    val mapper = com.metrolist.music.playback.SpotifyYouTubeMapper(database)
    return serviceReview("Spotify", period, items, artists) { id -> byId[id]?.let { mapper.resolveToMediaItem(it) } }
}

/** Yandex Music's likes as a wrap-up story: how many, the month with the most, the most liked artists. */
private fun yandexStory(
    y: ServiceStats.YandexStats,
    name: String,
    period: String,
    database: com.metrolist.music.db.MusicDatabase,
): MonthReview.Review {
    val artists = y.artists.take(11).map { a ->
        MonthReview.Item(title = a.name, subtitle = null, count = a.count, image = a.image ?: biggest(a.track?.album?.images), songId = a.track?.id)
    }
    val byId = y.artists.mapNotNull { it.track }.associateBy { it.id }
    val best = y.byMonth.maxByOrNull { it.count }?.takeIf { it.count > 0 }
    val mapper = com.metrolist.music.playback.SpotifyYouTubeMapper(database)
    return serviceReview(name, period, emptyList(), artists) { id -> byId[id]?.let { mapper.resolveToMediaItem(it) } }.copy(
        likesTotal = y.total,
        likesBestMonth = best?.let { runCatching { java.time.YearMonth.parse(it.month) }.getOrNull() },
        likesBestCount = best?.count ?: 0,
    )
}

private fun serviceReview(
    service: String,
    period: String,
    tracks: List<MonthReview.Item>,
    artists: List<MonthReview.Item>,
    media: suspend (String) -> androidx.media3.common.MediaItem?,
) = MonthReview.Review(
    month = java.time.YearMonth.now(),
    minutes = 0,
    listens = 0,
    topTracks = tracks,
    topArtists = artists,
    newArtists = 0,
    distinctArtists = 0,
    distinctTracks = 0,
    busiestDay = null,
    busiestDayMinutes = 0,
    topHour = null,
    service = service,
    period = period,
    media = media,
)

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
