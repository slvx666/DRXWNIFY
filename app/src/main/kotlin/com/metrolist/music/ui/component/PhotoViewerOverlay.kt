/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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
    val endRect: Rect?,
    val endCornerRadiusPx: Float,
    val startScrimAlphaMultiplier: Float,
    val isSwipeDismiss: Boolean
)

private enum class PhotoViewerPhase {
    Opening,
    Interactive,
    Closing
}

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

@Composable
fun PhotoViewerHost(
    state: PhotoViewerHostState,
    modifier: Modifier = Modifier,
    onDismissed: () -> Unit = {}
) {
    val session = state.session ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var containerSize by remember(session.nonce) { mutableStateOf(IntSize.Zero) }
    var phase by remember(session.nonce) { mutableStateOf(PhotoViewerPhase.Opening) }
    var closingTransition by remember(session.nonce) { mutableStateOf<ClosingTransition?>(null) }
    var dragOffset by remember(session.nonce) { mutableStateOf(Offset.Zero) }
    var handledDismissRequestCount by remember(session.nonce) { mutableIntStateOf(state.dismissRequestCount) }

    val progress = remember(session.nonce) { Animatable(0f) }
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
    val transitionPainter = rememberAsyncImagePainter(
        model = remember(transitionItem.model) {
            ImageRequest.Builder(context).data(transitionItem.model).crossfade(false).build()
        }
    )
    val transitionAspectRatio = transitionPainter.intrinsicSize.aspectRatioOrNull()
    val lockedAspectRatios = remember(session.nonce) { mutableStateMapOf<String, Float>() }
    val animationProgress = progress.value.coerceIn(0f, 1f)
    val dragDistance = computeDragDistance(dragOffset)
    val dragScale = 1f
    val openingSourceRect = state.sourceRect(openingItem.id) ?: fallbackSourceRect(containerSize)
    val openingSourceCornerRadiusPx = state.sourceCornerRadiusPx(openingItem.id)
    val dismissSwipeThresholdPx = computeDismissSwipeThresholdPx(
        density = LocalDensity.current,
        viewportHeight = containerSize.height
    )
    val scrimAlphaMultiplier = computeScrimAlphaMultiplier(
        distance = dragDistance,
        dismissSwipeThresholdPx = dismissSwipeThresholdPx
    )
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
    val scrimAlpha = when (phase) {
        PhotoViewerPhase.Opening -> animationProgress
        PhotoViewerPhase.Interactive -> scrimAlphaMultiplier
        PhotoViewerPhase.Closing -> {
            val closing = closingTransition
            if (closing?.isSwipeDismiss == true) {
                0f
            } else {
                val start = closing?.startScrimAlphaMultiplier ?: 1f
                lerpFloat(0f, start, animationProgress)
            }
        }
    }
    val openingHandoffAlpha = if (animationProgress >= 0.995f) 1f else 0f
    val chromeVisibility by animateFloatAsState(
        targetValue = if (phase == PhotoViewerPhase.Interactive && currentPageZoomed) 0f else 1f,
        animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing),
        label = "PhotoViewerChromeVisibility"
    )

    fun dismissWithAnimation(fromSwipe: Boolean = false) {
        if (phase == PhotoViewerPhase.Closing) return
        val targetRect = if (!currentPageZoomed) state.sourceRect(currentItem.id) else null
        if (targetRect != null) {
            state.updateHiddenSource(currentItem.id)
        }
        state.updateBottomBarHidden(false)
        closingTransition = ClosingTransition(
            startRect = computeCurrentDisplayRect(currentDisplayRect, 1f, dragOffset),
            endRect = targetRect,
            endCornerRadiusPx = if (targetRect != null) state.sourceCornerRadiusPx(currentItem.id) else 0f,
            startScrimAlphaMultiplier = if (fromSwipe) 0f else scrimAlphaMultiplier,
            isSwipeDismiss = fromSwipe
        )
        phase = PhotoViewerPhase.Closing
        scope.launch {
            progress.snapTo(1f)
            progress.animateTo(
                targetValue = 0f,
                animationSpec = if (targetRect != null) {
                    tween(durationMillis = 600, easing = LinearOutSlowInEasing)
                } else {
                    tween(durationMillis = 600, easing = LinearOutSlowInEasing)
                }
            )
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
        withFrameNanos { }
        state.allowSourceHide = true
        progress.animateTo(1f, tween(durationMillis = 500, easing = LinearOutSlowInEasing))
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

    LaunchedEffect(currentPageZoomed, phase) {
        state.updateImmersiveMode(phase == PhotoViewerPhase.Interactive && currentPageZoomed)
    }

    SideEffect {
        state.updateImmersiveMode(phase == PhotoViewerPhase.Interactive && currentPageZoomed)
    }

    val activity = remember(context) { context.findPhotoViewerActivity() }
    val insetsController = remember(activity) {
        activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
    }

    SideEffect {
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (state.isImmersive) {
            insetsController?.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            insetsController?.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    DisposableEffect(insetsController) {
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.statusBars())
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
            .background(Color.Black.copy(alpha = scrimAlpha))
    ) {
        val galleryAlpha = when (phase) {
            PhotoViewerPhase.Opening -> openingHandoffAlpha
            PhotoViewerPhase.Interactive -> 1f
            PhotoViewerPhase.Closing -> if (closingTransition?.endRect != null) 0f else animationProgress
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = galleryAlpha
                    translationX = dragOffset.x
                    translationY = dragOffset.y
                    scaleX = 1f
                    scaleY = 1f
                }
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
                    onDismissFromSwipe = { dismissWithAnimation(fromSwipe = true) },
                    onDragOffsetChanged = { offset ->
                        if (item.id == currentItem.id) {
                            dragOffset = offset
                        }
                    },
                    onZoomStateChanged = { isZoomed ->
                        zoomedPages[item.id] = isZoomed
                        if (item.id == currentItem.id) {
                            state.updateImmersiveMode(
                                phase == PhotoViewerPhase.Interactive && isZoomed
                            )
                        }
                    },
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

        val transitionRect = when (phase) {
            PhotoViewerPhase.Opening -> lerpRectWithArc(
                openingSourceRect,
                openingTargetRect,
                animationProgress
            )
            PhotoViewerPhase.Interactive -> Rect.Zero
            PhotoViewerPhase.Closing -> {
                val closing = closingTransition
                if (closing == null) Rect.Zero else lerpRectWithArc(
                    closing.startRect,
                    closing.endRect ?: scaleRect(closing.startRect, 0.92f),
                    1f - animationProgress
                )
            }
        }

        val transitionCornerRadius = when (phase) {
            PhotoViewerPhase.Opening -> lerpFloat(openingSourceCornerRadiusPx, 0f, animationProgress)
            PhotoViewerPhase.Interactive -> 0f
            PhotoViewerPhase.Closing -> {
                val closing = closingTransition
                if (closing == null) 0f else lerpFloat(0f, closing.endCornerRadiusPx, 1f - animationProgress)
            }
        }

        val transitionAlpha = when (phase) {
            PhotoViewerPhase.Opening -> 1f - openingHandoffAlpha
            PhotoViewerPhase.Interactive -> 0f
            PhotoViewerPhase.Closing -> if (closingTransition?.endRect != null) 1f else 0f
        }
        val closingCropBlend = if (
            phase == PhotoViewerPhase.Closing &&
            closingTransition?.endRect != null
        ) {
            ((0.22f - animationProgress) / 0.22f).coerceIn(0f, 1f)
        } else {
            0f
        }

        if (transitionAlpha > 0f && transitionRect != Rect.Zero) {
            if (phase == PhotoViewerPhase.Closing && closingTransition?.endRect != null) {
                TransitionPhoto(
                    model = transitionItem.model,
                    contentDescription = transitionItem.contentDescription,
                    rect = transitionRect,
                    cornerRadiusPx = transitionCornerRadius,
                    alpha = transitionAlpha * (1f - closingCropBlend),
                    contentScale = ContentScale.Fit
                )
                TransitionPhoto(
                    model = transitionItem.model,
                    contentDescription = transitionItem.contentDescription,
                    rect = transitionRect,
                    cornerRadiusPx = transitionCornerRadius,
                    alpha = transitionAlpha * closingCropBlend,
                    contentScale = ContentScale.Crop
                )
            } else {
                TransitionPhoto(
                    model = transitionItem.model,
                    contentDescription = transitionItem.contentDescription,
                    rect = transitionRect,
                    cornerRadiusPx = transitionCornerRadius,
                    alpha = transitionAlpha,
                    contentScale = if (phase == PhotoViewerPhase.Opening) {
                        ContentScale.Fit
                    } else {
                        ContentScale.Crop
                    }
                )
            }
        }
    }
}

@Composable
private fun TransitionPhoto(
    model: Any,
    contentDescription: String?,
    rect: Rect,
    cornerRadiusPx: Float,
    alpha: Float,
    contentScale: ContentScale
) {
    if (rect.width <= 1f || rect.height <= 1f || alpha <= 0f) return
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(with(density) { cornerRadiusPx.toDp() }))
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
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
    val painter = rememberAsyncImagePainter(
        model = remember(item.model) {
            ImageRequest.Builder(context).data(item.model).crossfade(false).build()
        }
    )
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
    val isZoomed = scale.value > 1.001f

    LaunchedEffect(item.id, isZoomed) { onZoomStateChanged(isZoomed) }
    LaunchedEffect(item.id, isZoomed) {
        if (isZoomed) {
            onDragOffsetChanged(Offset.Zero)
        }
    }
    LaunchedEffect(item.id, baseRect) {
        if (baseRect.width > 0f && baseRect.height > 0f) {
            onBaseRectChanged(baseRect)
        }
    }

    Box(
        modifier = modifier.fillMaxSize().onSizeChanged { viewportSize = it },
        contentAlignment = Alignment.Center
    ) {
        var hiResLoaded by remember(item.id) { mutableStateOf(false) }
        if (item.hiResModel != null) {
            AsyncImage(
                model = remember(item.hiResModel) {
                    ImageRequest.Builder(context).data(item.hiResModel).crossfade(false).build()
                },
                onSuccess = { hiResLoaded = true },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(with(density) { imageSize.width.toDp() }, with(density) { imageSize.height.toDp() })
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        translationX = if (isZoomed) offsetX.value else 0f
                        translationY = if (isZoomed) offsetY.value else 0f
                    }
            )
        }
        Image(
            painter = painter,
            contentDescription = item.contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(with(density) { imageSize.width.toDp() }, with(density) { imageSize.height.toDp() })
                .graphicsLayer {
                    // Drawn over the hi-res layer until that one has loaded, then hidden (it keeps
                    // the gestures: alpha doesn't affect touch).
                    alpha = if (hiResLoaded) 0f else 1f
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = if (isZoomed) offsetX.value else 0f
                    translationY = if (isZoomed) offsetY.value else 0f
                }
                .pointerInput(item.id, interactionsEnabled) {
                    if (!interactionsEnabled) return@pointerInput
                    detectTapGestures(
                        onLongPress = { onLongPress() },
                        onDoubleTap = { tap ->
                            scope.launch {
                                if (scale.value > 1.02f) {
                                    coroutineScope {
                                        launch {
                                            scale.animateTo(
                                                1f,
                                                tween(durationMillis = 220, easing = LinearOutSlowInEasing)
                                            )
                                        }
                                        launch {
                                            offsetX.animateTo(
                                                0f,
                                                tween(durationMillis = 220, easing = LinearOutSlowInEasing)
                                            )
                                        }
                                        launch {
                                            offsetY.animateTo(
                                                0f,
                                                tween(durationMillis = 220, easing = LinearOutSlowInEasing)
                                            )
                                        }
                                    }
                                } else {
                                    val targetScale = 2.35f
                                    val center = Offset(imageSize.width / 2f, imageSize.height / 2f)
                                    val bounded = clampOffset(
                                        (center - tap) * (targetScale - 1f),
                                        targetScale,
                                        imageSize,
                                        viewportSize
                                    )
                                    coroutineScope {
                                        launch {
                                            scale.animateTo(
                                                targetScale,
                                                tween(durationMillis = 220, easing = LinearOutSlowInEasing)
                                            )
                                        }
                                        launch {
                                            offsetX.animateTo(
                                                bounded.x,
                                                tween(durationMillis = 220, easing = LinearOutSlowInEasing)
                                            )
                                        }
                                        launch {
                                            offsetY.animateTo(
                                                bounded.y,
                                                tween(durationMillis = 220, easing = LinearOutSlowInEasing)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
                .pointerInput(item.id, viewportSize, imageSize, isActive, interactionsEnabled) {
                    if (!isActive || !interactionsEnabled) return@pointerInput
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        scope.launch {
                            val oldScale = scale.value
                            val newScale = (oldScale * zoom).coerceIn(1f, 4f)
                            val scaleFactor = if (oldScale > 0f) newScale / oldScale else 1f
                            val nextOffset = if (newScale <= 1.01f) {
                                val freeOffset = clampFreeDragOffset(
                                    candidate = Offset(
                                        x = 0f,
                                        y = offsetY.value + (pan.y * 1.1f)
                                    ),
                                    viewportSize = viewportSize
                                )
                                onDragOffsetChanged(freeOffset)
                                freeOffset
                            } else {
                                onDragOffsetChanged(Offset.Zero)
                                val currentOffset = Offset(offsetX.value, offsetY.value)
                                val center = Offset(imageSize.width / 2f, imageSize.height / 2f)
                                val pivotAdjustment = (centroid - center) * (1f - scaleFactor)
                                clampOffset(
                                    currentOffset + (pan * 2.45f) + pivotAdjustment,
                                    newScale,
                                    imageSize,
                                    viewportSize
                                )
                            }
                            scale.snapTo(newScale)
                            offsetX.snapTo(nextOffset.x)
                            offsetY.snapTo(nextOffset.y)
                        }
                    }
                }
                .pointerInput(item.id, viewportSize, imageSize, isActive, interactionsEnabled) {
                    if (!isActive || !interactionsEnabled) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                        scope.launch {
                            if (scale.value <= 1.01f) {
                                if (kotlin.math.abs(offsetY.value) >= dismissSwipeThresholdPx) {
                                    onDismissFromSwipe()
                                    return@launch
                                }
                                coroutineScope {
                                    launch {
                                        scale.animateTo(
                                            1f,
                                            spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow
                                            )
                                        )
                                    }
                                    launch {
                                        offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessLow)) {
                                            onDragOffsetChanged(Offset(value, offsetY.value))
                                        }
                                    }
                                    launch {
                                        offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessLow)) {
                                            onDragOffsetChanged(Offset(offsetX.value, value))
                                        }
                                    }
                                }
                                onDragOffsetChanged(Offset.Zero)
                            } else {
                                onDragOffsetChanged(Offset.Zero)
                                val bounded = clampOffset(
                                    Offset(offsetX.value, offsetY.value),
                                    scale.value,
                                    imageSize,
                                    viewportSize
                                )
                                coroutineScope {
                                    launch {
                                        offsetX.animateTo(
                                            bounded.x,
                                            spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        )
                                    }
                                    launch {
                                        offsetY.animateTo(
                                            bounded.y,
                                            spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
        )
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
    max(viewportHeight * 0.18f, 104.dp.toPx())
}

private fun clampFreeDragOffset(candidate: Offset, viewportSize: IntSize): Offset {
    if (viewportSize.height <= 0) return Offset(x = 0f, y = candidate.y)
    return Offset(x = 0f, y = candidate.y)
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

private fun computeCurrentDisplayRect(baseRect: Rect, scale: Float, translation: Offset): Rect {
    val width = baseRect.width * scale
    val height = baseRect.height * scale
    val centerX = baseRect.left + baseRect.width / 2f
    val centerY = baseRect.top + baseRect.height / 2f
    return Rect(
        centerX + translation.x - width / 2f,
        centerY + translation.y - height / 2f,
        centerX + translation.x + width / 2f,
        centerY + translation.y + height / 2f
    )
}

private fun scaleRect(rect: Rect, scale: Float): Rect {
    val width = rect.width * scale
    val height = rect.height * scale
    val centerX = rect.left + rect.width / 2f
    val centerY = rect.top + rect.height / 2f
    return Rect(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f)
}

private fun lerpRect(start: Rect, stop: Rect, fraction: Float): Rect {
    val t = fraction.coerceIn(0f, 1f)
    return Rect(
        left = lerpFloat(start.left, stop.left, t),
        top = lerpFloat(start.top, stop.top, t),
        right = lerpFloat(start.right, stop.right, t),
        bottom = lerpFloat(start.bottom, stop.bottom, t)
    )
}

private fun lerpRectWithArc(start: Rect, stop: Rect, fraction: Float): Rect {
    val t = LinearOutSlowInEasing.transform(fraction.coerceIn(0f, 1f))
    val width = lerpFloat(start.width, stop.width, t)
    val height = lerpFloat(start.height, stop.height, t)
    val startCenterX = start.left + (start.width / 2f)
    val startCenterY = start.top + (start.height / 2f)
    val stopCenterX = stop.left + (stop.width / 2f)
    val stopCenterY = stop.top + (stop.height / 2f)
    val linearCenterX = lerpFloat(startCenterX, stopCenterX, t)
    val linearCenterY = lerpFloat(startCenterY, stopCenterY, t)
    val horizontalDistance = stopCenterX - startCenterX
    val verticalDistance = stopCenterY - startCenterY
    val distance = hypot(horizontalDistance.toDouble(), verticalDistance.toDouble()).toFloat()
    val bow = sin(Math.PI * t.toDouble()).toFloat()
    val rightArc = min(distance * 0.08f, 44f) * bow
    val downArc = min(max(kotlin.math.abs(verticalDistance) * 0.12f, distance * 0.04f), 72f) * bow
    val centerX = linearCenterX + rightArc
    val centerY = linearCenterY + downArc
    return Rect(
        left = centerX - (width / 2f),
        top = centerY - (height / 2f),
        right = centerX + (width / 2f),
        bottom = centerY + (height / 2f)
    )
}

private fun android.content.Context.findPhotoViewerActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findPhotoViewerActivity()
    else -> null
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
    start + ((stop - start) * fraction.coerceIn(0f, 1f))

private fun computeDragDistance(offset: Offset): Float =
    hypot(offset.x.toDouble(), offset.y.toDouble()).toFloat()

private fun computeDragScale(distance: Float, containerSize: IntSize): Float {
    val reference = min(containerSize.width, containerSize.height).toFloat()
    if (reference <= 0f) return 1f
    return (1f - (distance / reference) * 0.18f).coerceIn(0.84f, 1f)
}

private fun computeScrimAlphaMultiplier(distance: Float, dismissSwipeThresholdPx: Float): Float {
    if (dismissSwipeThresholdPx <= 0f) return 1f
    return (1f - (distance / dismissSwipeThresholdPx)).coerceIn(0f, 1f)
}

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
