/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.concerts.Concert
import com.metrolist.music.concerts.ConcertAlerts
import com.metrolist.music.concerts.ConcertRule
import com.metrolist.music.concerts.Concerts
import com.metrolist.music.concerts.Genres
import com.metrolist.music.ui.screens.friends.genreColor
import com.metrolist.music.ui.theme.ThemedSwitch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

// ---- filtering ------------------------------------------------------------------------------------

/** When: a quick range or one picked day. */
sealed class ConcertPeriod {
    data object Today : ConcertPeriod()
    data object Tomorrow : ConcertPeriod()
    data object Week : ConcertPeriod()
    data object Month : ConcertPeriod()
    data object All : ConcertPeriod()
    data class Day(val date: LocalDate) : ConcertPeriod()

    fun contains(date: LocalDate, today: LocalDate): Boolean = when (this) {
        Today -> date == today
        Tomorrow -> date == today.plusDays(1)
        Week -> !date.isBefore(today) && date.isBefore(today.plusDays(7))
        Month -> !date.isBefore(today) && date.isBefore(today.plusDays(31))
        All -> !date.isBefore(today)
        is Day -> date == this.date
    }
}

/** What the filters leave: [maxPrice] null = any price; with a limit, gigs of unknown price drop out. */
data class ConcertFilter(
    val period: ConcertPeriod = ConcertPeriod.Week,
    val maxPrice: Int? = null,
    val freeOnly: Boolean = false,
    /** Genre families. */
    val genres: Set<String> = emptySet(),
    val venues: Set<String> = emptySet(),
    val regions: Set<String> = emptySet(),
    val query: String = "",
) {
    fun apply(all: List<Concert>): List<Concert> {
        val today = LocalDate.now(Concert.ZONE)
        val q = query.trim().lowercase(Locale.ROOT)
        return all.filter { c ->
            period.contains(c.date, today) &&
                (!freeOnly || c.isFree) &&
                (maxPrice == null || (c.price != null && c.price <= maxPrice)) &&
                (genres.isEmpty() || c.families.any { it in genres }) &&
                (venues.isEmpty() || venues.any { it.equals(c.venue, true) }) &&
                (regions.isEmpty() || c.region in regions) &&
                (q.isEmpty() || c.title.lowercase(Locale.ROOT).contains(q) || c.venue.lowercase(Locale.ROOT).contains(q))
        }
    }
}

private val RU = Locale("ru")

@Composable
private fun periodLabel(p: ConcertPeriod): String = when (p) {
    ConcertPeriod.Today -> stringResource(R.string.concerts_today)
    ConcertPeriod.Tomorrow -> stringResource(R.string.concerts_tomorrow)
    ConcertPeriod.Week -> stringResource(R.string.concerts_week)
    ConcertPeriod.Month -> stringResource(R.string.concerts_month)
    ConcertPeriod.All -> stringResource(R.string.concerts_all_dates)
    is ConcertPeriod.Day -> p.date.dayOfMonth.toString() + " " + p.date.month.getDisplayName(TextStyle.SHORT, RU)
}

private fun timeOf(c: Concert): String {
    if (!c.timeKnown) return "—"
    val t = java.time.Instant.ofEpochMilli(c.start).atZone(Concert.ZONE).toLocalTime()
    return "%02d:%02d".format(t.hour, t.minute)
}

@Composable
private fun priceOf(c: Concert): String? = when {
    c.isFree -> stringResource(R.string.concerts_free)
    c.price != null -> "от ${c.price} ₽"
    else -> null
}

private fun monthGenitive(d: LocalDate): String =
    d.month.getDisplayName(TextStyle.FULL_STANDALONE, RU).let { m -> if (m.endsWith("т")) m + "а" else m.dropLast(1) + "я" }

private fun dayTitle(d: LocalDate): String {
    val today = LocalDate.now(Concert.ZONE)
    val dow = d.dayOfWeek.getDisplayName(TextStyle.FULL, RU)
    val date = "${d.dayOfMonth} ${monthGenitive(d)}"
    return when (d) {
        today -> "Сегодня, $date"
        today.plusDays(1) -> "Завтра, $date"
        else -> "${dow.replaceFirstChar { it.titlecase(RU) }}, $date"
    }
}

private fun browse(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun copyAddress(context: Context, c: Concert) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm?.setPrimaryClip(ClipData.newPlainText("address", c.mapQuery))
    Toast.makeText(context, R.string.concerts_address_copied, Toast.LENGTH_SHORT).show()
}

/** The venue on the map; no map app → the address goes to the clipboard instead. */
private fun showOnMap(context: Context, c: Concert) {
    val uri = "geo:0,0?q=${android.net.Uri.encode(c.mapQuery)}".toUri()
    val ok = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!ok) copyAddress(context, c)
}

private fun addToCalendar(context: Context, c: Concert) {
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, c.title)
        .putExtra(CalendarContract.Events.EVENT_LOCATION, c.mapQuery)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, c.start)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, c.start + 3 * 60 * 60 * 1000L)
        .putExtra(CalendarContract.Events.DESCRIPTION, c.url)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun share(context: Context, c: Concert) {
    val text = "${c.title}\n${dayTitle(c.date)}${if (c.timeKnown) ", " + timeOf(c) else ""}\n${c.venue}\n${c.url}"
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ---- home block -----------------------------------------------------------------------------------

/**
 * The poster at the very bottom of the home screen: a week of picks (the saved taste — rock and
 * metal at up to 500 ₽ by default — nearest first) and a button to the whole poster.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConcertsHomeBlock(navController: NavController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by Concerts.state.collectAsState()
    val taste by Concerts.tasteFlow(context).collectAsState()
    LaunchedEffect(Unit) { Concerts.refresh(context) }
    val t = taste ?: Concerts.Taste(Genres.DEFAULT_TASTE, 500)
    val picks = remember(state.concerts, t) { Genres.recommend(state.concerts, t.genres, t.maxPrice) }
    var detail by remember { mutableStateOf<Concert?>(null) }
    detail?.let { ConcertDetailSheet(it, navController) { detail = null } }

    Column(modifier) {
        Text(
            stringResource(R.string.concerts_picks_label, t.maxPrice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        when {
            picks.isNotEmpty() -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(picks, key = { it.id }) { c -> ConcertPoster(c, Modifier.width(220.dp)) { detail = c } }
            }
            state.loading && state.concerts.isEmpty() ->
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> Text(
                stringResource(if (state.error) R.string.concerts_error else R.string.concerts_no_picks),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(
            onClick = { navController.navigate("concerts") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
        ) {
            Icon(painterResource(R.drawable.calendar_month), null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.concerts_open), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ConcertPoster(c: Concert, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = genreColor(c.genres.firstOrNull().orEmpty())
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.7f).background(Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.12f))))) {
            if (c.imageUrl != null) {
                AsyncImage(model = c.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            DateBadge(c.date, Modifier.align(Alignment.TopStart).padding(8.dp))
            priceOf(c)?.let { p ->
                Text(
                    p, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
            Spacer(Modifier.height(2.dp))
            Text("${timeOf(c)} · ${c.venue}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val tags = c.families.ifEmpty { c.genres.toSet() }
            if (tags.isNotEmpty()) {
                Text(tags.take(2).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DateBadge(d: LocalDate, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(d.dayOfMonth.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(d.month.getDisplayName(TextStyle.SHORT, RU).uppercase(RU).trimEnd('.'), color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
    }
}

// ---- details --------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConcertDetailSheet(c: Concert, navController: NavController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val accent = genreColor(c.genres.firstOrNull().orEmpty())
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.9f).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.12f))))) {
                if (c.imageUrl != null) AsyncImage(model = c.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(14.dp))
            Text(c.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "${dayTitle(c.date)} · ${if (c.timeKnown) timeOf(c) else stringResource(R.string.concerts_time_unknown)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(c.venue + if (c.address.isNotBlank()) ", ${c.address}" else "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(c.region, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(priceOf(c) ?: stringResource(R.string.concerts_price_unknown), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (c.genres.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    c.genres.forEach { g -> AssistChip(onClick = {}, label = { Text(g) }) }
                }
            }
            val acts = c.performers
            if (acts.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.concerts_performers), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    acts.take(12).forEach { name ->
                        AssistChip(
                            onClick = {
                                onDismiss()
                                navController.navigate("search/" + java.net.URLEncoder.encode(name, "UTF-8"))
                            },
                            label = { Text(name) },
                            leadingIcon = { Icon(painterResource(R.drawable.music_note), null, Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { browse(context, c.url) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.concerts_open_page))
            }
            c.altUrl?.let { alt ->
                OutlinedButton(onClick = { browse(context, alt) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.concerts_open_announce))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showOnMap(context, c) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.concerts_map), maxLines = 1) }
                OutlinedButton(onClick = { copyAddress(context, c) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.concerts_copy_address), maxLines = 1) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { addToCalendar(context, c) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.concerts_to_calendar), maxLines = 1) }
                OutlinedButton(onClick = { share(context, c) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.concerts_share), maxLines = 1) }
            }
            Text(
                stringResource(R.string.concerts_from, c.sources.joinToString(", ")),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

// ---- screen / section -----------------------------------------------------------------------------

/** The full-screen poster (from the home button, settings and alerts). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConcertsScreen(navController: NavController) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.concerts_title)) },
            navigationIcon = { IconButton(onClick = navController::navigateUp) { Icon(painterResource(R.drawable.arrow_back), null) } },
        )
        ConcertsContent(navController, underTopBar = true)
    }
}

/**
 * Everything of the poster — switch, period, calendar, filters, alerts and the list — so the full
 * screen and the Library section are the same thing. [header] sits on top (the Library's chips).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConcertsContent(
    navController: NavController,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    underTopBar: Boolean = false,
) {
    val context = LocalContext.current
    val state by Concerts.state.collectAsState()
    val enabled by Concerts.enabledFlow(context).collectAsState()
    val rules by ConcertAlerts.rulesFlow(context).collectAsState()
    var filter by remember { mutableStateOf(ConcertFilter()) }
    var month by remember { mutableStateOf(YearMonth.now(Concert.ZONE)) }
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Concert?>(null) }
    LaunchedEffect(enabled) { if (enabled == true) Concerts.refresh(context) }
    detail?.let { ConcertDetailSheet(it, navController) { detail = null } }

    val all = state.concerts
    val genres = remember(all) { Concerts.genresOf(all) }
    val venues = remember(all) { Concerts.venuesOf(all).take(16) }
    val shown = remember(all, filter) { filter.apply(all) }
    val byDay = remember(shown) { shown.groupBy { it.date }.toSortedMap() }
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() + 24.dp
    val top = if (underTopBar) 0.dp else LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateTopPadding()

    LazyColumn(
        contentPadding = PaddingValues(top = top, bottom = bottom),
        modifier = modifier.fillMaxSize(),
    ) {
        if (header != null) item(key = "header") { header() }
        item(key = "switch") {
            Row(
                Modifier.fillMaxWidth().clickable { Concerts.setEnabled(context, enabled != true) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.concerts_enable), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.concerts_enable_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ThemedSwitch(checked = enabled == true, onCheckedChange = { Concerts.setEnabled(context, it) })
            }
        }
        if (enabled != true) return@LazyColumn

        item(key = "period") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ConcertPeriod.Today, ConcertPeriod.Tomorrow, ConcertPeriod.Week, ConcertPeriod.Month, ConcertPeriod.All).forEach { p ->
                    FilterChip(selected = filter.period == p, onClick = { filter = filter.copy(period = p) }, label = { Text(periodLabel(p)) })
                }
                (filter.period as? ConcertPeriod.Day)?.let { d ->
                    FilterChip(selected = true, onClick = { showCalendar = true }, label = { Text(periodLabel(d)) })
                }
                FilterChip(
                    selected = showCalendar,
                    onClick = { showCalendar = !showCalendar },
                    label = { Text(stringResource(R.string.concerts_calendar)) },
                    leadingIcon = { Icon(painterResource(R.drawable.calendar_month), null, Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = searching || filter.query.isNotEmpty(),
                    onClick = { searching = !searching; if (!searching) filter = filter.copy(query = "") },
                    label = { Text(stringResource(R.string.concerts_search)) },
                    leadingIcon = { Icon(painterResource(R.drawable.search), null, Modifier.size(18.dp)) },
                )
            }
        }
        if (searching) item(key = "search") {
            androidx.compose.material3.OutlinedTextField(
                value = filter.query,
                onValueChange = { filter = filter.copy(query = it) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.concerts_search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (showCalendar) item(key = "calendar") {
            MonthCalendar(
                month = month,
                days = remember(all) { all.groupingBy { it.date }.eachCount() },
                selected = (filter.period as? ConcertPeriod.Day)?.date,
                onMonth = { month = it },
                onPick = { filter = filter.copy(period = ConcertPeriod.Day(it)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item(key = "price") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filter.freeOnly,
                    onClick = { filter = filter.copy(freeOnly = !filter.freeOnly, maxPrice = null) },
                    label = { Text(stringResource(R.string.concerts_free)) },
                )
                listOf(300, 500, 1000, 2000).forEach { cap ->
                    FilterChip(
                        selected = filter.maxPrice == cap,
                        onClick = { filter = filter.copy(maxPrice = if (filter.maxPrice == cap) null else cap, freeOnly = false) },
                        label = { Text("до $cap ₽") },
                    )
                }
                listOf(Genres.REGION_SPB, Genres.REGION_OBLAST).forEach { r ->
                    FilterChip(
                        selected = r in filter.regions,
                        onClick = { filter = filter.copy(regions = if (r in filter.regions) filter.regions - r else filter.regions + r) },
                        label = { Text(r) },
                    )
                }
            }
        }
        if (genres.isNotEmpty()) item(key = "genres") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                genres.forEach { g ->
                    FilterChip(
                        selected = g in filter.genres,
                        onClick = { filter = filter.copy(genres = if (g in filter.genres) filter.genres - g else filter.genres + g) },
                        label = { Text(g) },
                    )
                }
            }
        }
        if (venues.isNotEmpty()) item(key = "venues") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                venues.forEach { v ->
                    FilterChip(
                        selected = v in filter.venues,
                        onClick = { filter = filter.copy(venues = if (v in filter.venues) filter.venues - v else filter.venues + v) },
                        label = { Text(v) },
                    )
                }
            }
        }

        item(key = "actions") {
            val rule = ConcertRule(maxPrice = filter.maxPrice, freeOnly = filter.freeOnly, genres = filter.genres, venues = filter.venues)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (rules.orEmpty().none { it.copy(id = "", enabled = true) == rule.copy(id = "") }) {
                                ConcertAlerts.saveRules(context, rules.orEmpty() + rule)
                            }
                            Toast.makeText(context, R.string.concerts_alert_added, Toast.LENGTH_SHORT).show()
                        },
                        enabled = rule.hasCriteria,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(painterResource(R.drawable.notification), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.concerts_alert_short), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            Concerts.setTaste(
                                context,
                                Concerts.Taste(
                                    filter.genres.ifEmpty { Genres.DEFAULT_TASTE },
                                    if (filter.freeOnly) 0 else filter.maxPrice ?: 500,
                                ),
                            )
                            Toast.makeText(context, R.string.concerts_taste_saved, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.concerts_taste_save), maxLines = 1) }
                }
                if (!rule.hasCriteria) {
                    Text(
                        stringResource(R.string.concerts_alert_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        val ruleList = rules.orEmpty()
        if (ruleList.isNotEmpty()) {
            item(key = "alert_title") {
                Text(stringResource(R.string.concerts_alerts), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp))
            }
            items(ruleList, key = { "rule_" + it.id }) { r ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ruleText(r), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    ThemedSwitch(checked = r.enabled, onCheckedChange = { on -> ConcertAlerts.saveRules(context, ruleList.map { if (it.id == r.id) it.copy(enabled = on) else it }) })
                    TextButton(onClick = { ConcertAlerts.saveRules(context, ruleList.filter { it.id != r.id }) }) { Text("✕") }
                }
            }
            item(key = "alert_note") {
                Text(
                    stringResource(R.string.concerts_alert_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        item(key = "count") {
            Text(
                stringResource(R.string.concerts_found, shown.size),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp),
            )
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                if (state.loading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else Text(
                    stringResource(if (state.error) R.string.concerts_error else R.string.concerts_empty),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        byDay.forEach { (day, list) ->
            item(key = "day_$day") {
                Text(dayTitle(day), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
            }
            items(list, key = { it.id }) { c -> ConcertRow(c) { detail = c } }
        }
        if (state.updatedAt > 0) item(key = "source") {
            Text(
                stringResource(R.string.concerts_sources), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun ruleText(r: ConcertRule): String = buildList {
    if (r.freeOnly) add(stringResource(R.string.concerts_free))
    r.maxPrice?.let { add("до $it ₽") }
    if (r.genres.isNotEmpty()) add(r.genres.joinToString(", "))
    if (r.venues.isNotEmpty()) add(r.venues.joinToString(", "))
}.joinToString(" · ")

@Composable
private fun ConcertRow(c: Concert, onClick: () -> Unit) {
    val accent = genreColor(c.genres.firstOrNull().orEmpty())
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.12f))))) {
            if (c.imageUrl != null) AsyncImage(model = c.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${timeOf(c)} · ${c.venue}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val tags = c.families.ifEmpty { c.genres.toSet() }
            if (tags.isNotEmpty()) Text(tags.take(3).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        priceOf(c)?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun MonthCalendar(
    month: YearMonth,
    days: Map<LocalDate, Int>,
    selected: LocalDate?,
    onMonth: (YearMonth) -> Unit,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now(Concert.ZONE)
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onMonth(month.minusMonths(1)) }, enabled = month > YearMonth.from(today)) { Text("‹") }
            Text(
                month.month.getDisplayName(TextStyle.FULL_STANDALONE, RU).replaceFirstChar { it.titlecase(RU) } + " " + month.year,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Medium,
            )
            TextButton(onClick = { onMonth(month.plusMonths(1)) }) { Text("›") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1
        val cells = lead + month.lengthOfMonth()
        for (row in 0 until (cells + 6) / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val n = row * 7 + col - lead + 1
                    if (n < 1 || n > month.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f).height(44.dp))
                        continue
                    }
                    val date = month.atDay(n)
                    val count = days[date] ?: 0
                    val isSel = date == selected
                    Column(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable(enabled = !date.isBefore(today)) { onPick(date) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            n.toString(), fontSize = 14.sp,
                            fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                isSel -> MaterialTheme.colorScheme.onPrimary
                                date.isBefore(today) -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (count > 0 && !date.isBefore(today)) {
                            Box(Modifier.padding(top = 1.dp).size(5.dp).clip(CircleShape).background(if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
        }
    }
}
