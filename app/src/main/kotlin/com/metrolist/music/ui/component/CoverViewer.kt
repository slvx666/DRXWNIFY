/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.sign
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
import coil3.request.crossfade
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
 * The cover full screen, the way Telegram and Ozon do it:
 *  - it grows out of the cover that was tapped (the already loaded small picture shows at once,
 *    the big one fades in over it) and shrinks back into it when closed;
 *  - pinch zooms around the fingers and can shrink below full size — let go small and it closes;
 *  - double tap zooms in where tapped, or back out;
 *  - a zoomed picture pans and keeps gliding after a flick, stopping at its edges;
 *  - an unzoomed picture follows the finger in any direction, shrinking a little and letting the
 *    background through; flick it or drag it far enough and it flies off, otherwise it springs back;
 *  - tap shows/hides the bars, long press opens save/share.
 */
@Composable
private fun CoverViewer(state: CoverViewerState, url: String) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val largest = remember(url) { CoverSaver.largest(url) }

    val appear = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    val tx = remember { Animatable(0f) }
    val ty = remember { Animatable(0f) }
    var chromeVisible by remember { mutableStateOf(true) }
    var closing by remember { mutableStateOf(false) }
    // While flying off after a swipe the picture keeps its place instead of returning to the source.
    var flyingOff by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        appear.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 380f))
    }

    /** Back into the cover it came from. */
    fun closeToSource() {
        if (closing) return
        closing = true
        chromeVisible = false
        scope.launch {
            val spec = tween<Float>(300, easing = FastOutSlowInEasing)
            launch { scale.animateTo(1f, spec) }
            launch { tx.animateTo(0f, spec) }
            launch { ty.animateTo(0f, spec) }
            appear.animateTo(0f, spec)
            state.close()
        }
    }

    /** Off the screen in the direction it was flung. */
    fun flyOff(velocity: Offset, screenH: Float) {
        if (closing) return
        closing = true
        flyingOff = true
        chromeVisible = false
        scope.launch {
            val dirY = if (abs(velocity.y) > 200f) sign(velocity.y) else if (ty.value != 0f) sign(ty.value) else 1f
            val spec = tween<Float>(220, easing = LinearOutSlowInEasing)
            launch { tx.animateTo(tx.value + velocity.x * 0.12f, spec) }
            launch { ty.animateTo(dirY * screenH, spec) }
            appear.animateTo(0f, tween(240))
            state.close()
        }
    }

    Dialog(
        onDismissRequest = ::closeToSource,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // No window dim or window animation: the viewer draws and animates its own background,
        // otherwise the platform darkens everything at once and the opening looks delayed.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setDimAmount(0f)
            dialogWindow?.setWindowAnimations(0)
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenW = constraints.maxWidth.toFloat()
            val screenH = constraints.maxHeight.toFloat()
            val side = minOf(screenW, screenH)
            val target = Rect(Offset((screenW - side) / 2f, (screenH - side) / 2f), Size(side, side))
            val source = state.sourceBounds
            val from = source ?: Rect(target.center - Offset(side * 0.4f, side * 0.4f), Size(side * 0.8f, side * 0.8f))

            val p = appear.value
            val pc = p.coerceIn(0f, 1f)
            val unzoomed = scale.value <= 1.01f
            // How far an unzoomed picture has been dragged (0..1): it shrinks and lets the page through.
            val drag = if (unzoomed && !flyingOff) {
                (kotlin.math.hypot(tx.value, ty.value) / (screenH * 0.45f)).coerceIn(0f, 1f)
            } else if (flyingOff) {
                1f - pc
            } else {
                0f
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = pc * (1f - drag * 0.85f) }
                    .background(Color.Black),
            )

            val left = from.left + (target.left - from.left) * p
            val top = from.top + (target.top - from.top) * p
            val width = (from.width + (target.width - from.width) * p).coerceAtLeast(1f)
            // The opening spring may overshoot past 1: the corner must never go negative.
            val corner = with(density) { (12.dp.toPx() * (1f - pc) + 10.dp.toPx() * drag).toDp() }
            val visualScale = scale.value * (1f - 0.18f * drag)

            Box(
                modifier = Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(with(density) { width.toDp() })
                    .graphicsLayer {
                        scaleX = visualScale
                        scaleY = visualScale
                        translationX = tx.value
                        translationY = ty.value
                        alpha = if (source == null && !flyingOff) pc else 1f
                    }
                    .clip(RoundedCornerShape(corner)),
            ) {
                // The small picture is already in memory: it shows immediately, the big one fades in.
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                AsyncImage(
                    model = coil3.request.ImageRequest.Builder(LocalContext.current)
                        .data(largest)
                        .crossfade(250)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { chromeVisible = !chromeVisible },
                            onDoubleTap = { tapPoint ->
                                scope.launch {
                                    val spec = spring<Float>(dampingRatio = 0.85f, stiffness = 420f)
                                    if (scale.value > 1.05f) {
                                        launch { tx.animateTo(0f, spec) }
                                        launch { ty.animateTo(0f, spec) }
                                        scale.animateTo(1f, spec)
                                    } else {
                                        val zoom = 2.6f
                                        val focus = tapPoint - target.center
                                        val maxX = side * (zoom - 1f) / 2f
                                        val maxY = maxOf(0f, (side * zoom - screenH) / 2f)
                                        launch { tx.animateTo((-focus.x * (zoom - 1f)).coerceIn(-maxX, maxX), spec) }
                                        launch { ty.animateTo((-focus.y * (zoom - 1f)).coerceIn(-maxY, maxY), spec) }
                                        scale.animateTo(zoom, spec)
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
                            val tracker = VelocityTracker()
                            var mode = GestureMode.NONE
                            var travelled = 0f
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty() || closing) break
                                val pan = event.calculatePan()
                                val zoomChange = event.calculateZoom()
                                travelled += pan.getDistance()
                                pressed.first().let { tracker.addPosition(it.uptimeMillis, it.position) }

                                if (pressed.size >= 2) {
                                    mode = GestureMode.TRANSFORM
                                } else if (mode == GestureMode.NONE && travelled > viewConfiguration.touchSlop) {
                                    mode = if (scale.value > 1.01f) GestureMode.PAN else GestureMode.DRAG
                                }

                                when (mode) {
                                    GestureMode.TRANSFORM -> {
                                        // Around the fingers; below full size it gets harder (rubber band).
                                        val raw = scale.value * zoomChange
                                        val newScale = if (raw < 1f) {
                                            (scale.value * (1f + (zoomChange - 1f) * 0.5f)).coerceIn(0.5f, 1f)
                                        } else {
                                            raw.coerceAtMost(6f)
                                        }
                                        val centroid = event.calculateCentroid(useCurrent = true) - target.center
                                        val k = newScale / scale.value
                                        val nx = (tx.value - centroid.x) * k + centroid.x + pan.x
                                        val ny = (ty.value - centroid.y) * k + centroid.y + pan.y
                                        scope.launch {
                                            scale.snapTo(newScale)
                                            tx.snapTo(nx)
                                            ty.snapTo(ny)
                                        }
                                    }
                                    GestureMode.PAN -> {
                                        val maxX = side * (scale.value - 1f) / 2f
                                        val maxY = maxOf(0f, (side * scale.value - screenH) / 2f)
                                        scope.launch {
                                            tx.snapTo(rubber(tx.value + pan.x, maxX))
                                            ty.snapTo(rubber(ty.value + pan.y, maxY))
                                        }
                                    }
                                    GestureMode.DRAG -> scope.launch {
                                        tx.snapTo(tx.value + pan.x)
                                        ty.snapTo(ty.value + pan.y)
                                    }
                                    GestureMode.NONE -> Unit
                                }
                                if (mode != GestureMode.NONE) event.changes.forEach { it.consume() }
                            } while (true)

                            if (closing) return@awaitEachGesture
                            val v = tracker.calculateVelocity()
                            val velocity = Offset(v.x, v.y)
                            when (mode) {
                                GestureMode.DRAG -> {
                                    val distance = kotlin.math.hypot(tx.value, ty.value)
                                    if (distance > with(density) { 110.dp.toPx() } || velocity.getDistance() > 1400f) {
                                        flyOff(velocity, screenH)
                                    } else {
                                        val back = spring<Float>(dampingRatio = 0.72f, stiffness = 420f)
                                        scope.launch { tx.animateTo(0f, back, initialVelocity = velocity.x) }
                                        scope.launch { ty.animateTo(0f, back, initialVelocity = velocity.y) }
                                    }
                                }
                                GestureMode.TRANSFORM, GestureMode.PAN -> {
                                    val s = scale.value
                                    when {
                                        s < 0.82f -> closeToSource()
                                        s < 1f -> {
                                            val back = spring<Float>(dampingRatio = 0.8f, stiffness = 420f)
                                            scope.launch { scale.animateTo(1f, back) }
                                            scope.launch { tx.animateTo(0f, back) }
                                            scope.launch { ty.animateTo(0f, back) }
                                        }
                                        else -> {
                                            // Glide on after a flick, then settle inside the edges.
                                            val maxX = side * (s - 1f) / 2f
                                            val maxY = maxOf(0f, (side * s - screenH) / 2f)
                                            val decay = exponentialDecay<Float>(frictionMultiplier = 1.6f)
                                            scope.launch {
                                                tx.updateBounds(-maxX, maxX)
                                                if (tx.value !in -maxX..maxX) tx.animateTo(tx.value.coerceIn(-maxX, maxX), spring(0.8f, 420f))
                                                else tx.animateDecay(velocity.x, decay)
                                                tx.updateBounds(null, null)
                                            }
                                            scope.launch {
                                                ty.updateBounds(-maxY, maxY)
                                                if (ty.value !in -maxY..maxY) ty.animateTo(ty.value.coerceIn(-maxY, maxY), spring(0.8f, 420f))
                                                else ty.animateDecay(velocity.y, decay)
                                                ty.updateBounds(null, null)
                                            }
                                        }
                                    }
                                }
                                GestureMode.NONE -> Unit
                            }
                        }
                    },
            )

            AnimatedVisibility(
                visible = chromeVisible && pc > 0.6f && drag < 0.02f && !closing,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(120)),
            ) {
                Box(Modifier.fillMaxSize()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)),
                            )
                            .statusBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        IconButton(onClick = ::closeToSource) {
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
                            .background(
                                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))),
                            )
                            .navigationBarsPadding()
                            .padding(top = 24.dp, bottom = 8.dp),
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

            state.actionsFor?.let { CoverActionsSheet(state = state, url = it) }
        }
    }
}

private enum class GestureMode { NONE, TRANSFORM, PAN, DRAG }

/** Past [limit] the picture still moves, but only a third as far (the rubber-band edge). */
private fun rubber(value: Float, limit: Float): Float = when {
    value > limit -> limit + (value - limit) / 3f
    value < -limit -> -limit + (value + limit) / 3f
    else -> value
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
