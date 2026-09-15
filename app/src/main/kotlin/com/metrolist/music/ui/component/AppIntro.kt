/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Played once per process: rotating the screen or reopening the activity doesn't replay it. */
object AppIntroState {
    @Volatile
    var played = false
}

private val MetalMania = FontFamily(Font(R.font.metal_mania))

private const val ICON_MS = 550
private const val LETTERS_START_MS = 420L
private const val LETTER_STAGGER_MS = 55L
private const val LETTER_FADE_MS = 380
private const val MIN_TOTAL_MS = 1_700L
private const val MAX_TOTAL_MS = 3_000L
private const val EXIT_MS = 350

/**
 * Launch intro drawn over the app while home and library load underneath: the app icon fades in,
 * then the name's letters fade in one after another (slightly staggered, in Metal Mania), and a
 * shimmer runs across the name until the content is ready (or [MAX_TOTAL_MS] passes).
 */
@Composable
fun AppIntro(
    background: Color,
    awaitReady: suspend () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val name = stringResource(R.string.app_name)
    val iconSizePx = with(density) { 112.dp.roundToPx() }
    val icon = remember {
        runCatching {
            ResourcesCompat.getDrawable(context.resources, R.mipmap.ic_launcher, context.theme)
                ?.toBitmap(iconSizePx, iconSizePx)?.asImageBitmap()
        }.getOrNull()
    }

    val iconProgress = remember { Animatable(0f) }
    val letters = remember(name) { name.map { Animatable(0f) } }
    val overlayAlpha = remember { Animatable(1f) }
    var shimmerOn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        launch { iconProgress.animateTo(1f, tween(ICON_MS, easing = FastOutSlowInEasing)) }
        letters.forEachIndexed { i, anim ->
            launch {
                delay(LETTERS_START_MS + i * LETTER_STAGGER_MS)
                anim.animateTo(1f, tween(LETTER_FADE_MS, easing = FastOutSlowInEasing))
            }
        }
        val lettersDone = LETTERS_START_MS + (letters.size - 1).coerceAtLeast(0) * LETTER_STAGGER_MS + LETTER_FADE_MS
        launch {
            delay(lettersDone)
            shimmerOn = true
        }
        // Content loads in the background from the very first frame; wait for it within the budget.
        withTimeoutOrNull(MAX_TOTAL_MS) { awaitReady() }
        val elapsed = System.currentTimeMillis() - start
        if (elapsed < MIN_TOTAL_MS) delay(MIN_TOTAL_MS - elapsed)
        overlayAlpha.animateTo(0f, tween(EXIT_MS, easing = LinearEasing))
        onFinished()
    }

    val shimmerTransition = rememberInfiniteTransition(label = "introShimmer")
    val shimmerX by shimmerTransition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
        label = "introShimmerX",
    )

    val textColor = MaterialTheme.colorScheme.onSurface
    val nameStyle = TextStyle(fontFamily = MetalMania, fontSize = 46.sp, letterSpacing = 1.sp)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlayAlpha.value }
            .background(background)
            // Swallow touches so nothing underneath reacts while the intro is visible.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(112.dp)
                        .graphicsLayer {
                            val p = iconProgress.value
                            alpha = p
                            val scale = 0.82f + 0.18f * p
                            scaleX = scale
                            scaleY = scale
                        },
                )
            }
            Spacer(Modifier.height(20.dp))
            Box {
                // Base letters.
                IntroLetters(name, letters, nameStyle, textColor.copy(alpha = 0.72f))
                // Highlight copy, visible only inside the moving band.
                if (shimmerOn) {
                    IntroLetters(
                        name, letters, nameStyle, textColor,
                        modifier = Modifier
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                val band = size.width * 0.35f
                                val x = size.width * shimmerX
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
}

@Composable
private fun IntroLetters(
    name: String,
    letters: List<Animatable<Float, *>>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val rise = with(density) { 8.dp.toPx() }
    Row(horizontalArrangement = Arrangement.Center, modifier = modifier) {
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
