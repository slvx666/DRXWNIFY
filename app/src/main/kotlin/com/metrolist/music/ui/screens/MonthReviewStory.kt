/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.catalog.MonthReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.TextStyle
import java.util.Locale

/** One page of the story: how long it stays, what it plays, what it shows. */
private class StoryPage(
    val key: String,
    val durationMs: Long,
    val songId: String? = null,
    /** When the page's music must be at full volume (from the page's start): when its track or artist shows. */
    val fullVolumeAtMs: Long = 2_800,
    /** A long list: laid out from the top (centred it ran off the bottom). */
    val top: Boolean = false,
    val content: @Composable () -> Unit,
)

/**
 * The month in review as a story, in a sheet that slides up over nearly the whole screen:
 *  - pages play by themselves (a few seconds each), bars on top show where it is; a tap on the
 *    right/left goes on/back, holding pauses, a swipe flips; the button pauses too;
 *  - a page starts with a short line, then what it is about comes slowly, a bit later (the top
 *    five from fifth place to first, each a little later than the one before);
 *  - a page about one track or artist plays a piece of that track (its own player, the queue is
 *    left alone); the thin slider on top is its volume;
 *  - the last page is a card of the month to share or save as a picture.
 * Everything comes from the listening history in the app; genres from the artists' tags.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthReviewStory(review: MonthReview.Review, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val audio = remember { StoryAudio(database) { playerConnection?.service?.createPreviewPlayer() } }
    // What was playing pauses while the story plays its own music, and goes on afterwards.
    DisposableEffect(Unit) {
        val wasPlaying = playerConnection?.player?.isPlaying == true
        if (wasPlaying) playerConnection?.player?.pause()
        onDispose {
            audio.release()
            if (wasPlaying) playerConnection?.player?.play()
        }
    }

    // "Watch again" starts a new round: the genre page picks another of its songs.
    var round by remember { mutableIntStateOf(0) }
    val pages = remember(review, round) { storyPages(review, round) }
    var index by remember { mutableIntStateOf(0) }
    var direction by remember { mutableIntStateOf(1) }
    var paused by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(audio.volume) }
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun go(to: Int) {
        val target = to.coerceIn(0, pages.lastIndex)
        if (target == index) return
        direction = if (target > index) 1 else -1
        index = target
    }

    // The page's time runs while it isn't paused; at its end the next page comes.
    // Which page [progress] belongs to: a new page's bar is empty from its very first frame (it
    // showed the old page's full bar for one frame before going back to the start).
    var progressFor by remember { mutableIntStateOf(0) }
    LaunchedEffect(index) {
        progress.snapTo(0f)
        progressFor = index
    }
    LaunchedEffect(index, paused, holding) {
        val page = pages[index]
        if (paused || holding || page.durationMs <= 0) return@LaunchedEffect
        val left = ((1f - progress.value) * page.durationMs).toInt()
        progress.animateTo(1f, tween(left, easing = LinearEasing))
        if (index < pages.lastIndex) go(index + 1)
    }
    LaunchedEffect(index) { audio.play(pages[index].songId, System.currentTimeMillis() + pages[index].fullVolumeAtMs) }
    // Pausing the story holds the pages, not the music: it plays on.

    val accent = MaterialTheme.colorScheme.primary
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent, accent.copy(alpha = 0.08f)))),
        ) {
            // The page, with the gestures: tap right / left, hold to pause, swipe to flip.
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .pointerInput(pages.size) {
                        // Holding pauses; letting go only resumes. Pages flip by swiping.
                        detectTapGestures(
                            onPress = {
                                holding = true
                                tryAwaitRelease()
                                holding = false
                            },
                        )
                    }
                    .pointerInput(pages.size) {
                        var dragged = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragged = 0f },
                            onHorizontalDrag = { _, amount -> dragged += amount },
                            onDragEnd = {
                                if (dragged < -80f) go(index + 1) else if (dragged > 80f) go(index - 1)
                            },
                        )
                    },
            ) {
                AnimatedContent(
                    targetState = index,
                    // A slow crossfade with a light zoom and a short drift the way the story goes.
                    transitionSpec = {
                        val d = direction
                        (
                            fadeIn(tween(700, delayMillis = 120, easing = Settle)) +
                                scaleIn(tween(800, easing = Settle), initialScale = 0.96f) +
                                slideInHorizontally(tween(800, easing = Settle)) { it / 14 * d }
                            ) togetherWith (
                            fadeOut(tween(380)) +
                                androidx.compose.animation.scaleOut(tween(420), targetScale = 1.03f)
                            )
                    },
                    label = "storyPage",
                    modifier = Modifier.fillMaxSize(),
                ) { i ->
                    Box(
                        contentAlignment = if (pages[i].top) Alignment.TopCenter else Alignment.Center,
                        modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = if (pages[i].top) 84.dp else 96.dp, bottom = 24.dp),
                    ) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalStoryRestart provides { round++; go(0) }) { pages[i].content() }
                    }
                }
            }

            // On top: the bars of the pages, the volume, pause and close.
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    pages.forEachIndexed { i, _ ->
                        val fill = when {
                            i < index -> 1f
                            i == index -> if (progressFor == index) progress.value else 0f
                            else -> 0f
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
                        ) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Icon(
                        painterResource(R.drawable.volume_up),
                        null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    // A thin, quiet volume line: drag or tap along it.
                    BoxWithConstraints(
                        Modifier
                            .weight(1f)
                            .height(24.dp)
                            .pointerInput(Unit) {
                                detectTapGestures { o ->
                                    volume = (o.x / size.width).coerceIn(0f, 1f)
                                    audio.volume = volume
                                }
                            }
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures { change, _ ->
                                    volume = (change.position.x / size.width).coerceIn(0f, 1f)
                                    audio.volume = volume
                                }
                            },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)))
                        Box(Modifier.fillMaxWidth(volume).height(2.dp).clip(RoundedCornerShape(1.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)))
                    }
                    IconButton(onClick = { paused = !paused }) {
                        Icon(painterResource(if (paused) R.drawable.play else R.drawable.pause), contentDescription = null)
                    }
                    IconButton(onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }) {
                        Icon(painterResource(R.drawable.close), contentDescription = null)
                    }
                }
            }
        }
    }
}

// ── the pages ──

/** Going back to the first page (the "Watch again" button on the card). */
private val LocalStoryRestart = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

/** Soft, long ease-out: things settle in place instead of popping. */
private val Settle = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

private fun monthName(review: MonthReview.Review): String =
    review.month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()).replaceFirstChar { it.uppercase() }

/** "{month}" in the prepositional case where the language needs it (ru), else the plain name. */
private fun inMonth(review: MonthReview.Review): String {
    if (Locale.getDefault().language != "ru") return monthName(review)
    return when (review.month.monthValue) {
        1 -> "январе"; 2 -> "феврале"; 3 -> "марте"; 4 -> "апреле"; 5 -> "мае"; 6 -> "июне"
        7 -> "июле"; 8 -> "августе"; 9 -> "сентябре"; 10 -> "октябре"; 11 -> "ноябре"; else -> "декабре"
    }
}

private fun dayText(d: java.time.LocalDate): String =
    "${d.dayOfMonth} " + d.month.getDisplayName(TextStyle.FULL, Locale.getDefault())

private fun storyPages(r: MonthReview.Review, round: Int = 0): List<StoryPage> = buildList {
    // The text is all there after ~2.1 s; the wait after it is a third shorter than before.
    add(StoryPage("hello", 4_700) { HelloPage(r) })
    add(StoryPage("minutes", 8_000) { MinutesPage(r) })
    r.busiestDay?.let { add(StoryPage("day", 8_000) { BusiestDayPage(r) }) }
    val topTrack = r.topTracks.firstOrNull()
    if (topTrack != null) {
        add(StoryPage("topTrack", 11_000, topTrack.songId, fullVolumeAtMs = 2_900) { TopTrackPage(r, topTrack) })
        if (r.topTracks.size > 1) add(StoryPage("topTracks", 12_000, topTrack.songId, top = true) { TopTracksPage(r.topTracks) })
    }
    val topArtist = r.topArtists.firstOrNull()
    if (topArtist != null) {
        add(StoryPage("topArtist", 11_000, topArtist.songId, fullVolumeAtMs = 2_900) { TopArtistPage(r, topArtist) })
        if (r.topArtists.size > 1) add(StoryPage("topArtists", 12_000, topArtist.songId, top = true) { TopArtistsPage(r.topArtists) })
    }
    // The top genre's page plays one of its songs, another one each round.
    val genreSong = r.genreSongs.takeIf { it.isNotEmpty() }?.let { it[kotlin.random.Random(r.month.hashCode() + round * 7919).nextInt(it.size)] }
    if (r.genres.isNotEmpty()) add(StoryPage("genres", 11_000, genreSong, fullVolumeAtMs = 2_700) { GenresPage(r) })
    add(StoryPage("card", 0) { CardPage(r) })
}

/**
 * Appears after [delayMs], in place: it keeps its room from the start (nothing above or below
 * moves), fades in and glides the last bit of the way — from the right ([fromRight]) or from below.
 */
@Composable
private fun Appear(delayMs: Long, fromRight: Boolean = false, durationMs: Int = 1_000, content: @Composable () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        t.animateTo(1f, tween(durationMs, easing = Settle))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = t.value
            val left = 1f - t.value
            if (fromRight) translationX = left * size.width * 0.35f else translationY = left * 36.dp.toPx()
            val scale = 0.97f + 0.03f * t.value
            scaleX = scale
            scaleY = scale
        },
    ) { content() }
}

/** A number counting up from zero, starting after [delayMs]. */
@Composable
private fun CountUp(value: Long, delayMs: Long = 0, style: @Composable (String) -> Unit) {
    val n = remember { Animatable(0f) }
    LaunchedEffect(value) {
        delay(delayMs)
        n.animateTo(value.toFloat(), tween(1_800, easing = Settle))
    }
    style("%,d".format(n.value.toLong()).replace(',', ' '))
}

@Composable
private fun Preface(text: String, delayMs: Long = 0) {
    Appear(delayMs, durationMs = 800) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun HelloPage(r: MonthReview.Review) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Appear(150, durationMs = 1_100) {
            Text(stringResource(R.string.story_hello), fontSize = 44.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        }
        Appear(1_100) {
            Text(
                stringResource(R.string.story_hello_sub, monthName(r)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MinutesPage(r: MonthReview.Review) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Preface(stringResource(R.string.story_minutes_pre, inMonth(r)), 100)
        Appear(1_400) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CountUp(r.minutes, 1_400) { Text(it, fontSize = 60.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary) }
                Text(stringResource(R.string.story_minutes), style = MaterialTheme.typography.titleMedium)
            }
        }
        Appear(3_600) {
            Text(stringResource(R.string.story_listens_line, r.listens), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BusiestDayPage(r: MonthReview.Review) {
    val day = r.busiestDay ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Preface(stringResource(R.string.story_day_pre), 100)
        Appear(1_500) {
            Text(dayText(day), fontSize = 46.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        }
        Appear(2_500) {
            Text(stringResource(R.string.story_day_minutes, r.busiestDayMinutes), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        r.topHour?.let { h ->
            Appear(4_000) {
                Text(stringResource(R.string.story_hour_line, "%02d:00".format(h)), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TopTrackPage(r: MonthReview.Review, top: MonthReview.Item) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The lead-in, then the track: on the same page.
        Preface(stringResource(R.string.story_tracks_count, r.distinctTracks), 100)
        Preface(stringResource(R.string.story_tracks_tease), 1_200)
        Appear(2_800, durationMs = 1_300) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AsyncImage(
                    model = top.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(0.62f).aspectRatio(1f).clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                Text(top.title, fontSize = 26.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                top.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Appear(4_400) {
            Text(
                top.since?.let { stringResource(R.string.story_since_played, dayText(it), top.sinceCount) }
                    ?: stringResource(R.string.story_played_times, top.count),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TopArtistPage(r: MonthReview.Review, top: MonthReview.Item) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Preface(stringResource(R.string.story_artists_count, r.distinctArtists), 100)
        Preface(stringResource(R.string.story_artists_tease), 1_200)
        Appear(2_800, durationMs = 1_300) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AsyncImage(
                    model = top.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(0.56f).aspectRatio(1f).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                Text(top.title, fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Appear(4_200) {
            Text(stringResource(R.string.story_listens_minutes, top.count, top.minutes), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (top.streakDays > 1) {
            Appear(5_200) {
                Text(stringResource(R.string.story_streak, top.streakDays), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Top five, from fifth place to first, each sliding in from the right: the fifth after 0.5 s,
 * then 0.6, 0.7, 0.8, 0.9 s between them, so the first place comes last and slowest. Every row
 * holds its place from the start: nothing moves when the next one comes.
 */
@Composable
private fun RankPage(title: String, items: List<MonthReview.Item>, round: Boolean) {
    val delays = remember(items.size) {
        var t = 700L
        val gaps = listOf(500L, 600L, 700L, 800L, 900L)
        val byPlace = LongArray(items.size)
        for ((step, place) in items.indices.reversed().withIndex()) {
            t += gaps.getOrElse(step) { 900L }
            byPlace[place] = t
        }
        byPlace
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Appear(100, durationMs = 800) { Text(title, fontSize = 30.sp, fontWeight = FontWeight.Black) }
        items.forEachIndexed { i, item ->
            Appear(delays[i], fromRight = true, durationMs = 900 + i * 60) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", fontSize = 26.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(34.dp))
                    AsyncImage(
                        model = item.image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(if (round) CircleShape else RoundedCornerShape(8.dp))
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
}

/**
 * When each place appears: the small ones (from the last up) quickly first, then the top five from
 * fifth to first with 0.5 / 0.6 / 0.7 / 0.8 / 0.9 s between them — the first comes last.
 */
private fun revealTimes(count: Int, top: Int): LongArray {
    val at = LongArray(count)
    var t = 600L
    for (place in (count - 1) downTo top) {
        at[place] = t
        t += 70L
    }
    if (count > top) t += 350L
    val gaps = listOf(500L, 600L, 700L, 800L, 900L)
    for ((step, place) in (minOf(top, count) - 1 downTo 0).withIndex()) {
        t += gaps.getOrElse(step) { 900L }
        at[place] = t
    }
    return at
}

/** The top five tracks large, then sixteen more small: eight in each of two columns. */
@Composable
private fun TopTracksPage(items: List<MonthReview.Item>) {
    val at = remember(items.size) { revealTimes(items.size, 5) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Appear(100, durationMs = 800) { Text(stringResource(R.string.story_top_tracks), fontSize = 26.sp, fontWeight = FontWeight.Black) }
        items.take(5).forEachIndexed { i, item ->
            Appear(at[i], fromRight = true, durationMs = 900 + i * 60) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", fontSize = 24.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(30.dp))
                    AsyncImage(
                        model = item.image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                    Spacer(Modifier.width(12.dp))
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
        val rest = items.drop(5).take(16)
        if (rest.isNotEmpty()) {
            val half = (rest.size + 1) / 2
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(0 until half, half until rest.size).forEach { range ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        range.forEach { k ->
                            val item = rest[k]
                            Appear(at.getOrElse(5 + k) { 600L }, fromRight = true, durationMs = 700) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${6 + k}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
                                    AsyncImage(
                                        model = item.image,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(stringResource(R.string.story_times, item.count), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Ten artists: the first largest and each next place a little smaller down to the fifth (4 and 5
 * alike); places 6–10 as smaller rows under them, three on the left and two on the right. Every one says how many times.
 */
@Composable
private fun TopArtistsPage(items: List<MonthReview.Item>) {
    val at = remember(items.size) { revealTimes(items.size, 5) }
    val image = listOf(88, 74, 64, 56, 56)
    val name = listOf(24, 21, 19, 17, 17)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Appear(100, durationMs = 800) { Text(stringResource(R.string.story_top_artists), fontSize = 28.sp, fontWeight = FontWeight.Black) }
        items.take(5).forEachIndexed { i, item ->
            Appear(at[i], fromRight = true, durationMs = 900 + i * 60) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", fontSize = (name[i] + 4).sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(34.dp))
                    AsyncImage(
                        model = item.image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(image[i].dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontSize = name[i].sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.story_times, item.count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val rest = items.drop(5).take(5)
        if (rest.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(rest.indices.take(3), rest.indices.drop(3)).forEach { column ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        column.forEach { k ->
                            val item = rest[k]
                            Appear(at.getOrElse(5 + k) { 600L }, fromRight = true, durationMs = 700) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${6 + k}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
                                    AsyncImage(
                                        model = item.image,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(stringResource(R.string.story_times, item.count), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenresPage(r: MonthReview.Review) {
    val max = r.genres.maxOf { it.second }.coerceAtLeast(1)
    fun label(g: String) = com.metrolist.music.portrait.Genres.displayName(g) ?: g
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Preface(stringResource(R.string.story_change_pre), 100)
        // How the taste moved over the month, when it did.
        val early = r.genreEarly
        val late = r.genreLate
        if (early != null && late != null && early != late) {
            Appear(1_300) {
                Text(
                    stringResource(R.string.story_change, label(early), label(late)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Appear(2_200, durationMs = 800) { Text(stringResource(R.string.story_genres), fontSize = 28.sp, fontWeight = FontWeight.Black) }
        r.genres.forEachIndexed { i, (genre, n) ->
            Appear(2_700L + (r.genres.lastIndex - i) * 600L, fromRight = true) {
                Column {
                    Text(label(genre), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.fillMaxWidth((n.toFloat() / max).coerceAtLeast(0.04f)).height(8.dp).clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 1f - i * 0.14f)),
                    )
                }
            }
        }
    }
}

/** The card of the month (also what is shared), and the buttons to share or save it. */
@Composable
private fun CardPage(r: MonthReview.Review) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            },
        ) { SummaryCard(r) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                scope.launch {
                    val bitmap = layer.toImageBitmap().asAndroidBitmap()
                    val file = withContext(Dispatchers.IO) { saveCard(context, bitmap, r) }
                    file?.let { shareCard(context, it) }
                }
            }) { Text(stringResource(R.string.story_share)) }
            OutlinedButton(onClick = {
                scope.launch {
                    val bitmap = layer.toImageBitmap().asAndroidBitmap()
                    val ok = withContext(Dispatchers.IO) { savePicture(context, bitmap, r) }
                    Toast.makeText(context, if (ok) R.string.story_saved else R.string.story_save_failed, Toast.LENGTH_SHORT).show()
                }
            }) { Text(stringResource(R.string.story_save)) }
        }
        val restart = LocalStoryRestart.current
        androidx.compose.material3.TextButton(onClick = restart) {
            Icon(painterResource(R.drawable.replay), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.story_restart))
        }
    }
}

@Composable
private fun SummaryCard(r: MonthReview.Review) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .width(300.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent)))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = r.topArtists.firstOrNull()?.image ?: r.topTracks.firstOrNull()?.image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Text(stringResource(R.string.story_card_title, monthName(r)), fontSize = 22.sp, fontWeight = FontWeight.Black)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.story_top_artists_short), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                r.topArtists.take(5).forEachIndexed { i, a -> Text("${i + 1} ${a.title}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.story_top_tracks_short), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                r.topTracks.take(5).forEachIndexed { i, t -> Text("${i + 1} ${t.title}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.story_minutes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("%,d".format(r.minutes).replace(',', ' '), fontSize = 26.sp, fontWeight = FontWeight.Black, color = accent)
            }
            r.genres.firstOrNull()?.let { (g, _) ->
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.story_top_genre), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(com.metrolist.music.portrait.Genres.displayName(g) ?: g, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text("Drxwnify", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun cardName(r: MonthReview.Review) = "drxwnify-${r.month}.png"

/** The card as a PNG in the cache (for sharing). */
private fun saveCard(context: android.content.Context, bitmap: android.graphics.Bitmap, r: MonthReview.Review): File? = runCatching {
    val dir = File(context.cacheDir, "month_review").also { it.mkdirs() }
    File(dir, cardName(r)).also { f -> f.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
}.getOrNull()

private fun shareCard(context: android.content.Context, file: File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** The card into the gallery (Pictures/Drxwnify). */
private fun savePicture(context: android.content.Context, bitmap: android.graphics.Bitmap, r: MonthReview.Review): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, cardName(r))
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Drxwnify")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        true
    } else {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Drxwnify").also { it.mkdirs() }
        File(dir, cardName(r)).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        true
    }
}.getOrDefault(false)
