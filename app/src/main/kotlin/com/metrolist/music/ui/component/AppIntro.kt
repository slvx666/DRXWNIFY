/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import com.metrolist.music.R
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Played once per process: rotating the screen or reopening the activity doesn't replay it. */
object AppIntroState {
    @Volatile
    var played = false
}

private val MetalMania = FontFamily(Font(R.font.metal_mania))

private val IconSize = 168.dp
private const val ICON_MS = 750
private const val LETTERS_START_MS = 550L
private const val LETTER_STAGGER_MS = 90L
private const val LETTER_FADE_MS = 650
private const val SHIMMER_MS = 1_000
/** After the shimmer pass, wait at most this long for home/library before opening anyway. */
private const val READY_GRACE_MS = 1_000L
/** Cap on waiting for the main thread to settle (first composition of the app) before animating. */
private const val SETTLE_MAX_MS = 900L
private const val EXIT_MS = 650

/** Decelerating curve so letters glide into place instead of stopping abruptly. */
private val RiseEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/**
 * Launch intro drawn over the app while home and library load underneath.
 *
 * The screen stays black until the main thread settles (the app's own first composition happens
 * then, not during the animation), then: the icon fades/scales in, the name's letters rise from
 * below and fade in one after another (Metal Mania), a single shimmer passes over the name, and the
 * app cross-fades in — as soon as the content is ready, or at most [READY_GRACE_MS] after the shimmer.
 *
 * @param exitProgress 0 → 1 while the intro fades out; the host fades its content in with it.
 */
@Composable
fun AppIntro(
    exitProgress: Animatable<Float, AnimationVector1D>,
    awaitReady: suspend () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val name = stringResource(R.string.app_name)
    val iconSizePx = with(density) { IconSize.roundToPx() }
    val icon = remember {
        runCatching {
            ResourcesCompat.getDrawable(context.resources, R.mipmap.ic_launcher, context.theme)
                ?.toBitmap(iconSizePx, iconSizePx)?.asImageBitmap()
        }.getOrNull()
    }

    val iconProgress = remember { Animatable(0f) }
    val letters = remember(name) { name.map { Animatable(0f) } }
    // -1 = shimmer not running; otherwise the band position across the name (0..1 plus overshoot).
    val shimmer = remember { Animatable(-1f) }

    LaunchedEffect(Unit) {
        // Content starts loading right away, in parallel with everything below.
        val ready = async { awaitReady() }

        // Hold on black until frames come in on time, so the animation itself runs smoothly.
        withTimeoutOrNull(SETTLE_MAX_MS) {
            var last = withFrameNanos { it }
            var smooth = 0
            while (smooth < 4) {
                val now = withFrameNanos { it }
                smooth = if (now - last <= 22_000_000L) smooth + 1 else 0
                last = now
            }
        }

        launch { iconProgress.animateTo(1f, tween(ICON_MS, easing = FastOutSlowInEasing)) }
        val letterJobs = letters.mapIndexed { i, anim ->
            launch {
                delay(LETTERS_START_MS + i * LETTER_STAGGER_MS)
                anim.animateTo(1f, tween(LETTER_FADE_MS, easing = RiseEasing))
            }
        }
        letterJobs.forEach { it.join() }

        // One shimmer pass, never repeated.
        shimmer.snapTo(-0.4f)
        shimmer.animateTo(1.4f, tween(SHIMMER_MS, easing = LinearEasing))
        shimmer.snapTo(-1f)

        withTimeoutOrNull(READY_GRACE_MS) { ready.await() }
        exitProgress.animateTo(1f, tween(EXIT_MS, easing = FastOutSlowInEasing))
        onFinished()
    }

    val nameStyle = remember { TextStyle(fontFamily = MetalMania, fontSize = 48.sp, letterSpacing = 1.sp) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - exitProgress.value }
            .background(Color.Black)
            // Swallow touches so nothing underneath reacts while the intro is visible.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(IconSize)
                        .graphicsLayer {
                            val p = iconProgress.value
                            alpha = p
                            val scale = 0.85f + 0.15f * p
                            scaleX = scale
                            scaleY = scale
                        },
                )
            }
            Spacer(Modifier.height(18.dp))
            Box {
                IntroLetters(name, letters, nameStyle, Color.White.copy(alpha = 0.78f))
                // Highlight copy, visible only inside the moving band.
                IntroLetters(
                    name, letters, nameStyle, Color.White,
                    modifier = Modifier
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                            alpha = if (shimmer.value < -0.5f) 0f else 1f
                        }
                        .drawWithContent {
                            val position = shimmer.value
                            if (position < -0.5f) return@drawWithContent
                            drawContent()
                            val band = size.width * 0.3f
                            val x = size.width * position
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(Color.Transparent, Color.Black, Color.Transparent),
                                    start = Offset(x - band, 0f),
                                    end = Offset(x + band, size.height),
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                )
            }
        }
    }
}

@Composable
private fun IntroLetters(
    name: String,
    letters: List<Animatable<Float, AnimationVector1D>>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val rise = with(LocalDensity.current) { 42.dp.toPx() }
    Row(modifier = modifier) {
        name.forEachIndexed { i, ch ->
            Text(
                text = ch.toString(),
                style = style,
                color = color,
                modifier = Modifier.graphicsLayer {
                    val p = letters[i].value
                    alpha = p
                    translationY = (1f - p) * rise
                },
            )
        }
    }
}
