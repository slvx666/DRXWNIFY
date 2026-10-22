/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.friends

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.friends.FriendsHub
import com.metrolist.music.portrait.Genres
import com.metrolist.music.portrait.Portrait
import com.metrolist.music.portrait.ProfilePortrait
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * A genre's own hue, anywhere on the colour wheel (the golden ratio spreads close names apart).
 * Ten fixed hues gave e.g. Breakcore and Metalcore the same colour.
 */
private fun hueOf(genre: String): Float {
    val h = genre.lowercase().hashCode().toLong() and 0xffffffffL
    return ((h * 0.6180339887) % 1.0 * 360.0).toFloat()
}

private fun hueDistance(a: Float, b: Float): Float {
    val d = kotlin.math.abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

/** Hues for genres shown side by side: each its own, pushed around the wheel when two would look alike. */
fun huesFor(genres: List<String>): Map<String, Float> {
    val out = LinkedHashMap<String, Float>()
    genres.filter { it.isNotBlank() }.distinct().forEach { g ->
        var hue = hueOf(g)
        var tries = 0
        while (out.values.any { hueDistance(it, hue) < 38f } && tries < 9) {
            hue = (hue + 41f) % 360f
            tries++
        }
        out[g] = hue
    }
    return out
}

@Composable
private fun isDark() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** The strong colour of [genre] (bars, dots). */
@Composable
fun genreColor(genre: String, hue: Float? = null): Color =
    if (genre.isBlank()) MaterialTheme.colorScheme.outlineVariant
    else Color.hsv(hue ?: hueOf(genre), 0.6f, if (isDark()) 0.85f else 0.72f)

/** What a tap on a genre tag does (opens the genre's playlist); set once around the app's screens. */
val LocalGenreClick = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Opens the "getting to know the genre" playlist of [genre]. */
fun androidx.navigation.NavController.openGenre(genre: String) = navigate("genre/" + android.net.Uri.encode(genre))

/** A genre tag, like a Discord role: tinted background, text of the same hue. A tap opens the genre. */
@Composable
fun GenreTag(genre: String, hue: Float = hueOf(genre)) {
    val dark = isDark()
    val onClick = LocalGenreClick.current
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.hsv(hue, if (dark) 0.45f else 0.18f, if (dark) 0.32f else 0.97f),
        modifier = if (onClick != null) Modifier.clip(RoundedCornerShape(50)).clickable { onClick(genre) } else Modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Color.hsv(hue, 0.6f, if (dark) 0.9f else 0.65f)))
            Spacer(Modifier.width(6.dp))
            Text(
                genre,
                style = MaterialTheme.typography.labelMedium,
                color = Color.hsv(hue, if (dark) 0.25f else 0.75f, if (dark) 0.95f else 0.38f),
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenreTags(tags: List<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        val hues = huesFor(tags)
        tags.forEach { GenreTag(it, hues[it] ?: hueOf(it)) }
    }
}

/** An artist's genre tags (artist page): looked up on first opening, then kept for a month. */
@Composable
fun ArtistGenreTags(artistName: String?, modifier: Modifier = Modifier) {
    if (artistName.isNullOrBlank()) return
    val version by Genres.version.collectAsState()
    LaunchedEffect(artistName) { runCatching { Genres.ensure(listOf(artistName)) } }
    val tags = remember(artistName, version) { Genres.cached(artistName) }
    GenreTags(tags, modifier)
}

/** An album's genre tags (album page): its own Last.fm tags, else the artist's. */
@Composable
fun AlbumGenreTags(artistName: String?, albumName: String?, modifier: Modifier = Modifier) {
    if (artistName.isNullOrBlank() || albumName.isNullOrBlank()) return
    var tags by remember(artistName, albumName) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(artistName, albumName) {
        tags = runCatching { Genres.ofAlbum(artistName, albumName) }.getOrDefault(emptyList()).take(4)
    }
    GenreTags(tags, modifier)
}

private fun hoursText(minutes: Int): Pair<Boolean, String> =
    if (minutes >= 60) true to "%.1f".format(Locale.getDefault(), minutes / 60.0).removeSuffix(",0").removeSuffix(".0")
    else false to minutes.toString()

@Composable
private fun durationLabel(minutes: Int): String {
    val (inHours, value) = hoursText(minutes)
    return if (inHours) stringResource(R.string.portrait_hours_value, value) else stringResource(R.string.portrait_minutes_value, minutes)
}

private fun monthTitle(key: String, full: Boolean): String = runCatching {
    val ym = YearMonth.parse(key)
    val name = ym.month.getDisplayName(if (full) TextStyle.FULL_STANDALONE else TextStyle.SHORT_STANDALONE, Locale.getDefault())
        .replaceFirstChar { it.uppercase() }
    if (full) "$name ${ym.year}" else name
}.getOrDefault(key)

/**
 * The portrait block of a profile: period chips and the calendar, numbers, top artists, genres and
 * favourite tracks. [onPlay] plays a list of tracks from one of them, [onMenu] opens a track's menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PortraitSection(
    portrait: ProfilePortrait,
    onPlay: (List<FriendsHub.TrackRef>, Int) -> Unit,
    onMenu: (FriendsHub.TrackRef) -> Unit,
) {
    var period by rememberSaveable { mutableStateOf(if (portrait.month != null) 1 else if (portrait.week != null) 0 else 2) }
    var month by rememberSaveable { mutableStateOf<String?>(null) }
    var showCalendar by remember { mutableStateOf(false) }
    val shown: Portrait? = month?.let { portrait.months[it] } ?: when (period) {
        0 -> portrait.week
        1 -> portrait.month
        else -> portrait.year
    }

    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.portrait_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { showCalendar = true }) {
                    Icon(painterResource(R.drawable.calendar_month), stringResource(R.string.portrait_calendar))
                }
            }
            val selectedMonth = month
            if (selectedMonth != null) {
                InputChip(
                    selected = true,
                    onClick = { month = null },
                    label = { Text(monthTitle(selectedMonth, full = true)) },
                    trailingIcon = { Icon(painterResource(R.drawable.close), null, modifier = Modifier.size(16.dp)) },
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.portrait_week, R.string.portrait_month, R.string.portrait_year).forEachIndexed { i, label ->
                        FilterChip(selected = period == i, onClick = { period = i }, label = { Text(stringResource(label)) })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (shown == null) {
                Text(
                    stringResource(R.string.portrait_not_enough),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                PortraitBody(shown, onPlay, onMenu)
            }
        }
    }

    if (showCalendar) {
        CalendarDialog(
            portrait = portrait,
            selected = month,
            onPick = { picked -> month = picked; showCalendar = false },
            onDismiss = { showCalendar = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun PortraitBody(
    p: Portrait,
    onPlay: (List<FriendsHub.TrackRef>, Int) -> Unit,
    onMenu: (FriendsHub.TrackRef) -> Unit,
) {
    if (p.byLikes) {
        Text(
            stringResource(R.string.portrait_by_likes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!p.byLikes) {
            StatTile(stringResource(R.string.portrait_listened), durationLabel(p.minutes), Modifier.weight(1f))
            StatTile(stringResource(R.string.portrait_tracks), p.tracks.toString(), Modifier.weight(1f))
        }
        StatTile(stringResource(R.string.portrait_new_likes), p.newLikes.toString(), Modifier.weight(1f))
    }

    if (p.artists.isNotEmpty()) {
        SubTitle(stringResource(if (p.byLikes) R.string.portrait_top_artists_likes else R.string.portrait_top_artists))
        val maxMinutes = p.artists.maxOf { it.minutes }.coerceAtLeast(1)
        p.artists.forEachIndexed { i, a ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(18.dp))
                AsyncImage(
                    a.cover, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    if (!p.byLikes) {
                        Spacer(Modifier.height(3.dp))
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                            Box(
                                Modifier
                                    .fillMaxWidth(a.minutes.toFloat() / maxMinutes)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                val parts = listOfNotNull(
                    a.minutes.takeIf { it > 0 && !p.byLikes }?.let { durationLabel(it) },
                    a.likes.takeIf { it > 0 }?.let { "♥ $it" },
                )
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (p.genres.isNotEmpty()) {
        SubTitle(stringResource(R.string.portrait_genres))
        GenreBar(p.genres, Modifier.fillMaxWidth().height(10.dp))
        Spacer(Modifier.height(6.dp))
        val legendHues = huesFor(p.genres.map { it.first })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            p.genres.forEach { (g, pct) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(genreColor(g, legendHues[g])))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${g.ifBlank { stringResource(R.string.portrait_other) }} $pct%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (p.topTracks.isNotEmpty()) {
        SubTitle(stringResource(if (p.byLikes) R.string.portrait_new_liked_tracks else R.string.portrait_top_tracks))
        val refs = p.topTracks.map { it.track }
        p.topTracks.forEachIndexed { i, t ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(onClick = { onPlay(refs, i) }, onLongClick = { onMenu(t.track) })
                    .padding(vertical = 4.dp),
            ) {
                AsyncImage(
                    t.track.cover, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(
                            t.track.artist,
                            t.plays.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.portrait_plays, it, it) },
                        ).joinToString(" · "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (t.liked) {
                    Icon(painterResource(R.drawable.favorite), null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                } else {
                    Text(stringResource(R.string.portrait_not_liked), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.portrait_footer),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
    )
}

@Composable
private fun SubTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun GenreBar(genres: List<Pair<String, Int>>, modifier: Modifier = Modifier) {
    val hues = huesFor(genres.map { it.first })
    Row(modifier.clip(RoundedCornerShape(5.dp))) {
        genres.filter { it.second > 0 }.forEach { (g, pct) ->
            Box(Modifier.weight(pct.toFloat()).fillMaxHeight().background(genreColor(g, hues[g])))
        }
    }
}

/**
 * The year's months, each with its genre bar and main genre; picking one shows that month in the
 * portrait. Months without enough listening but with likes say so; the future ones are empty.
 */
@Composable
private fun CalendarDialog(
    portrait: ProfilePortrait,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = YearMonth.now()
    val years = portrait.months.keys.mapNotNull { runCatching { YearMonth.parse(it).year }.getOrNull() }.plus(current.year).distinct().sorted()
    var year by rememberSaveable { mutableStateOf(selected?.let { runCatching { YearMonth.parse(it).year }.getOrNull() } ?: current.year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { year-- }, enabled = year > (years.firstOrNull() ?: year)) {
                    Icon(painterResource(R.drawable.arrow_back), null)
                }
                Text(year.toString(), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { year++ }, enabled = year < current.year) {
                    Icon(painterResource(R.drawable.arrow_forward), null)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..12).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { m ->
                            val ym = YearMonth.of(year, m)
                            val key = ym.toString()
                            val p = portrait.months[key]
                            val future = ym.isAfter(current)
                            val isCurrent = ym == current
                            val shape = RoundedCornerShape(10.dp)
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(shape)
                                    .then(
                                        when {
                                            key == selected || isCurrent -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                                            p == null -> Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                                            else -> Modifier
                                        },
                                    )
                                    .background(if (p != null) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                                    .clickable(enabled = p != null) { onPick(key) }
                                    .padding(7.dp),
                            ) {
                                Text(
                                    monthTitle(key, full = false),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                                if (p != null && p.genres.isNotEmpty()) {
                                    GenreBar(p.genres, Modifier.fillMaxWidth().height(6.dp))
                                } else {
                                    Box(Modifier.fillMaxWidth().height(6.dp))
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    when {
                                        future || p == null -> " "
                                        p.byLikes -> stringResource(R.string.portrait_calendar_by_likes)
                                        else -> p.genres.firstOrNull { it.first.isNotBlank() }?.first ?: " "
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.portrait_calendar_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(android.R.string.cancel)) } },
    )
}
