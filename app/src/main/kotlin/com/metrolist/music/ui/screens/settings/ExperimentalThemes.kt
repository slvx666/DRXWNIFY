/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.constants.DarkModeKey
import com.metrolist.music.constants.ExperimentalThemeKey
import com.metrolist.music.constants.IslandTabsOpenKey
import com.metrolist.music.constants.PulseStrengthKey
import com.metrolist.music.ui.theme.shell.ShellGuard
import com.metrolist.music.ui.theme.shell.ShellTheme
import com.metrolist.music.ui.theme.shell.systemReducesMotion
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The bottom of Settings → Theme and colours: the experimental themes, each with a small live scene
 * of how it looks and moves. A tap switches the theme on at once; the picked one off again.
 */
@Composable
fun ExperimentalThemesSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val (picked, onPick) = rememberEnumPreference(ExperimentalThemeKey, ShellTheme.NONE)
    val darkMode by rememberEnumPreference(DarkModeKey, DarkMode.AUTO)
    val dark = when (darkMode) {
        DarkMode.AUTO -> isSystemInDarkTheme()
        DarkMode.ON -> true
        DarkMode.OFF -> false
    }
    var blocked by remember { mutableStateOf(ShellGuard.blockedTheme()) }
    val animate = remember { !systemReducesMotion(context) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.shell_section_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.shell_section_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val themes = ShellTheme.entries.filter { it != ShellTheme.NONE }
            themes.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { theme ->
                        ShellCard(
                            theme = theme,
                            dark = dark,
                            animate = animate,
                            selected = theme == picked,
                            crashed = theme == blocked,
                            onClick = {
                                if (theme == blocked) {
                                    ShellGuard.unblock(context)
                                    blocked = null
                                }
                                onPick(if (theme == picked) ShellTheme.NONE else theme)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (picked != ShellTheme.NONE) {
                Text(stringResource(picked.descriptionRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The two themes with a choice of their own.
            when (picked) {
                ShellTheme.PULSE -> {
                    val (strength, onStrength) = rememberPreference(PulseStrengthKey, defaultValue = 0)
                    Text(stringResource(R.string.shell_pulse_strength), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(R.string.shell_pulse_weak, R.string.shell_pulse_medium, R.string.shell_pulse_strong).forEachIndexed { i, res ->
                            FilterChip(selected = strength == i, onClick = { onStrength(i) }, label = { Text(stringResource(res)) })
                        }
                    }
                }
                ShellTheme.ISLAND -> {
                    val (open, onOpen) = rememberPreference(IslandTabsOpenKey, defaultValue = true)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(!open) }.padding(vertical = 4.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.shell_island_tabs), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.shell_island_tabs_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        com.metrolist.music.ui.theme.ThemedSwitch(checked = open, onCheckedChange = onOpen)
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun ShellCard(
    theme: ShellTheme,
    dark: Boolean,
    animate: Boolean,
    selected: Boolean,
    crashed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border by animateDpAsState(if (selected) 3.dp else 1.dp, label = "shellBorder")
    val accent = MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(border, if (selected) accent else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                .clickable(onClick = onClick),
        ) {
            // The scene in the theme's own palette (Paper's ink and paper; the others the app's).
            com.metrolist.music.ui.theme.MetrolistTheme(
                darkTheme = dark,
                themeColor = MaterialTheme.colorScheme.primary,
                shell = theme,
                animations = false,
            ) {
                ShellScene(theme, animate, Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
            }
            if (selected) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(accent, CircleShape),
                ) {
                    Icon(painterResource(R.drawable.check), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                }
            }
        }
        Text(
            stringResource(theme.nameRes),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (crashed) {
            Text(
                stringResource(R.string.shell_crashed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
            )
        }
    }
}

/**
 * A few seconds of the theme on a loop: how its navigation, mini player or cover moves. Drawn on
 * one canvas from one clock (no recomposition per frame).
 */
@Composable
private fun ShellScene(theme: ShellTheme, animate: Boolean, modifier: Modifier) {
    val clock = if (animate) {
        rememberInfiniteTransition(label = "shellScene").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing), RepeatMode.Restart),
            label = "t",
        )
    } else {
        remember { mutableStateOf(0.3f) }
    }
    val c = MaterialTheme.colorScheme
    Canvas(modifier) {
        val t = clock.value
        when (theme) {
            ShellTheme.MONOLITH -> monolithScene(t, c.surfaceContainerHigh, c.surface, c.primary, c.onSurfaceVariant)
            ShellTheme.GLASS -> glassScene(t, c.surface, c.primary, c.onSurfaceVariant, c.onSurface)
            ShellTheme.ISLAND -> islandScene(t, c.onSurfaceVariant, c.surface, c.primary)
            ShellTheme.PULSE -> pulseScene(t, c.primary, c.surfaceContainerHighest, c.onSurfaceVariant)
            ShellTheme.STACK -> stackScene(t, c.surfaceContainerHigh, c.surfaceContainerHighest, c.onSurfaceVariant, c.primary)
            ShellTheme.ORBIT -> orbitScene(t, c.primary, c.surfaceContainerHighest, c.onSurfaceVariant)
            ShellTheme.PAPER -> paperScene(t, c.onSurface, c.outlineVariant)
            ShellTheme.NONE -> Unit
        }
    }
}

/** 0→1→0 smoothly over the loop, [phase] shifted. */
private fun wave(t: Float, phase: Float = 0f) = (0.5f - 0.5f * kotlin.math.cos(2f * PI.toFloat() * (t + phase)))

private fun ease(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3 - 2 * c)
}

private fun DrawScope.contentLines(color: Color, top: Float, alpha: Float = 1f) {
    val w = size.width
    for (i in 0 until 4) {
        val y = top + i * 16.dp.toPx()
        drawRoundRect(color.copy(alpha = 0.35f * alpha), Offset(12.dp.toPx(), y), Size(w * (0.7f - i * 0.1f), 6.dp.toPx()), CornerRadius(3.dp.toPx()))
    }
}

private fun DrawScope.monolithScene(t: Float, frame: Color, page: Color, accent: Color, ink: Color) {
    val w = size.width
    val h = size.height
    val band = 30.dp.toPx()
    val r = 14.dp.toPx()
    // The frame everything grows out of, the page cut into it with rounded corners.
    drawRect(frame)
    drawRoundRect(page, Offset(4.dp.toPx(), 4.dp.toPx()), Size(w - 8.dp.toPx(), h - band - 4.dp.toPx()), CornerRadius(r))
    contentLines(ink, 16.dp.toPx())
    // The tab under the active icon flows to the next one like a drop.
    val slots = 4
    val step = t * slots
    val from = step.toInt() % slots
    val p = ease((step - step.toInt()) * 1.6f)
    val slotW = w / slots
    val x0 = slotW * from
    val x1 = slotW * ((from + 1) % slots)
    val left = if (x1 > x0) x0 + (x1 - x0) * ease(p * 1.3f - 0.3f) else x0 * (1 - p)
    val right = if (x1 > x0) x0 + slotW + (x1 - x0) * ease(p * 1.3f) else (x0 + slotW) * (1 - p) + slotW * p
    drawRoundRect(accent.copy(alpha = 0.22f), Offset(left + 6.dp.toPx(), h - band + 6.dp.toPx()), Size((right - left) - 12.dp.toPx(), band - 12.dp.toPx()), CornerRadius(9.dp.toPx()))
    for (i in 0 until slots) {
        drawCircle(if (i == from) accent else ink.copy(alpha = 0.6f), 4.dp.toPx(), Offset(slotW * i + slotW / 2, h - band / 2))
    }
}

private fun DrawScope.glassScene(t: Float, page: Color, accent: Color, ink: Color, on: Color) {
    val w = size.width
    val h = size.height
    // Content scrolls under the glass.
    val shift = (t * 64.dp.toPx())
    for (i in -1 until 6) {
        val y = i * 32.dp.toPx() - shift % 32.dp.toPx()
        drawRoundRect(if (i % 2 == 0) accent.copy(alpha = 0.5f) else ink.copy(alpha = 0.3f), Offset(10.dp.toPx(), y), Size(w - 20.dp.toPx(), 22.dp.toPx()), CornerRadius(8.dp.toPx()))
    }
    // The floating capsule: frosted, a highlight on its top edge, squashed a little when "touched".
    val press = (1f - abs(t - 0.5f) * 6f).coerceIn(0f, 1f)
    val cw = (w - 28.dp.toPx()) * (1f + 0.04f * press)
    val ch = 30.dp.toPx() * (1f - 0.05f * press)
    val left = (w - cw) / 2
    val top = h - ch - 10.dp.toPx()
    drawRoundRect(page.copy(alpha = 0.72f), Offset(left, top), Size(cw, ch), CornerRadius(ch / 2))
    drawRoundRect(
        Brush.verticalGradient(listOf(on.copy(alpha = 0.22f), Color.Transparent), startY = top, endY = top + ch * 0.6f),
        Offset(left, top), Size(cw, ch), CornerRadius(ch / 2),
    )
    drawRoundRect(on.copy(alpha = 0.18f), Offset(left, top), Size(cw, ch), CornerRadius(ch / 2), style = Stroke(1.dp.toPx()))
    // The tab indicator: a drop of glass that stretches as it moves.
    val pos = ease(wave(t, 0.25f))
    val dropW = 34.dp.toPx() * (1f + 0.35f * (1f - abs(pos - 0.5f) * 2f))
    val cx = left + 24.dp.toPx() + (cw - 48.dp.toPx()) * pos
    drawRoundRect(accent.copy(alpha = 0.28f), Offset(cx - dropW / 2, top + 4.dp.toPx()), Size(dropW, ch - 8.dp.toPx()), CornerRadius((ch - 8.dp.toPx()) / 2))
}

private fun DrawScope.islandScene(t: Float, ink: Color, page: Color, accent: Color) {
    val w = size.width
    val h = size.height
    contentLines(ink, 14.dp.toPx())
    // One capsule changes shape: mini player → a note ("added to likes") → back.
    val grow = ease(((t - 0.35f) / 0.15f)) * (1f - ease((t - 0.7f) / 0.15f))
    val cw = (w * 0.62f) + (w * 0.26f) * grow
    val ch = 28.dp.toPx() + 18.dp.toPx() * grow
    val left = (w - cw) / 2
    val top = h - ch - 12.dp.toPx()
    val island = if (page.luminance() > 0.5f) Color(0xFF1B1B1D) else Color(0xFF050506)
    drawRoundRect(island, Offset(left, top), Size(cw, ch), CornerRadius(ch / 2))
    // Inside: the cover dot and a line of text, or the note's icon.
    drawCircle(accent, 8.dp.toPx(), Offset(left + ch / 2, top + ch / 2))
    drawRoundRect(Color.White.copy(alpha = 0.7f * (1f - grow) + 0.9f * grow), Offset(left + ch, top + ch / 2 - 3.dp.toPx()), Size(cw * 0.45f, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private fun DrawScope.pulseScene(t: Float, accent: Color, cover: Color, ink: Color) {
    val w = size.width
    val h = size.height
    val center = Offset(w / 2, h * 0.42f)
    // Two beats a second: a fast rise, a slow fall.
    val beatT = (t * 8f) % 1f
    val beat = if (beatT < 0.12f) beatT / 0.12f else (1f - (beatT - 0.12f) / 0.88f).let { it * it }
    val radius = 30.dp.toPx() * (1f + 0.025f * beat)
    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.10f + 0.25f * beat), Color.Transparent), center, radius * 2.1f), radius * 2.1f, center)
    drawRoundRect(cover, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), CornerRadius(10.dp.toPx()))
    // The line over the mini player follows the loudness.
    val y = h - 24.dp.toPx()
    drawRoundRect(ink.copy(alpha = 0.15f), Offset(10.dp.toPx(), y), Size(w - 20.dp.toPx(), 18.dp.toPx()), CornerRadius(9.dp.toPx()))
    drawLine(accent.copy(alpha = 0.4f + 0.6f * beat), Offset(20.dp.toPx(), y), Offset(w - 20.dp.toPx(), y), strokeWidth = (1f + 1.5f * beat) * 1.dp.toPx(), cap = StrokeCap.Round)
}

private fun DrawScope.stackScene(t: Float, back: Color, front: Color, ink: Color, accent: Color) {
    val w = size.width
    val h = size.height
    // A card comes up from below; the one under it steps back (smaller, darker).
    val p = ease((t % 0.5f) / 0.32f)
    val scale = 1f - 0.06f * p
    val bw = w * scale
    val bTop = 10.dp.toPx() - 8.dp.toPx() * p
    drawRoundRect(back, Offset((w - bw) / 2, bTop), Size(bw, h), CornerRadius(14.dp.toPx()))
    drawRoundRect(Color.Black.copy(alpha = 0.25f * p), Offset((w - bw) / 2, bTop), Size(bw, h), CornerRadius(14.dp.toPx()))
    val top = h - (h - 22.dp.toPx()) * p
    drawRoundRect(front, Offset(0f, top), Size(w, h), CornerRadius(14.dp.toPx()))
    drawRoundRect(accent.copy(alpha = 0.6f), Offset(12.dp.toPx(), top + 12.dp.toPx()), Size(36.dp.toPx(), 36.dp.toPx()), CornerRadius(6.dp.toPx()))
    contentLines(ink, top + 58.dp.toPx())
}

private fun DrawScope.orbitScene(t: Float, accent: Color, cover: Color, ink: Color) {
    val w = size.width
    val h = size.height
    val center = Offset(w / 2, h * 0.45f)
    val r = minOf(w, h) * 0.3f
    // The cover turns slowly; the ring shows how far the track has gone.
    rotate(t * 360f, center) {
        drawCircle(cover, r, center)
        drawCircle(ink.copy(alpha = 0.35f), r * 0.18f, center)
        drawLine(ink.copy(alpha = 0.25f), Offset(center.x, center.y - r * 0.85f), Offset(center.x, center.y - r * 0.4f), 2.dp.toPx(), cap = StrokeCap.Round)
    }
    val ring = r + 9.dp.toPx()
    drawCircle(ink.copy(alpha = 0.2f), ring, center, style = Stroke(3.dp.toPx()))
    val sweep = 360f * ((t * 1.2f) % 1f)
    drawArc(accent, -90f, sweep, false, Offset(center.x - ring, center.y - ring), Size(ring * 2, ring * 2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    val a = Math.toRadians((sweep - 90f).toDouble())
    drawCircle(accent, 5.dp.toPx(), Offset(center.x + ring * kotlin.math.cos(a).toFloat(), center.y + ring * sin(a).toFloat()))
    // The navigation as an arc.
    val y = h - 14.dp.toPx()
    for (i in 0 until 4) {
        val x = w * (0.2f + i * 0.2f)
        val dy = 8.dp.toPx() * (1f - ((x - w / 2) / (w * 0.35f)).let { it * it })
        drawCircle(if (i == 1) accent else ink.copy(alpha = 0.6f), 4.dp.toPx(), Offset(x, y - dy))
    }
}

private fun DrawScope.paperScene(t: Float, ink: Color, rule: Color) {
    val w = size.width
    // Pages turn as a quick fade, nothing slides.
    val page = (t * 2f).toInt()
    val within = (t * 2f) % 1f
    val alpha = if (within < 0.1f) within / 0.1f else 1f
    drawRect(ink, Offset(12.dp.toPx(), 14.dp.toPx()), Size(w * 0.45f, 9.dp.toPx()), alpha = alpha)
    for (i in 0 until 6) {
        val y = 34.dp.toPx() + i * 13.dp.toPx()
        val len = w * (0.85f - ((i + page) % 3) * 0.12f)
        drawRect(ink.copy(alpha = 0.55f), Offset(12.dp.toPx(), y), Size(len - 24.dp.toPx(), 4.dp.toPx()), alpha = alpha)
    }
    drawLine(rule, Offset(0f, size.height - 26.dp.toPx()), Offset(w, size.height - 26.dp.toPx()), 1.dp.toPx())
    val slot = w / 4
    drawLine(ink, Offset(slot * (page % 4) + slot * 0.3f, size.height - 8.dp.toPx()), Offset(slot * (page % 4) + slot * 0.7f, size.height - 8.dp.toPx()), 2.dp.toPx())
}
