/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme.shell

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Frosted glass for the Glass theme: what is under a panel shows through it blurred, the way iOS
 * 26 does it. The pages ([backdropSource]) are recorded into one layer as they draw; each glass
 * panel ([GlassSurface]) draws that layer, shifted to where it sits, through a blur, clipped to
 * its shape.
 *
 * Android 12+ only (RenderEffect); before that the glass is a dense see-through tint with the
 * same highlight — never broken, just less glassy. Nothing is recorded in the other themes.
 */
object Backdrop {
    /** The pages as last drawn; null outside the Glass theme. */
    var layer: GraphicsLayer? by mutableStateOf(null)
        internal set

    /** Where the pages sit on screen. */
    var origin: Offset by mutableStateOf(Offset.Zero)
        internal set

    val blurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

/** Put on the box holding the pages: in the Glass theme they are recorded for the glass panels. */
@Composable
fun Modifier.backdropSource(): Modifier {
    val on = LocalShell.current == ShellTheme.GLASS && Backdrop.blurSupported
    androidx.compose.runtime.DisposableEffect(on) {
        onDispose { if (on) Backdrop.layer = null }
    }
    if (!on) return this
    val layer = rememberGraphicsLayer()
    androidx.compose.runtime.SideEffect { if (Backdrop.layer !== layer) Backdrop.layer = layer }
    return this
        .onGloballyPositioned { Backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
}

/**
 * A glass panel: the blurred backdrop, a [tint] over it, a highlight along the top edge and a
 * hairline rim; [content] on top.
 */
@Composable
fun GlassSurface(
    shape: Shape,
    tint: Color,
    rim: Color,
    modifier: Modifier = Modifier,
    blur: Dp = 28.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.clip(shape)) {
        GlassFill(Modifier.matchParentSize(), tint, rim, shape, blur)
        content()
    }
}

/** The tint of glass over light or dark pages. */
@Composable
fun glassTint(): Color =
    if (androidx.compose.material3.MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF15161A).copy(alpha = 0.46f) else Color.White.copy(alpha = 0.5f)

@Composable
fun glassRim(): Color =
    if (androidx.compose.material3.MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.7f)

/**
 * The glass itself, filling its parent (which clips it to its shape): what is under it blurred,
 * the tint, the light on the top edge, the rim.
 */
@Composable
fun GlassFill(
    modifier: Modifier = Modifier,
    tint: Color = glassTint(),
    rim: Color = glassRim(),
    shape: Shape? = null,
    blur: Dp = 28.dp,
) {
    var here by androidx.compose.runtime.remember { mutableStateOf(Offset.Zero) }
    val layer = Backdrop.layer
    Box(modifier.onGloballyPositioned { here = it.positionInRoot() }) {
        if (layer != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val r = blur.toPx()
                        renderEffect = BlurEffect(r, r, TileMode.Clamp)
                        clip = true
                    }
                    .drawBehind {
                        val shift = Backdrop.origin - here
                        translate(shift.x, shift.y) { drawLayer(layer) }
                    },
            )
        }
        // Without the blur the tint carries the panel alone: denser.
        Box(Modifier.matchParentSize().background(if (layer != null) tint else tint.copy(alpha = (tint.alpha + 0.3f).coerceAtMost(0.94f))))
        // The light catching the top edge.
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0f to rim.copy(alpha = (rim.alpha * 1.4f).coerceAtMost(1f)), 0.45f to Color.Transparent)),
        )
        if (shape != null) Box(Modifier.matchParentSize().border(1.dp, rim, shape))
    }
}
