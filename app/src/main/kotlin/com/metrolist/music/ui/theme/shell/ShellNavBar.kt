/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.constants.IslandTabsOpenKey
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One tab of the navigation, already wired to the app's own handlers. */
@Immutable
data class ShellTab(
    val label: String,
    val iconActive: Int,
    val iconInactive: Int,
    val selected: Boolean,
    val onClick: () -> Unit,
    /** Search: long press opens recognition, as in the standard bar. */
    val onLongClick: (() -> Unit)? = null,
)

/** The themes whose navigation floats over the pages (no opaque band behind it). */
val ShellTheme.floatingNav: Boolean get() = this == ShellTheme.GLASS || this == ShellTheme.ISLAND || this == ShellTheme.ORBIT

fun isFloatingNav(shell: ShellTheme?): Boolean = shell?.floatingNav == true

/** Whether [shell] draws its own navigation bar (Stack and Pulse keep the standard one). */
fun hasOwnNavBar(shell: ShellTheme?): Boolean = when (shell) {
    ShellTheme.MONOLITH, ShellTheme.GLASS, ShellTheme.ISLAND, ShellTheme.ORBIT, ShellTheme.PAPER -> true
    else -> false
}

/**
 * The navigation bar of an experimental theme. It fills the same box as the standard one (the
 * height the app gives it, the system's navigation inset included), so nothing around it moves.
 */
@Composable
fun ShellNavBar(shell: ShellTheme, tabs: List<ShellTab>, pureBlack: Boolean, slim: Boolean, modifier: Modifier = Modifier) {
    when (shell) {
        ShellTheme.MONOLITH -> MonolithNav(tabs, pureBlack, slim, modifier)
        ShellTheme.GLASS -> GlassNav(tabs, slim, modifier)
        ShellTheme.ISLAND -> IslandNav(tabs, modifier)
        ShellTheme.ORBIT -> OrbitNav(tabs, pureBlack, modifier)
        ShellTheme.PAPER -> PaperNav(tabs, slim, modifier)
        else -> Unit
    }
}

/** A press that answers the finger: a little smaller while held, back with a spring; tap and long press. */
@Composable
fun Modifier.shellPress(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    pressedScale: Float = ShellMotion.PRESSED_SCALE,
    onPressed: ((Boolean) -> Unit)? = null,
): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val pressedCallback by rememberUpdatedState(onPressed)
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, ShellMotion.snappy(), label = "press")
    val haptics = LocalHapticFeedback.current
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongClick)
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    pressedCallback?.invoke(true)
                    tryAwaitRelease()
                    pressed = false
                    pressedCallback?.invoke(false)
                },
                onTap = { click() },
                onLongPress = {
                    long?.let {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                },
            )
        }
}

private fun selectedIndex(tabs: List<ShellTab>) = tabs.indexOfFirst { it.selected }

/**
 * An indicator that flows between tabs like a drop: the edge in front moves first and fast, the
 * one behind follows slower, so it stretches on the way and settles round at the end.
 */
@Composable
private fun rememberFlowingEdges(index: Int): Pair<Float, Float> {
    val target = index.coerceAtLeast(0).toFloat()
    var last by remember { mutableFloatStateOf(target) }
    val right = target >= last
    val fast = spring<Float>(dampingRatio = 0.8f, stiffness = 900f)
    val slow = spring<Float>(dampingRatio = 0.86f, stiffness = 300f)
    val calm = ShellRuntime.calm
    val leftEdge by animateFloatAsState(target, if (calm) tween(160) else if (right) slow else fast, label = "left")
    val rightEdge by animateFloatAsState(target, if (calm) tween(160) else if (right) fast else slow, label = "right")
    LaunchedEffect(target) { last = target }
    return leftEdge to rightEdge
}

@Composable
private fun TabIcon(tab: ShellTab, tint: Color, size: Int = 24) {
    Icon(
        painter = painterResource(if (tab.selected) tab.iconActive else tab.iconInactive),
        contentDescription = tab.label,
        tint = tint,
        modifier = Modifier.size(size.dp),
    )
}

// ---------------------------------------------------------------------------------------------
// Monolith: the bar is the thick bottom of the frame the page is cut into (concave corners join
// them); the tab under the active icon flows between icons like a drop.
// ---------------------------------------------------------------------------------------------

/** The concave corner radius where the frame meets the page. */
val MonolithCorner = 22.dp

@Composable
private fun MonolithNav(tabs: List<ShellTab>, pureBlack: Boolean, slim: Boolean, modifier: Modifier) {
    val band = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    val accent = MaterialTheme.colorScheme.primary
    val (l, r) = rememberFlowingEdges(selectedIndex(tabs))
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(band)
                // Concave corners above both ends: the page looks cut out of the same piece.
                val c = MonolithCorner.toPx()
                drawPath(concaveCorner(0f, 0f, c, left = true), band)
                drawPath(concaveCorner(size.width, 0f, c, left = false), band)
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .drawBehind {
                    if (tabs.isEmpty()) return@drawBehind
                    val slot = size.width / tabs.size
                    val pillH = 32.dp.toPx()
                    val inset = 14.dp.toPx()
                    val top = if (slim) (size.height - pillH) / 2 else 12.dp.toPx()
                    val left = slot * l + inset
                    val right = slot * (r + 1) - inset
                    drawRoundRect(accent.copy(alpha = 0.2f), Offset(left, top), Size(right - left, pillH), CornerRadius(pillH / 2))
                },
        ) {
            Row(Modifier.fillMaxSize()) {
                tabs.forEach { tab ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = if (slim) Arrangement.Center else Arrangement.Top,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .shellPress(tab.onClick, tab.onLongClick)
                            .padding(top = if (slim) 0.dp else 16.dp),
                    ) {
                        TabIcon(tab, if (tab.selected) accent else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!slim) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                tab.label,
                                fontSize = 12.sp,
                                fontWeight = if (tab.selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (tab.selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A concave corner of radius [r] standing on the point ([x], [y]) and rising above it: a square
 * with a quarter circle taken out, so a straight edge below flows into the rounded page above.
 */
fun concaveCorner(x: Float, y: Float, r: Float, left: Boolean, downward: Boolean = false): Path = Path().apply {
    val dir = if (left) 1f else -1f
    val v = if (downward) 1f else -1f
    moveTo(x, y)
    lineTo(x, y + v * r)
    // The quarter circle centred r into the page from the corner.
    val cx = x + dir * r
    val cy = y + v * r
    val steps = 12
    for (i in 0..steps) {
        val a = Math.PI / 2 * i / steps
        // From (x, cy) to (cx, y) along the circle around (cx, cy).
        val px = cx - dir * r * kotlin.math.cos(a).toFloat()
        val py = cy - v * r * kotlin.math.sin(a).toFloat()
        lineTo(px, py)
    }
    lineTo(x, y)
    close()
}

// ---------------------------------------------------------------------------------------------
// Glass: a floating frosted capsule; the tab indicator is a drop of glass that can be dragged
// along it and stretches as it goes; the capsule gives under the finger like jelly.
// ---------------------------------------------------------------------------------------------

@Composable
private fun GlassNav(tabs: List<ShellTab>, slim: Boolean, modifier: Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val tint = glassTint()
    val rim = glassRim()
    val accent = MaterialTheme.colorScheme.primary
    val content = MaterialTheme.colorScheme.onSurface
    val selected = selectedIndex(tabs)
    val scope = rememberCoroutineScope()
    // The drop: follows the selection, or the finger while dragged.
    val drop = remember { Animatable(selected.coerceAtLeast(0).toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(selected) {
        if (!dragging && selected >= 0) drop.animateTo(selected.toFloat(), if (ShellRuntime.calm) tween(160) else ShellMotion.jelly())
    }
    // Jelly: squashed toward the press, released with an overshoot.
    var squash by remember { mutableStateOf(false) }
    val sx by animateFloatAsState(if (squash) 1.035f else 1f, ShellMotion.jelly(), label = "sx")
    val sy by animateFloatAsState(if (squash) 0.95f else 1f, ShellMotion.jelly(), label = "sy")
    val height = if (slim) 56.dp else 62.dp
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars), contentAlignment = Alignment.BottomCenter) {
        GlassSurface(
            shape = CircleShape,
            tint = tint,
            rim = rim,
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                .fillMaxWidth()
                .height(height)
                .graphicsLayer {
                    scaleX = sx
                    scaleY = sy
                },
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
                val slot = if (tabs.isEmpty()) 1f else widthPx / tabs.size
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            if (tabs.isEmpty()) return@drawBehind
                            val v = drop.value
                            // Stretched by its speed: longer while it travels.
                            val stretch = 1f + (kotlin.math.abs(drop.velocity) * 0.06f).coerceAtMost(0.35f)
                            val w = (slot - 12.dp.toPx()) * stretch
                            val h = size.height - 12.dp.toPx()
                            val cx = slot * v + slot / 2
                            drawRoundRect(accent.copy(alpha = 0.22f), Offset(cx - w / 2, 6.dp.toPx()), Size(w, h), CornerRadius(h / 2))
                            drawRoundRect(Color.White.copy(alpha = if (dark) 0.06f else 0.35f), Offset(cx - w / 2, 6.dp.toPx()), Size(w, h / 2), CornerRadius(h / 2))
                        }
                        .pointerInput(tabs.size) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragging = true
                                    squash = true
                                },
                                onDragEnd = {
                                    dragging = false
                                    squash = false
                                    val target = drop.value.roundToInt().coerceIn(0, tabs.lastIndex)
                                    scope.launch { drop.animateTo(target.toFloat(), ShellMotion.jelly()) }
                                    if (target != selected) tabs[target].onClick()
                                },
                                onDragCancel = {
                                    dragging = false
                                    squash = false
                                },
                            ) { change, dx ->
                                change.consume()
                                scope.launch { drop.snapTo((drop.value + dx / slot).coerceIn(0f, tabs.lastIndex.toFloat())) }
                            }
                        },
                ) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        tabs.forEach { tab ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .shellPress(tab.onClick, tab.onLongClick, onPressed = { squash = it }),
                            ) {
                                TabIcon(tab, if (tab.selected) accent else content.copy(alpha = 0.78f))
                                if (!slim) {
                                    Text(tab.label, fontSize = 11.sp, color = if (tab.selected) accent else content.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Island: one dark capsule. With the tabs kept open it holds all of them; otherwise only the
// current one, and a tap opens the rest for a few seconds.
// ---------------------------------------------------------------------------------------------

/** The island's own colour: graphite in light mode, nearly black in dark. */
@Composable
fun islandColor(): Color = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color(0xFF1C1C1F) else Color(0xFF060607)

@Composable
private fun IslandNav(tabs: List<ShellTab>, modifier: Modifier) {
    val keepOpen by rememberPreference(IslandTabsOpenKey, defaultValue = true)
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) {
            delay(4_000)
            open = false
        }
    }
    val expanded = keepOpen || open
    val island = islandColor()
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars), contentAlignment = Alignment.BottomCenter) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            // The background takes the animated size: the capsule itself grows and shrinks, round at both ends.
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                .height(54.dp)
                .background(island, CircleShape)
                .animateContentSize(if (ShellRuntime.calm) tween(160) else ShellMotion.jelly())
                .then(if (expanded) Modifier.fillMaxWidth() else Modifier)
                .padding(horizontal = 6.dp),
        ) {
            tabs.forEach { tab ->
                AnimatedVisibility(
                    visible = expanded || tab.selected,
                    enter = fadeIn(tween(180, delayMillis = 60)) + expandHorizontally(ShellMotion.jelly()),
                    exit = fadeOut(tween(120)) + shrinkHorizontally(ShellMotion.soft()),
                    modifier = if (expanded) Modifier.weight(1f) else Modifier,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxHeight()
                            .then(if (expanded) Modifier.fillMaxWidth() else Modifier.width(72.dp))
                            .shellPress(
                                onClick = { if (!expanded) open = true else tab.onClick() },
                                onLongClick = tab.onLongClick,
                            ),
                    ) {
                        TabIcon(tab, Color.White.copy(alpha = if (tab.selected) 1f else 0.55f))
                        Spacer(Modifier.height(5.dp))
                        // The current tab: a small dot under it.
                        val dot by animateFloatAsState(if (tab.selected) 1f else 0f, ShellMotion.snappy(), label = "dot")
                        Box(
                            Modifier
                                .size(5.dp)
                                .graphicsLayer {
                                    scaleX = dot
                                    scaleY = dot
                                    alpha = dot
                                }
                                .background(Color.White, CircleShape),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Orbit: the bar is an arc (a crescent rising in the middle); the icons sit along it and a
// circle of the accent rolls to the current one.
// ---------------------------------------------------------------------------------------------

@Composable
private fun OrbitNav(tabs: List<ShellTab>, pureBlack: Boolean, modifier: Modifier) {
    val band = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    val accent = MaterialTheme.colorScheme.primary
    val selected = selectedIndex(tabs)
    val pos by animateFloatAsState(selected.coerceAtLeast(0).toFloat(), if (ShellRuntime.calm) tween(160) else ShellMotion.soft(), label = "orbit")
    val rise = 14.dp
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                // The arc: the edges low, the middle up by [rise].
                val top = rise.toPx()
                val path = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(0f, top + 8.dp.toPx())
                    quadraticTo(size.width / 2, -top + 8.dp.toPx(), size.width, top + 8.dp.toPx())
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(path, band)
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
            val density = LocalDensity.current
            val w = with(density) { maxWidth.toPx() }
            val n = tabs.size.coerceAtLeast(1)
            val slot = w / n
            fun lift(x: Float): Float {
                val d = (x - w / 2) / (w / 2)
                return (1f - d * d) * with(density) { (rise - 2.dp).toPx() }
            }
            // The accent circle rolling along the arc.
            Box(
                Modifier
                    .offset {
                        val cx = slot * pos + slot / 2
                        IntOffset((cx - 24.dp.toPx()).roundToInt(), (px(12) - lift(cx)).roundToInt())
                    }
                    .size(48.dp)
                    .background(accent.copy(alpha = 0.2f), CircleShape),
            )
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { i, tab ->
                    val cx = slot * i + slot / 2
                    Box(
                        contentAlignment = Alignment.TopCenter,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .shellPress(tab.onClick, tab.onLongClick)
                            .offset { IntOffset(0, (px(24) - lift(cx)).roundToInt()) },
                    ) {
                        TabIcon(tab, if (tab.selected) accent else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** [dp] in pixels, inside an offset block. */
private fun androidx.compose.ui.unit.Density.px(dp: Int): Float = dp.dp.toPx()

// ---------------------------------------------------------------------------------------------
// Paper: a flat strip with a hairline on top; the current tab underlined, the line moving
// straight across (no bounce).
// ---------------------------------------------------------------------------------------------

@Composable
private fun PaperNav(tabs: List<ShellTab>, slim: Boolean, modifier: Modifier) {
    val page = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val rule = MaterialTheme.colorScheme.outlineVariant
    val selected = selectedIndex(tabs)
    val pos by animateFloatAsState(selected.coerceAtLeast(0).toFloat(), tween(if (ShellRuntime.calm) 0 else 220), label = "underline")
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(page)
                drawLine(rule, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .drawBehind {
                    if (tabs.isEmpty()) return@drawBehind
                    val slot = size.width / tabs.size
                    val y = size.height - 10.dp.toPx()
                    val x = slot * pos
                    drawLine(ink, Offset(x + slot * 0.3f, y), Offset(x + slot * 0.7f, y), 2.dp.toPx())
                },
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { tab ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.weight(1f).fillMaxHeight().shellPress(tab.onClick, tab.onLongClick, pressedScale = 0.98f),
                    ) {
                        TabIcon(tab, if (tab.selected) ink else ink.copy(alpha = 0.55f), size = 22)
                        if (!slim) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (tab.selected) ink else ink.copy(alpha = 0.55f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
