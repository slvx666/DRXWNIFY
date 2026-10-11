/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.reviews

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.extensions.matchesNormalizedQuery
import com.metrolist.music.extensions.normalizeForSearch
import com.metrolist.music.reviews.NightReviews
import com.metrolist.music.ui.component.LibrarySearchEmptyPlaceholder
import com.metrolist.music.ui.component.LibrarySearchHeader
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch
import java.net.URLEncoder

private val CardsKey = booleanPreferencesKey("reviews_cards")
private val SortKey = stringPreferencesKey("reviews_sort")
private val DescKey = booleanPreferencesKey("reviews_desc")

private enum class ReviewSort { POSTED, RATING, ALBUM, ARTIST, YEAR, LISTENERS }

/** drxwnslvt's reviews on their own screen (opened from his profile in Friends). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewsScreen(navController: NavController) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.reviews_title)) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) { Icon(painterResource(R.drawable.arrow_back), null) }
            },
        )
        ReviewsContent(navController)
    }
}

/**
 * The reviewed albums: one per row (or one big centred cover per row), newest post first by
 * default, sortable, filterable by rating, genre, year, country and label, searchable. Opening one
 * looks the album up in the app's catalog. [header] sits on top (the library's chip row).
 */
@Composable
fun ReviewsContent(navController: NavController, header: (@Composable () -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by NightReviews.state.collectAsState()
    LaunchedEffect(Unit) { NightReviews.refresh(context) }

    var cards by rememberPreference(CardsKey, false)
    var sortName by rememberPreference(SortKey, ReviewSort.POSTED.name)
    var descending by rememberPreference(DescKey, true)
    val sort = runCatching { ReviewSort.valueOf(sortName) }.getOrDefault(ReviewSort.POSTED)

    var minRating by rememberSaveable { mutableStateOf<Double?>(null) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    var label by rememberSaveable { mutableStateOf<String?>(null) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var opening by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    val all = state.reviews
    val normalized = remember(query) { query.normalizeForSearch() }
    val shown = remember(all, sort, descending, minRating, genre, year, country, label, normalized) {
        val filtered = all.filter { r ->
            (minRating == null || r.rating >= minRating!!) &&
                (genre == null || genre in r.genres) &&
                (year == null || r.year == year) &&
                (country == null || r.country == country) &&
                (label == null || r.label == label) &&
                (normalized.isBlank() || matchesNormalizedQuery(normalized, r.album, r.artist, r.label, r.genres.joinToString(" ")))
        }
        val comparator: Comparator<NightReviews.Review> = when (sort) {
            ReviewSort.POSTED -> compareBy<NightReviews.Review> { it.postDate }.thenBy { it.messageId }
            ReviewSort.RATING -> compareBy { it.rating }
            ReviewSort.ALBUM -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.album }
            ReviewSort.ARTIST -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist }
            ReviewSort.YEAR -> compareBy { it.year ?: 0 }
            ReviewSort.LISTENERS -> compareBy { it.popularity }
        }
        filtered.sortedWith(if (descending) comparator.reversed() else comparator)
    }

    fun open(review: NightReviews.Review) {
        if (opening != null) return
        opening = review.id
        scope.launch {
            val albumId = NightReviews.findAlbum(context, review)
            opening = null
            if (albumId != null) {
                navController.navigate("spotify_album/$albumId")
            } else {
                Toast.makeText(context, R.string.reviews_album_not_found, Toast.LENGTH_SHORT).show()
                navController.navigate("search/" + URLEncoder.encode("${review.artist} ${review.album}", "UTF-8"))
            }
        }
    }

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        modifier = Modifier.fillMaxSize(),
    ) {
        header?.let { item(key = "header") { it() } }
        item(key = "controls") {
            LibrarySearchHeader(
                isSearchActive = searchActive,
                searchQuery = query,
                onSearchQueryChange = { query = it },
                onBack = {
                    searchActive = false
                    query = ""
                },
                keyboardController = keyboard,
                modifier = Modifier.padding(start = 12.dp),
            ) {
                SortControl(sort, descending, onSort = { sortName = it.name }, onFlip = { descending = !descending })
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { searchActive = true }, modifier = Modifier.padding(start = 8.dp).size(40.dp)) {
                    Icon(painterResource(R.drawable.search), contentDescription = stringResource(R.string.search))
                }
                IconButton(onClick = { cards = !cards }, modifier = Modifier.padding(end = 8.dp).size(40.dp)) {
                    Icon(
                        painterResource(if (cards) R.drawable.list else R.drawable.grid_view),
                        contentDescription = stringResource(if (cards) R.string.switch_to_list_view else R.string.switch_to_grid_view),
                    )
                }
            }
        }
        item(key = "filters") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                FilterChipMenu(
                    title = stringResource(R.string.reviews_filter_rating),
                    value = minRating?.let { "${formatRating(it)}+" },
                    options = listOf(9.5, 9.0, 8.5, 8.0).map { "${formatRating(it)}+" to it },
                    onPick = { minRating = it },
                )
                FilterChipMenu(
                    title = stringResource(R.string.reviews_filter_genre),
                    value = genre,
                    options = all.flatMap { it.genres }.groupingBy { it }.eachCount().entries
                        .sortedByDescending { it.value }.map { "${it.key} · ${it.value}" to it.key },
                    onPick = { genre = it },
                )
                FilterChipMenu(
                    title = stringResource(R.string.reviews_filter_year),
                    value = year?.toString(),
                    options = all.mapNotNull { it.year }.distinct().sortedDescending().map { it.toString() to it },
                    onPick = { year = it },
                )
                FilterChipMenu(
                    title = stringResource(R.string.reviews_filter_country),
                    value = country,
                    options = all.map { it.country }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries
                        .sortedByDescending { it.value }.map { "${it.key} · ${it.value}" to it.key },
                    onPick = { country = it },
                )
                FilterChipMenu(
                    title = stringResource(R.string.reviews_filter_label),
                    value = label,
                    options = all.map { it.label }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries
                        .sortedByDescending { it.value }.map { "${it.key} · ${it.value}" to it.key },
                    onPick = { label = it },
                )
            }
        }
        item(key = "summary") {
            val newCount = state.newIds.count { id -> all.any { it.id == id } }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(
                    text = stringResource(R.string.reviews_count, shown.size, all.size) +
                        (if (newCount > 0) " · " + stringResource(R.string.reviews_new, newCount) else ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.loading) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            }
        }
        if (all.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    if (state.loading) CircularProgressIndicator()
                    else Text(stringResource(if (state.error) R.string.reviews_error else R.string.reviews_empty), textAlign = TextAlign.Center)
                }
            }
        } else if (shown.isEmpty()) {
            item(key = "nothing") {
                LibrarySearchEmptyPlaceholder(text = stringResource(R.string.no_results_found), modifier = Modifier.fillMaxWidth().padding(32.dp))
            }
        }
        items(shown, key = { it.id }) { review ->
            val isNew = review.id in state.newIds
            val busy = opening == review.id
            Box(Modifier.animateItem()) {
                if (cards) ReviewCard(review, isNew, busy) { open(review) } else ReviewRow(review, isNew, busy) { open(review) }
            }
        }
    }
}

@Composable
private fun SortControl(sort: ReviewSort, descending: Boolean, onSort: (ReviewSort) -> Unit, onFlip: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val options = listOf(
        ReviewSort.POSTED to R.string.reviews_sort_posted,
        ReviewSort.RATING to R.string.reviews_sort_rating,
        ReviewSort.ALBUM to R.string.reviews_sort_album,
        ReviewSort.ARTIST to R.string.reviews_sort_artist,
        ReviewSort.YEAR to R.string.reviews_sort_year,
        ReviewSort.LISTENERS to R.string.reviews_sort_listeners,
    )
    val rotation by androidx.compose.animation.core.animateFloatAsState(if (descending) 0f else 180f, label = "reviewSortArrow")
    Box {
        Text(
            text = stringResource(options.first { it.first == sort }.second),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { menu = true }.padding(vertical = 8.dp, horizontal = 4.dp),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            options.forEach { (s, res) ->
                DropdownMenuItem(
                    text = { Text(stringResource(res), color = if (s == sort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    onClick = { onSort(s); menu = false },
                )
            }
        }
    }
    Text("·", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    IconButton(onClick = onFlip, modifier = Modifier.size(32.dp)) {
        Icon(
            painterResource(R.drawable.arrow_downward),
            contentDescription = null,
            modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation },
        )
    }
}

/** A filter as a chip: its value when set (tap clears it), else its name with the choices. */
@Composable
private fun <T> FilterChipMenu(title: String, value: String?, options: List<Pair<String, T>>, onPick: (T?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val selected = value != null
    Box {
        Surface(
            shape = RoundedCornerShape(50),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { if (selected) onPick(null) else menu = true },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) {
                Text(
                    text = value ?: title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    painterResource(if (selected) R.drawable.close else R.drawable.arrow_downward),
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            options.forEach { (text, option) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onPick(option); menu = false })
            }
        }
    }
}

private fun formatPostDate(date: String): String = runCatching {
    java.time.LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.getDefault()))
}.getOrDefault(date)

private fun formatRating(r: Double): String =
    if (r == Math.floor(r)) r.toInt().toString() else java.math.BigDecimal(r).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/** 10: gold-green; 6 and lower: red, through yellow. */
private fun ratingColor(r: Double): Color {
    val t = ((r - 6.0) / 4.0).coerceIn(0.0, 1.0).toFloat()
    return Color.hsv(hue = 125f * t, saturation = 0.75f, value = 0.9f)
}

@Composable
private fun RatingPill(rating: Double, large: Boolean = false) {
    val color = ratingColor(rating)
    Text(
        text = formatRating(rating),
        style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = if (large) 12.dp else 8.dp, vertical = if (large) 4.dp else 3.dp),
    )
}

@Composable
private fun NewBadge() {
    Text(
        text = stringResource(R.string.reviews_new_badge),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Color.Black,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFF0BE7E)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun detailLine(review: NightReviews.Review): String =
    listOfNotNull(review.year?.toString(), review.genres.take(2).joinToString(", ").takeIf { it.isNotBlank() }).joinToString(" · ")

@Composable
private fun Cover(review: NightReviews.Review, modifier: Modifier, busy: Boolean) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = review.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (busy) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** One album per row: cover, title, artist, year · genres, the rating at the edge. */
@Composable
private fun ReviewRow(review: NightReviews.Review, isNew: Boolean, busy: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Cover(review, Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)), busy)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    review.album,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isNew) {
                    Spacer(Modifier.width(6.dp))
                    NewBadge()
                }
            }
            Text(review.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detailLine(review).takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        RatingPill(review.rating)
    }
}

/** A big centred cover with the title, artist and rating under it — the list read like a gallery. */
@Composable
private fun ReviewCard(review: NightReviews.Review, isNew: Boolean, busy: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 14.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Cover(
            review,
            Modifier
                .fillMaxWidth(0.84f)
                .aspectRatio(1f)
                .shadow(18.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp)),
            busy,
        )
        Spacer(Modifier.height(16.dp))
        if (isNew) {
            NewBadge()
            Spacer(Modifier.height(6.dp))
        }
        Text(
            review.album,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            review.artist,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        RatingPill(review.rating, large = true)
        detailLine(review).takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(
            stringResource(R.string.reviews_posted, formatPostDate(review.postDate)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
