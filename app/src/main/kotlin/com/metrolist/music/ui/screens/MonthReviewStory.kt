/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.catalog.MonthReview
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale

/**
 * The month in review as a story, in a sheet that slides up over nearly the whole screen (like the
 * patch notes): swipe left and right through pages — the month in numbers, the favourite artist
 * in a circle, the top five, the track of the month, when and how much was listened. Arrows at the
 * sides pulse softly to show it goes on. Everything comes from the listening history in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthReviewStory(review: MonthReview.Review, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pages = buildList<@Composable () -> Unit> {
        add { IntroPage(review) }
        review.topArtists.firstOrNull()?.let { top -> add { TopArtistPage(top) } }
        if (review.topArtists.size > 1) add { RankPage(stringResource(R.string.story_top_artists), review.topArtists, round = true) }
        review.topTracks.firstOrNull()?.let { top -> add { TopTrackPage(top) } }
        if (review.topTracks.size > 1) add { RankPage(stringResource(R.string.story_top_tracks), review.topTracks, round = false) }
        add { HabitsPage(review) }
    }
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.18f), Color.Transparent, accent.copy(alpha = 0.10f)))),
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                // Each page eases in as it comes into view.
                val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val f = kotlin.math.abs(offset).coerceIn(0f, 1f)
                            alpha = 1f - f * 0.6f
                            scaleX = 1f - f * 0.08f
                            scaleY = 1f - f * 0.08f
                        }
                        .padding(horizontal = 32.dp, vertical = 24.dp),
                ) { pages[page]() }
            }
            // Where in the story: dots on top.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
            ) {
                repeat(pages.size) { i ->
                    val w by animateFloatAsState(if (i == pager.currentPage) 22f else 8f, tween(250), label = "dot")
                    Box(
                        Modifier
                            .height(6.dp)
                            .width(w.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (i == pager.currentPage) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)),
                    )
                }
            }
            // Soft pulsing arrows: there is more to the left / right.
            val pulse = rememberInfiniteTransition(label = "arrows")
            val nudge by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "nudge")
            if (pager.currentPage > 0) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 6.dp)
                        .graphicsLayer { translationX = -nudge * 8f }
                        .alpha(0.35f + 0.25f * nudge)
                        .size(22.dp),
                )
            }
            if (pager.currentPage < pages.lastIndex) {
                Icon(
                    painterResource(R.drawable.arrow_forward),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp)
                        .graphicsLayer { translationX = nudge * 8f }
                        .alpha(0.35f + 0.25f * nudge)
                        .size(22.dp),
                )
            } else {
                TextButton(
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                ) { Text(stringResource(R.string.story_close)) }
            }
        }
    }
}

private fun monthTitle(review: MonthReview.Review): String =
    review.month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()).replaceFirstChar { it.uppercase() } +
        if (review.month.year != java.time.Year.now().value) " ${review.month.year}" else ""

@Composable
private fun BigNumber(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 44.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun IntroPage(r: MonthReview.Review) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(stringResource(R.string.story_intro_over), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(monthTitle(r), fontSize = 40.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        BigNumber(r.minutes.toString(), stringResource(R.string.story_minutes))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            BigNumberSmall(r.listens.toString(), stringResource(R.string.story_listens))
            BigNumberSmall(r.distinctTracks.toString(), stringResource(R.string.story_tracks))
            BigNumberSmall(r.distinctArtists.toString(), stringResource(R.string.story_artists))
        }
    }
}

@Composable
private fun BigNumberSmall(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TopArtistPage(top: MonthReview.Item) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.story_top_artist), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AsyncImage(
            model = top.image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Text(top.title, fontSize = 32.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(R.string.story_listens_minutes, top.count, top.minutes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TopTrackPage(top: MonthReview.Item) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.story_top_track), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AsyncImage(
            model = top.image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(0.66f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Text(top.title, fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        top.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text(stringResource(R.string.story_times, top.count), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun RankPage(title: String, items: List<MonthReview.Item>, round: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Black)
        items.forEachIndexed { i, item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", fontSize = 24.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(32.dp))
                AsyncImage(
                    model = item.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(if (round) CircleShape else RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(item.subtitle, stringResource(R.string.story_times, item.count)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun HabitsPage(r: MonthReview.Review) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Text(stringResource(R.string.story_habits), fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        r.busiestDay?.let { day ->
            val dayText = "${day.dayOfMonth} " + day.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
            BigNumber(dayText, stringResource(R.string.story_busiest_day, r.busiestDayMinutes))
        }
        r.topHour?.let { h -> BigNumber("%02d:00".format(h), stringResource(R.string.story_top_hour)) }
        if (r.newArtists > 0) BigNumber(r.newArtists.toString(), stringResource(R.string.story_new_artists))
    }
}
