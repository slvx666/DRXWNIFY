/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.utils.CoverSaver
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A cover that can be looked at: tap opens it full screen (growing out of where it was, as in
 * Telegram/Ozon), long press offers to save or share it. Each screen with a cover keeps one of
 * these; [bounds] tracks where the cover is drawn so the viewer can open from and close into it.
 */
class CoverViewerState(private val context: android.content.Context) {
    var url by mutableStateOf<String?>(null)
        private set
    var name by mutableStateOf("")
        private set
    var sourceBounds by mutableStateOf<Rect?>(null)
        private set
    var actionsFor by mutableStateOf<String?>(null)
        private set

    /** Latest on-screen position of the cover this state belongs to. */
    var bounds: Rect? = null

    fun open(url: String?, name: String) {
        if (url.isNullOrBlank()) return
        this.name = name
        this.sourceBounds = bounds
        this.url = url
    }

    fun close() {
        url = null
    }

    /** Long press: the save/share sheet (not an immediate download). */
    fun showActions(url: String?, name: String) {
        if (url.isNullOrBlank()) return
        this.name = name
        actionsFor = url
    }

    fun hideActions() {
        actionsFor = null
    }

    fun save(url: String?, name: String) = CoverSaver.saveInBackground(context, url, name)

    fun share(url: String?, name: String) = CoverSaver.shareInBackground(context, url, name)

    @Composable
    fun Content() {
        val open = url
        if (open != null) {
            // The viewer shows the long-press sheet itself, above its own window.
            CoverViewer(state = this, url = open)
        } else {
            actionsFor?.let { CoverActionsSheet(state = this, url = it) }
        }
    }
}

@Composable
fun rememberCoverViewerState(): CoverViewerState {
    val context = LocalContext.current
    return remember { CoverViewerState(context.applicationContext) }
}

/** Keeps [state] informed where this cover is on screen (for the open/close animation). */
fun Modifier.coverSource(state: CoverViewerState): Modifier =
    onGloballyPositioned { state.bounds = it.boundsInWindow() }

/**
 * Save / share sheet shown on a long press: the cover, its name and two big actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverActionsSheet(state: CoverViewerState, url: String) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun dismissThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            state.hideActions()
            action()
        }
    }
    ModalBottomSheet(
        onDismissRequest = state::hideActions,
        sheetState = sheetState,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 8.dp,
                modifier = Modifier.size(200.dp),
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(16.dp))
            if (state.name.isNotBlank()) {
                Text(
                    text = state.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                CoverAction(
                    icon = R.drawable.download,
                    label = stringResource(R.string.save_cover_action),
                    primary = true,
                    modifier = Modifier.weight(1f),
                    onClick = { dismissThen { state.save(url, state.name) } },
                )
                CoverAction(
                    icon = R.drawable.share,
                    label = stringResource(R.string.share),
                    primary = false,
                    modifier = Modifier.weight(1f),
                    onClick = { dismissThen { state.share(url, state.name) } },
                )
            }
        }
    }
}

@Composable
private fun CoverAction(icon: Int, label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 14.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The cover full screen, Telegram-style: it grows out of the spot it was tapped in; pinch zooms
 * around the fingers, double tap zooms in where tapped (and back), drag moves a zoomed picture,
 * dragging an unzoomed one up or down lets it go (the background fades with the distance), tap
 * shows/hides the bars, long press opens save/share.
 */
@Composable
private fun CoverViewer(state: CoverViewerState, url: String) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val largest = remember(url) { CoverSaver.largest(url) }

    val appear = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    val panX = remember { Animatable(0f) }
    val panY = remember { Animatable(0f) }
    val dismissDrag = remember { Animatable(0f) }
    var chromeVisible by remember { mutableStateOf(true) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        appear.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
    }

    fun close() {
        if (closing) return
        closing = true
        chromeVisible = false
        scope.launch {
            launch { scale.animateTo(1f, tween(220)) }
            launch { panX.animateTo(0f, tween(220)) }
            launch { panY.animateTo(0f, tween(220)) }
            appear.animateTo(0f, tween(260))
            state.close()
        }
    }

    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenW = constraints.maxWidth.toFloat()
            val screenH = constraints.maxHeight.toFloat()
            // Covers are square: full width, centred.
            val side = minOf(screenW, screenH)
            val target = Rect(Offset((screenW - side) / 2f, (screenH - side) / 2f), androidx.compose.ui.geometry.Size(side, side))
            val source = state.sourceBounds
            val dragLimit = with(density) { 140.dp.toPx() }
            val dragFade = (1f - abs(dismissDrag.value) / (dragLimit * 2.2f)).coerceIn(0f, 1f)

            // Background: fades in with the opening and out with the dismiss drag.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) * dragFade }
                    .background(Color.Black),
            )

            // The picture: interpolated from the source rect to the full-screen rect.
            val p = appear.value
            val from = source ?: Rect(target.center - Offset(side * 0.42f, side * 0.42f), androidx.compose.ui.geometry.Size(side * 0.84f, side * 0.84f))
            val left = from.left + (target.left - from.left) * p
            val top = from.top + (target.top - from.top) * p
            val width = from.width + (target.width - from.width) * p
            val corner = with(density) { (12.dp.toPx() * (1f - p)).toDp() }

            AsyncImage(
                model = largest,
                placeholder = null,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(with(density) { width.toDp() })
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        translationX = panX.value
                        translationY = panY.value + dismissDrag.value
                        alpha = if (source == null) p.coerceIn(0f, 1f) else 1f
                    }
                    .clip(RoundedCornerShape(corner)),
            )

            // Gestures on the whole screen.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { chromeVisible = !chromeVisible },
                            onDoubleTap = { tap ->
                                scope.launch {
                                    if (scale.value > 1.05f) {
                                        launch { panX.animateTo(0f, tween(240)) }
                                        launch { panY.animateTo(0f, tween(240)) }
                                        scale.animateTo(1f, tween(240))
                                    } else {
                                        // Zoom in on the tapped spot.
                                        val zoom = 2.6f
                                        val focus = tap - target.center
                                        val maxX = side * (zoom - 1f) / 2f
                                        val maxY = maxOf(0f, (side * zoom - screenH) / 2f)
                                        launch { panX.animateTo((-focus.x * (zoom - 1f)).coerceIn(-maxX, maxX), tween(240)) }
                                        launch { panY.animateTo((-focus.y * (zoom - 1f)).coerceIn(-maxY, maxY), tween(240)) }
                                        scale.animateTo(zoom, tween(240))
                                    }
                                }
                            },
                            onLongPress = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                state.showActions(url, state.name)
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val velocity = VelocityTracker()
                            var dismissing = false
                            var moved = 0f
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                val zoomChange = event.calculateZoom()
                                val pan = event.calculatePan()
                                moved += pan.getDistance()
                                if (pressed.size >= 2 || scale.value > 1.01f) {
                                    // Zoom around the fingers, move a zoomed picture.
                                    val newScale = (scale.value * zoomChange).coerceIn(1f, 6f)
                                    val centroid = event.calculateCentroid(useCurrent = true) - target.center
                                    val k = newScale / scale.value
                                    val maxX = side * (newScale - 1f) / 2f
                                    val maxY = maxOf(0f, (side * newScale - screenH) / 2f)
                                    val nx = ((panX.value - centroid.x) * k + centroid.x + pan.x).coerceIn(-maxX, maxX)
                                    val ny = ((panY.value - centroid.y) * k + centroid.y + pan.y).coerceIn(-maxY, maxY)
                                    scope.launch {
                                        scale.snapTo(newScale)
                                        panX.snapTo(nx)
                                        panY.snapTo(ny)
                                    }
                                    event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                                } else if (dismissing || (moved > viewConfiguration.touchSlop && abs(pan.y) > abs(pan.x))) {
                                    // Unzoomed: drag up/down to let it go.
                                    dismissing = true
                                    pressed.firstOrNull()?.let { velocity.addPosition(it.uptimeMillis, it.position) }
                                    scope.launch { dismissDrag.snapTo(dismissDrag.value + pan.y) }
                                    event.changes.forEach { it.consume() }
                                }
                            } while (true)

                            if (dismissing) {
                                val v = velocity.calculateVelocity().y
                                if (abs(dismissDrag.value) > dragLimit || abs(v) > 2200f) {
                                    close()
                                    scope.launch { dismissDrag.animateTo(0f, tween(260)) }
                                } else {
                                    scope.launch { dismissDrag.animateTo(0f, spring(dampingRatio = 0.75f)) }
                                }
                            }
                        }
                    },
            )

            // Bars: close + name on top, save/share at the bottom; tap toggles them.
            AnimatedVisibility(
                visible = chromeVisible && appear.value > 0.6f && abs(dismissDrag.value) < 8f,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(120)),
            ) {
                Box(Modifier.fillMaxSize()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.35f))
                            .statusBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        IconButton(onClick = ::close) {
                            Icon(painterResource(R.drawable.arrow_back), stringResource(R.string.close), tint = Color.White)
                        }
                        Text(
                            text = state.name,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.35f))
                            .navigationBarsPadding()
                            .padding(vertical = 8.dp),
                    ) {
                        ViewerBarButton(R.drawable.download, stringResource(R.string.save_cover_action)) {
                            state.save(url, state.name)
                        }
                        ViewerBarButton(R.drawable.share, stringResource(R.string.share)) {
                            state.share(url, state.name)
                        }
                    }
                }
            }

            // The long-press sheet also has to show above the viewer.
            state.actionsFor?.let { CoverActionsSheet(state = state, url = it) }
        }
    }
}

@Composable
private fun ViewerBarButton(icon: Int, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(4.dp).height(4.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}
