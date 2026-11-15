/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.launch

/**
 * Over the pages, Monolith's frame: the strip behind the status bar is the top of the same piece
 * as the navigation bar, and the page under it starts with concave corners — the page looks cut
 * out of one block. Draws nothing in the other themes; takes no touches.
 */
@Composable
fun ShellPageFrame(navController: androidx.navigation.NavController, tabRoutes: List<String>) {
    // Pulse: the music's clock and the pages' breathing live here too.
    com.metrolist.music.ui.theme.shell.pulse.PulseDriver()
    com.metrolist.music.ui.theme.shell.pulse.PulseBreath()
    // Stack: the handle that pulls the top card away.
    StackHandle(navController, tabRoutes)
    if (LocalShell.current != ShellTheme.MONOLITH) return
    val surface = MaterialTheme.colorScheme.surface
    val band = if (surface == Color.Black) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    val top = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    Canvas(Modifier.fillMaxSize()) {
        drawRect(band, Offset.Zero, Size(size.width, top))
        val r = MonolithCorner.toPx()
        drawPath(concaveCorner(0f, top, r, left = true, downward = true), band)
        drawPath(concaveCorner(size.width, top, r, left = false, downward = true), band)
    }
}

/** Stack: how far the top card is pulled down by its handle (px); 0 at rest. */
object StackDrag {
    var offset by androidx.compose.runtime.mutableFloatStateOf(0f)
}

/**
 * Stack's handle: a short bar under the status bar on every page that has one under it (not the
 * main tabs). Pull it down and the card follows the finger; let go past a third of the screen
 * (or with a quick flick) and it goes — back to the card under it; otherwise it springs back.
 */
@Composable
private fun StackHandle(navController: androidx.navigation.NavController, tabRoutes: List<String>) {
    if (LocalShell.current != ShellTheme.STACK) return
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val canPop = route != null && route !in tabRoutes && navController.previousBackStackEntry != null
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { StackDrag.offset = 0f } }
    if (!canPop) return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val anim = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density)
    val handle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)
    // The card underneath shows above this one: a darker strip behind the status bar, and this
    // card's top corners rounded into it.
    val under = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surface, Color.Black, 0.28f)
    Canvas(Modifier.fillMaxSize().graphicsLayer { translationY = StackDrag.offset }) {
        val y = top.toFloat()
        drawRect(under, Offset(0f, -StackDrag.offset), Size(size.width, y + StackDrag.offset))
        val r = 24.dp.toPx()
        drawPath(concaveCorner(0f, y, r, left = true, downward = true), under)
        drawPath(concaveCorner(size.width, y, r, left = false, downward = true), under)
    }
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxSize()
            .wrapContentSize(androidx.compose.ui.Alignment.TopCenter),
    ) {
        androidx.compose.foundation.layout.Box(
            contentAlignment = androidx.compose.ui.Alignment.Center,
            modifier = Modifier
                .padding(top = with(density) { top.toDp() })
                .size(width = 96.dp, height = 22.dp)
                .graphicsLayer { translationY = StackDrag.offset }
                .pointerInput(route) {
                    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onDragEnd = {
                            val v = tracker.calculateVelocity().y
                            val far = StackDrag.offset > (navController.context.resources.displayMetrics.heightPixels / 3f)
                            scope.launch {
                                anim.snapTo(StackDrag.offset)
                                if (far || v > 1_800f) {
                                    navController.popBackStack()
                                    anim.animateTo(0f, ShellMotion.soft()) { StackDrag.offset = value }
                                } else {
                                    anim.animateTo(0f, ShellMotion.jelly()) { StackDrag.offset = value }
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                anim.snapTo(StackDrag.offset)
                                anim.animateTo(0f, ShellMotion.jelly()) { StackDrag.offset = value }
                            }
                        },
                    ) { change, dy ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        StackDrag.offset = (StackDrag.offset + dy).coerceAtLeast(0f)
                    }
                },
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(width = 40.dp, height = 4.dp)
                    .background(handle, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
            )
        }
    }
}
