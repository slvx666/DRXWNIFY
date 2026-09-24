/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

data class PhotoViewerItem(
    val id: String,
    val model: Any,
    val contentDescription: String? = null,
    /** Bigger version, loaded over [model] once the photo is open (the small one shows at once). */
    val hiResModel: Any? = null,
)

internal data class PhotoViewerSession(
    val items: List<PhotoViewerItem>,
    val initialIndex: Int,
    val nonce: Long
)

private data class ClosingTransition(
    val startRect: Rect,
    /** The cover it flies back to, followed live (the page behind can still move a little). */
    val endId: String?,
    val endRect: Rect?,
    val endCornerRadiusPx: Float,
    val startScrimAlpha: Float,
)

private enum class PhotoViewerPhase {
    Opening,
    Interactive,
    Closing
}

/**
 * One curve for every move of the viewer: quick start, long soft landing (Material's emphasized
 * decelerate, close to what Telegram uses). Motion is straight, never along an arc.
 */
private val ViewerEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val OPEN_DURATION_MS = 300
private const val CLOSE_DURATION_MS = 260

@Stable
class PhotoViewerHostState {
    private val sourceBounds = mutableStateMapOf<String, Rect>()
    private val sourceCornerRadiiPx = mutableStateMapOf<String, Float>()

    internal var session by mutableStateOf<PhotoViewerSession?>(null)
        private set
    internal var hiddenSourceId by mutableStateOf<String?>(null)
    internal var dismissRequestCount by mutableIntStateOf(0)
    internal var immersiveMode by mutableStateOf(false)
    internal var bottomBarHidden by mutableStateOf(false)
    internal var allowSourceHide by mutableStateOf(true)

    /** Long press on the open photo (the save/share sheet). */
    var onLongPress: ((PhotoViewerItem) -> Unit)? = null

    val isVisible: Boolean
        get() = session != null
    val isImmersive: Boolean
        get() = immersiveMode
    val isBottomBarHidden: Boolean
        get() = bottomBarHidden

    fun open(items: List<PhotoViewerItem>, initialIndex: Int = 0) {
        if (items.isEmpty()) return
        val safeIndex = initialIndex.coerceIn(items.indices)
        session = PhotoViewerSession(items.toList(), safeIndex, System.nanoTime())
        hiddenSourceId = items[safeIndex].id
        bottomBarHidden = true
        allowSourceHide = false
    }

    fun dismiss() {
        if (session != null) dismissRequestCount++
    }

    internal fun registerSource(id: String, bounds: Rect, cornerRadiusPx: Float) {
        sourceBounds[id] = bounds
        sourceCornerRadiiPx[id] = cornerRadiusPx
    }

    internal fun unregisterSource(id: String) {
        sourceBounds.remove(id)
        sourceCornerRadiiPx.remove(id)
        if (hiddenSourceId == id && session == null) hiddenSourceId = null
    }

    internal fun sourceRect(id: String): Rect? = sourceBounds[id]
    internal fun sourceCornerRadiusPx(id: String): Float = sourceCornerRadiiPx[id] ?: 0f
    internal fun updateHiddenSource(id: String?) {
        hiddenSourceId = id
    }

    internal fun updateImmersiveMode(value: Boolean) {
        immersiveMode = value
    }

    internal fun updateBottomBarHidden(value: Boolean) {
        bottomBarHidden = value
    }

    internal fun completeDismiss() {
        session = null
        hiddenSourceId = null
        immersiveMode = false
        bottomBarHidden = false
        allowSourceHide = true
    }
}

@Composable
fun rememberPhotoViewerHostState(): PhotoViewerHostState = remember { PhotoViewerHostState() }

fun Modifier.photoViewerSource(
    state: PhotoViewerHostState,
    id: String,
    cornerRadius: Dp = 12.dp
): Modifier = composed {
    val density = LocalDensity.current
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }
    val hidden = state.hiddenSourceId == id && state.allowSourceHide
    DisposableEffect(state, id) {
        onDispose { state.unregisterSource(id) }
    }
    this
        .graphicsLayer { alpha = if (hidden) 0f else 1f }
        .onGloballyPositioned { state.registerSource(id, it.boundsInRoot(), cornerRadiusPx) }
}

/** The same request everywhere in the viewer, so the transition and the page share one cached bitmap. */
@Composable
private fun rememberViewerPainter(model: Any): AsyncImagePainter {
    val context = LocalContext.current
    return rememberAsyncImagePainter(
        model = remember(model) {
            ImageRequest.Builder(context).data(model).crossfade(false).build()
        }
    )
}

@Composable
fun PhotoViewerHost(
    state: PhotoViewerHostState,
    modifier: Modifier = Modifier,
    onDismissed: () -> Unit = {}
) {
    val session = state.session ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current

    var containerSize by remember(session.nonce) { mutableStateOf(IntSize.Zero) }
    var phase by remember(session.nonce) { mutableStateOf(PhotoViewerPhase.Opening) }
    var closingTransition by remember(session.nonce) { mutableStateOf<ClosingTransition?>(null) }
    var dragOffset by remember(session.nonce) { mutableStateOf(Offset.Zero) }
    var handledDismissRequestCount by remember(session.nonce) { mutableIntStateOf(state.dismissRequestCount) }

    val progress = remember(session.nonce) { Animatable(0f) }
    // The backdrop's own fade on closing: linear in time from wherever the drag left it, so it
    // never drops in a burst at the start of the flight.
    val scrimFade = remember(session.nonce) { Animatable(1f) }
    val zoomedPages = remember(session.nonce) { mutableStateMapOf<String, Boolean>() }
    val imageBaseRects = remember(session.nonce) { mutableStateMapOf<String, Rect>() }
    val pagerState = rememberPagerState(
        initialPage = session.initialIndex,
        pageCount = { session.items.size }
    )

    val currentPage = pagerState.currentPage.coerceIn(session.items.indices)
    val currentItem = session.items[currentPage]
    val openingItem = session.items[session.initialIndex]
    val currentPageZoomed = zoomedPages[currentItem.id] == true
    val transitionItem = when (phase) {
        PhotoViewerPhase.Opening -> openingItem
        PhotoViewerPhase.Interactive, PhotoViewerPhase.Closing -> currentItem
    }
    val transitionPainter = rememberViewerPainter(transitionItem.model)
    val transitionAspectRatio = transitionPainter.intrinsicSize.aspectRatioOrNull()
    val lockedAspectRatios = remember(session.nonce) { mutableStateMapOf<String, Float>() }
    val animationProgress = progress.value.coerceIn(0f, 1f)
    val openingSourceRect = state.sourceRect(openingItem.id) ?: fallbackSourceRect(containerSize)
    val openingSourceCornerRadiusPx = state.sourceCornerRadiusPx(openingItem.id)
    val dismissSwipeThresholdPx = computeDismissSwipeThresholdPx(density, containerSize.height)
    val openingTargetRect = computeTargetRect(
        containerSize,
        lockedAspectRatios[openingItem.id] ?: transitionAspectRatio ?: openingSourceRect.aspectRatioOrNull()
    )
    val currentDisplayRect = imageBaseRects[currentItem.id]
        ?: computeTargetRect(
            containerSize,
            lockedAspectRatios[currentItem.id]
                ?: transitionAspectRatio
                ?: state.sourceRect(currentItem.id)?.aspectRatioOrNull()
        )
    // The backdrop follows the photo at every step, so it never jumps. Read while drawing only:
    // dragging repaints the backdrop without recomposing anything.
    fun scrimNow(): Float = when (phase) {
        PhotoViewerPhase.Opening -> progress.value.coerceIn(0f, 1f)
        PhotoViewerPhase.Interactive -> computeDragScrimAlpha(abs(dragOffset.y), containerSize.height)
        PhotoViewerPhase.Closing -> (closingTransition?.startScrimAlpha ?: 1f) * scrimFade.value
    }

    fun dismissWithAnimation() {
        if (phase == PhotoViewerPhase.Closing) return
        val targetRect = if (!currentPageZoomed) state.sourceRect(currentItem.id) else null
        if (targetRect != null) {
            state.updateHiddenSource(currentItem.id)
        }
        state.updateBottomBarHidden(false)
        val startRect = if (phase == PhotoViewerPhase.Opening) {
            lerpRect(openingSourceRect, openingTargetRect, animationProgress)
        } else {
            computeCurrentDisplayRect(currentDisplayRect, dragOffset)
        }
        closingTransition = ClosingTransition(
            startRect = startRect,
            endId = currentItem.id.takeIf { targetRect != null },
            endRect = targetRect,
            endCornerRadiusPx = if (targetRect != null) state.sourceCornerRadiusPx(currentItem.id) else 0f,
            startScrimAlpha = scrimNow(),
        )
        phase = PhotoViewerPhase.Closing
        scope.launch {
            progress.snapTo(1f)
            scrimFade.snapTo(1f)
            coroutineScope {
                launch { scrimFade.animateTo(0f, tween(durationMillis = CLOSE_DURATION_MS, easing = LinearEasing)) }
                launch { progress.animateTo(0f, tween(durationMillis = CLOSE_DURATION_MS, easing = ViewerEasing)) }
            }
            state.completeDismiss()
            onDismissed()
        }
    }

    BackHandler(enabled = true) { dismissWithAnimation() }

    LaunchedEffect(session.nonce) {
        dragOffset = Offset.Zero
        phase = PhotoViewerPhase.Opening
        state.updateHiddenSource(session.items[session.initialIndex].id)
        state.updateBottomBarHidden(true)
        state.allowSourceHide = false
        progress.snapTo(0f)
        // The cover stays in place until its copy is ready to fly: no empty frame, no blink.
        withTimeoutOrNull(250) {
            transitionPainter.state.first {
                it is AsyncImagePainter.State.Success || it is AsyncImagePainter.State.Error
            }
        }
        withFrameNanos { }
        state.allowSourceHide = true
        progress.animateTo(1f, tween(durationMillis = OPEN_DURATION_MS, easing = ViewerEasing))
        if (phase != PhotoViewerPhase.Closing) phase = PhotoViewerPhase.Interactive
    }

    LaunchedEffect(currentItem.id) { state.updateHiddenSource(currentItem.id) }
    LaunchedEffect(openingItem.id, transitionAspectRatio, openingSourceRect) {
        val ratio = transitionAspectRatio ?: openingSourceRect.aspectRatioOrNull()
        if (ratio != null && lockedAspectRatios[openingItem.id] == null) {
            lockedAspectRatios[openingItem.id] = ratio
        }
    }

    LaunchedEffect(currentItem.id, transitionAspectRatio) {
        val ratio = transitionAspectRatio ?: state.sourceRect(currentItem.id)?.aspectRatioOrNull()
        if (ratio != null && lockedAspectRatios[currentItem.id] == null) {
            lockedAspectRatios[currentItem.id] = ratio
        }
    }

    SideEffect {
        state.updateImmersiveMode(phase == PhotoViewerPhase.Interactive && currentPageZoomed)
    }

    val activity = remember(context) { context.findPhotoViewerActivity() }
    val insetsController = remember(activity) {
        activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
    }

    // The status bar disappears on the black without hiding it: hiding it changes the window
    // insets, and the whole app lays itself out again (a visible shift behind the photo and a heavy
    // relayout in the middle of a drag). Dark icons on black are just as invisible and cost nothing.
    val originalLightIcons = remember(session.nonce) { insetsController?.isAppearanceLightStatusBars }
    val iconsOnBlack = phase == PhotoViewerPhase.Interactive
    SideEffect {
        val controller = insetsController ?: return@SideEffect
        controller.isAppearanceLightStatusBars = if (iconsOnBlack) true else originalLightIcons ?: false
    }
    DisposableEffect(insetsController, session.nonce) {
        onDispose {
            originalLightIcons?.let { insetsController?.isAppearanceLightStatusBars = it }
        }
    }

    LaunchedEffect(state.dismissRequestCount) {
        if (handledDismissRequestCount != state.dismissRequestCount) {
            handledDismissRequestCount = state.dismissRequestCount
            dismissWithAnimation()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(20f)
            .onSizeChanged { containerSize = it }
            .drawBehind { drawRect(Color.Black, alpha = scrimNow()) }
    ) {
        val galleryAlpha = when (phase) {
            PhotoViewerPhase.Opening -> 0f
            PhotoViewerPhase.Interactive -> 1f
            PhotoViewerPhase.Closing -> if (closingTransition?.endRect != null) 0f else animationProgress
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = galleryAlpha }
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = false,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = session.items[page]
                ZoomablePhotoPage(
                    item = item,
                    isActive = page == currentPage,
                    interactionsEnabled = phase == PhotoViewerPhase.Interactive,
                    dismissSwipeThresholdPx = dismissSwipeThresholdPx,
                    onDismissFromSwipe = { dismissWithAnimation() },
                    onDragOffsetChanged = { offset ->
                        if (item.id == currentItem.id) {
                            dragOffset = offset
                        }
                    },
                    onZoomStateChanged = { isZoomed -> zoomedPages[item.id] = isZoomed },
                    onBaseRectChanged = { rect -> imageBaseRects[item.id] = rect },
                    aspectRatioHint = lockedAspectRatios[item.id]
                        ?: imageBaseRects[item.id]?.aspectRatioOrNull()
                        ?: if (item.id == transitionItem.id) transitionAspectRatio else null
                            ?: state.sourceRect(item.id)?.aspectRatioOrNull(),
                    onLongPress = { state.onLongPress?.invoke(item) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        val closing = closingTransition
        val transitionRect = when (phase) {
            PhotoViewerPhase.Opening -> lerpRect(openingSourceRect, openingTargetRect, animationProgress)
            PhotoViewerPhase.Interactive -> Rect.Zero
            PhotoViewerPhase.Closing -> if (closing?.endRect == null) {
                Rect.Zero
            } else {
                val end = closing.endId?.let { state.sourceRect(it) } ?: closing.endRect
                lerpRect(closing.startRect, end, 1f - animationProgress)
            }
        }
        val transitionCornerRadius = when (phase) {
            PhotoViewerPhase.Opening -> lerpFloat(openingSourceCornerRadiusPx, 0f, animationProgress)
            PhotoViewerPhase.Interactive -> 0f
            PhotoViewerPhase.Closing -> lerpFloat(0f, closing?.endCornerRadiusPx ?: 0f, 1f - animationProgress)
        }

        if (transitionRect != Rect.Zero) {
            TransitionPhoto(
                painter = transitionPainter,
                contentDescription = transitionItem.contentDescription,
                rect = transitionRect,
                cornerRadiusPx = transitionCornerRadius,
            )
        }
    }
}

@Composable
private fun TransitionPhoto(
    painter: Painter,
    contentDescription: String?,
    rect: Rect,
    cornerRadiusPx: Float,
) {
    if (rect.width <= 1f || rect.height <= 1f) return
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .clip(RoundedCornerShape(with(density) { cornerRadiusPx.toDp() }))
    ) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun ZoomablePhotoPage(
    item: PhotoViewerItem,
    isActive: Boolean,
    interactionsEnabled: Boolean,
    dismissSwipeThresholdPx: Float,
    onDismissFromSwipe: () -> Unit,
    onDragOffsetChanged: (Offset) -> Unit,
    onZoomStateChanged: (Boolean) -> Unit,
    onBaseRectChanged: (Rect) -> Unit,
    aspectRatioHint: Float?,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val painter = rememberViewerPainter(item.model)
    val scale = remember(item.id) { Animatable(1f) }
    val offsetX = remember(item.id) { Animatable(0f) }
    val offsetY = remember(item.id) { Animatable(0f) }
    var viewportSize by remember(item.id) { mutableStateOf(IntSize.Zero) }
    val imageSize = remember(viewportSize, painter.intrinsicSize, aspectRatioHint) {
        computeTargetSize(viewportSize, aspectRatioHint ?: painter.intrinsicSize.aspectRatioOrNull())
    }
    val baseRect = remember(viewportSize, imageSize) {
        Rect(
            left = (viewportSize.width - imageSize.width) / 2f,
            top = (viewportSize.height - imageSize.height) / 2f,
            right = (viewportSize.width + imageSize.width) / 2f,
            bottom = (viewportSize.height + imageSize.height) / 2f
        )
    }
    // Only crossing the threshold recomposes; zooming itself happens in the draw phase.
    val isZoomed by remember(item.id) { derivedStateOf { scale.value > 1.001f } }
    val viewportCenter = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
    val settleSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    val zoomSpec = tween<Float>(durationMillis = 260, easing = ViewerEasing)

    LaunchedEffect(item.id, isZoomed) {
        onZoomStateChanged(isZoomed)
        if (isZoomed) onDragOffsetChanged(Offset.Zero)
    }
    LaunchedEffect(item.id, baseRect) {
        if (baseRect.width > 0f && baseRect.height > 0f) {
            onBaseRectChanged(baseRect)
        }
    }

    // Gestures sit on the untransformed viewport (before any graphicsLayer), so every drag and
    // pinch is measured in screen pixels: the photo moves exactly with the finger.
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it }
            .pointerInput(item.id, interactionsEnabled) {
                if (!interactionsEnabled) return@pointerInput
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onDoubleTap = { tap ->
                        scope.launch {
                            val zoomOut = scale.value > 1.02f
                            val targetScale = if (zoomOut) 1f else 2.5f
                            val target = if (zoomOut) {
                                Offset.Zero
                            } else {
                                clampOffset((viewportCenter - tap) * (targetScale - 1f), targetScale, imageSize, viewportSize)
                            }
                            coroutineScope {
                                launch { scale.animateTo(targetScale, zoomSpec) }
                                launch { offsetX.animateTo(target.x, zoomSpec) }
                                launch { offsetY.animateTo(target.y, zoomSpec) }
                            }
                        }
                    }
                )
            }
            .pointerInput(item.id, viewportSize, imageSize, isActive, interactionsEnabled) {
                if (!isActive || !interactionsEnabled) return@pointerInput
                detectTransformGestures { centroid, pan, zoom, _ ->
                    // Each step reads the values the previous one left (launches run in order).
                    scope.launch {
                        val oldScale = scale.value
                        val newScale = (oldScale * zoom).coerceIn(1f, 5f)
                        val next = if (newScale <= 1.01f && oldScale <= 1.01f) {
                            // Not zoomed: a straight vertical drag towards closing.
                            val free = Offset(0f, offsetY.value + pan.y)
                            onDragOffsetChanged(free)
                            free
                        } else {
                            onDragOffsetChanged(Offset.Zero)
                            val current = Offset(offsetX.value, offsetY.value)
                            // Keeps the point under the fingers in place while scaling.
                            val pivot = (centroid - viewportCenter - current) * (1f - newScale / oldScale)
                            clampOffset(current + pan + pivot, newScale, imageSize, viewportSize)
                        }
                        scale.snapTo(newScale)
                        offsetX.snapTo(next.x)
                        offsetY.snapTo(next.y)
                    }
                }
            }
            .pointerInput(item.id, viewportSize, imageSize, isActive, interactionsEnabled) {
                if (!isActive || !interactionsEnabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.firstOrNull { it.id == down.id }?.let {
                            tracker.addPosition(it.uptimeMillis, it.position)
                        }
                    } while (event.changes.any { it.pressed })
                    val velocityY = tracker.calculateVelocity().y
                    scope.launch {
                        if (scale.value <= 1.01f) {
                            val dragged = offsetY.value
                            // A flick closes too, not only a long drag.
                            val flung = abs(velocityY) > with(density) { 900.dp.toPx() } &&
                                sign(velocityY) == sign(dragged) &&
                                abs(dragged) > with(density) { 12.dp.toPx() }
                            if (abs(dragged) >= dismissSwipeThresholdPx || flung) {
                                onDismissFromSwipe()
                                return@launch
                            }
                            coroutineScope {
                                launch { scale.animateTo(1f, settleSpec) }
                                launch { offsetX.animateTo(0f, settleSpec) }
                                launch {
                                    offsetY.animateTo(0f, settleSpec) {
                                        onDragOffsetChanged(Offset(0f, value))
                                    }
                                }
                            }
                            onDragOffsetChanged(Offset.Zero)
                        } else {
                            val bounded = clampOffset(
                                Offset(offsetX.value, offsetY.value),
                                scale.value,
                                imageSize,
                                viewportSize
                            )
                            coroutineScope {
                                launch { offsetX.animateTo(bounded.x, settleSpec) }
                                launch { offsetY.animateTo(bounded.y, settleSpec) }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val imageModifier = Modifier
            .size(with(density) { imageSize.width.toDp() }, with(density) { imageSize.height.toDp() })
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationX = offsetX.value
                translationY = offsetY.value
            }
        var hiResLoaded by remember(item.id) { mutableStateOf(false) }
        // The sharper version fades in over the small one instead of popping in.
        val lowResAlpha by animateFloatAsState(
            targetValue = if (hiResLoaded) 0f else 1f,
            animationSpec = tween(durationMillis = 220),
            label = "PhotoViewerHiRes",
        )
        if (item.hiResModel != null) {
            AsyncImage(
                model = remember(item.hiResModel) {
                    ImageRequest.Builder(context).data(item.hiResModel).crossfade(false).build()
                },
                onSuccess = { hiResLoaded = true },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = imageModifier
            )
        }
        if (lowResAlpha > 0f) {
            Image(
                painter = painter,
                contentDescription = item.contentDescription,
                contentScale = ContentScale.Fit,
                alpha = lowResAlpha,
                modifier = imageModifier
            )
        }
    }
}

private fun computeTargetRect(containerSize: IntSize, aspectRatio: Float?): Rect {
    val size = computeTargetSize(containerSize, aspectRatio)
    return Rect(
        left = (containerSize.width - size.width) / 2f,
        top = (containerSize.height - size.height) / 2f,
        right = (containerSize.width + size.width) / 2f,
        bottom = (containerSize.height + size.height) / 2f
    )
}

private fun computeTargetSize(containerSize: IntSize, aspectRatio: Float?): Size {
    if (containerSize.width <= 0 || containerSize.height <= 0) return Size.Zero
    val safeAspect = aspectRatio?.takeIf { it.isFinite() && it > 0f }
        ?: (containerSize.width.toFloat() / containerSize.height.toFloat())
    val containerAspect = containerSize.width.toFloat() / containerSize.height.toFloat()
    return if (safeAspect > containerAspect) {
        Size(containerSize.width.toFloat(), containerSize.width.toFloat() / safeAspect)
    } else {
        Size(containerSize.height.toFloat() * safeAspect, containerSize.height.toFloat())
    }
}

private fun Rect.aspectRatioOrNull(): Float? =
    if (width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
        width / height
    } else {
        null
    }

private fun computeDismissSwipeThresholdPx(
    density: Density,
    viewportHeight: Int
): Float = with(density) {
    max(viewportHeight * 0.14f, 96.dp.toPx())
}

/** Fades evenly with the drag, from full down to nothing at half the screen height. */
private fun computeDragScrimAlpha(distance: Float, viewportHeight: Int): Float {
    if (viewportHeight <= 0) return 1f
    return 1f - (distance / (viewportHeight * 0.5f)).coerceIn(0f, 1f)
}

private fun clampOffset(candidate: Offset, scale: Float, imageSize: Size, viewportSize: IntSize): Offset {
    if (viewportSize.width <= 0 || viewportSize.height <= 0) return Offset.Zero
    val horizontalRoom = max(0f, ((imageSize.width * scale) - viewportSize.width) / 2f)
    val verticalRoom = max(0f, ((imageSize.height * scale) - viewportSize.height) / 2f)
    return Offset(
        x = candidate.x.coerceIn(-horizontalRoom, horizontalRoom),
        y = candidate.y.coerceIn(-verticalRoom, verticalRoom)
    )
}

private fun fallbackSourceRect(containerSize: IntSize): Rect {
    if (containerSize.width <= 0 || containerSize.height <= 0) return Rect.Zero
    val side = min(containerSize.width, containerSize.height) * 0.24f
    val centerX = containerSize.width / 2f
    val centerY = containerSize.height / 2f
    return Rect(centerX - side / 2f, centerY - side / 2f, centerX + side / 2f, centerY + side / 2f)
}

private fun computeCurrentDisplayRect(baseRect: Rect, translation: Offset): Rect =
    baseRect.translate(translation)

private fun lerpRect(start: Rect, stop: Rect, fraction: Float): Rect {
    val t = fraction.coerceIn(0f, 1f)
    return Rect(
        left = lerpFloat(start.left, stop.left, t),
        top = lerpFloat(start.top, stop.top, t),
        right = lerpFloat(start.right, stop.right, t),
        bottom = lerpFloat(start.bottom, stop.bottom, t)
    )
}

private fun android.content.Context.findPhotoViewerActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findPhotoViewerActivity()
    else -> null
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
    start + ((stop - start) * fraction.coerceIn(0f, 1f))

private fun Size.aspectRatioOrNull(): Float? = try {
    if (width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
        width / height
    } else {
        null
    }
} catch (_: IllegalStateException) {
    null
}

/** The app-wide photo viewer (drawn over everything, the player included). */
val LocalPhotoViewer = androidx.compose.runtime.staticCompositionLocalOf { PhotoViewerHostState() }
