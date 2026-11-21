package com.metrolist.music.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.rememberLikeSyncs
import com.metrolist.music.playback.rememberTrackLiked
import com.metrolist.music.ui.theme.GothicFace
import com.metrolist.music.ui.theme.GothicThemeConfig
import com.metrolist.music.utils.makeTimeString

/**
 * Custom player controls for the GOTHIC Theme:
 * - Centered Track Title in Gothic Face (theme_gothic.ttf)
 * - Centered Artist in medieval Serif font
 * - Slender Gothic Cross like button on the right
 * - Clean progress bar with gothic cross / diamond thumb
 * - Gothic arcade-framed main playback buttons
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GothicPlayerControls(
    mediaMetadata: MediaMetadata,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    playbackState: Int,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    shuffleModeEnabled: Boolean,
    repeatMode: Int,
    isListenTogetherGuest: Boolean,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekPrevious: () -> Unit,
    onSeekNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onTitleClick: () -> Unit,
    onArtistClick: () -> Unit,
    onTitleLongClick: () -> Unit,
    onArtistLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    val primaryText = GothicThemeConfig.getPrimaryText(isDark)
    val secondaryText = GothicThemeConfig.getSecondaryText(isDark)
    val mutedText = GothicThemeConfig.getMutedText(isDark)
    val accent = GothicThemeConfig.getAccent(isDark)

    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val isLiked = rememberTrackLiked(database, mediaMetadata.id)
    val likeSyncs = rememberLikeSyncs(database, mediaMetadata.id)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        // ── 1. Track Title in Gothic Face & Artist in Serif with Gothic Cross Like Button ──
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 44.dp),
            ) {
                Text(
                    text = mediaMetadata.title,
                    style = TextStyle(
                        fontFamily = GothicFace,
                        fontSize = 26.sp,
                        color = primaryText,
                        textAlign = TextAlign.Center,
                        letterSpacing = 0.5.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp)
                        .combinedClickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onTitleClick,
                            onLongClick = onTitleLongClick,
                        ),
                )

                Spacer(Modifier.height(7.dp))

                Text(
                    text = mediaMetadata.artists.joinToString { it.name }.ifEmpty { "—" },
                    style = TextStyle(
                        fontFamily = GothicThemeConfig.SerifFont,
                        fontSize = 18.sp,
                        color = secondaryText,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onArtistClick,
                            onLongClick = onArtistLongClick,
                        ),
                )
            }

            // Gothic Cross Like Button on the right of track title
            if (playerConnection != null) {
                IconButton(
                    onClick = { playerConnection.service.setCurrentFavorite(!isLiked) },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(44.dp),
                ) {
                    val crossIcon = if (isLiked) R.drawable.theme_cross_filled else R.drawable.theme_cross_outline
                    val crossTint = if (isLiked) (if (isDark) Color.White else primaryText) else mutedText
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(crossIcon),
                        contentDescription = "Favorite",
                        tint = crossTint,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── 2. Progress Bar ─────────────────────────────────────────────────────────
        var dragFrac by remember { mutableFloatStateOf(-1f) }
        val currentFrac = if (dragFrac in 0f..1f) dragFrac
        else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f)
        else 0f

        val effectivePos = if (dragFrac in 0f..1f) (duration * dragFrac).toLong() else position

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Current position
            Text(
                text = makeTimeString(effectivePos).ifEmpty { "0:00" },
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = GothicThemeConfig.SerifFont,
                    color = secondaryText,
                    fontSize = 13.sp,
                ),
                modifier = Modifier.width(42.dp),
            )

            // Progress Slider Track
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .padding(horizontal = 4.dp)
                    .pointerInput(duration, isListenTogetherGuest) {
                        if (isListenTogetherGuest || duration <= 0) return@pointerInput
                        detectTapGestures { offset ->
                            val f = (offset.x / size.width).coerceIn(0f, 1f)
                            onSeek((duration * f).toLong())
                        }
                    }
                    .pointerInput(duration, isListenTogetherGuest) {
                        if (isListenTogetherGuest || duration <= 0) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                dragFrac = (offset.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                if (dragFrac in 0f..1f) {
                                    onSeek((duration * dragFrac).toLong())
                                }
                                dragFrac = -1f
                            },
                            onDragCancel = {
                                dragFrac = -1f
                            },
                            onHorizontalDrag = { change, _ ->
                                dragFrac = (change.position.x / size.width).coerceIn(0f, 1f)
                            },
                        )
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
                    val w = size.width
                    val activeW = w * currentFrac
                    val cy = size.height / 2f

                    // Inactive track line
                    drawLine(
                        color = primaryText.copy(alpha = 0.22f),
                        start = Offset(0f, cy),
                        end = Offset(w, cy),
                        strokeWidth = 1.6.dp.toPx(),
                        cap = StrokeCap.Round,
                    )

                    // Active track line
                    if (activeW > 0f) {
                        drawLine(
                            color = accent,
                            start = Offset(0f, cy),
                            end = Offset(activeW, cy),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }

                    // Diamond / Lancet thumb
                    val thumbSize = if (dragFrac in 0f..1f) 6.dp.toPx() else 4.dp.toPx()
                    val path = Path().apply {
                        moveTo(activeW, cy - thumbSize)
                        lineTo(activeW + thumbSize * 0.7f, cy)
                        lineTo(activeW, cy + thumbSize)
                        lineTo(activeW - thumbSize * 0.7f, cy)
                        close()
                    }
                    drawPath(path, color = if (isDark) Color.White else primaryText)
                }
            }

            // Total duration
            Text(
                text = if (duration > 0) makeTimeString(duration) else "0:00",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = GothicThemeConfig.SerifFont,
                    color = secondaryText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.End,
                ),
                modifier = Modifier.width(42.dp),
            )
        }

        // Lowered spacing between seekbar and control buttons
        Spacer(Modifier.height(36.dp))

        // ── 3. Main Controls Row: Shuffle | Prev | Play/Pause | Next | Repeat ────────
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            // Shuffle Button
            IconButton(
                onClick = onToggleShuffle,
                enabled = !isListenTogetherGuest,
                modifier = Modifier.size(52.dp),
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.shuffle),
                    contentDescription = null,
                    tint = if (shuffleModeEnabled) primaryText else mutedText,
                    modifier = Modifier.size(28.dp),
                )
            }

            // Previous Button with Gothic lancet arch frame
            Box(
                modifier = Modifier.size(68.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.matchParentSize()) {
                    drawGothicFrame(size.minDimension, primaryText.copy(alpha = if (canSkipPrevious) 0.55f else 0.25f))
                }
                IconButton(
                    onClick = onSeekPrevious,
                    enabled = canSkipPrevious && !isListenTogetherGuest,
                    modifier = Modifier.size(58.dp),
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.skip_previous),
                        contentDescription = null,
                        tint = if (canSkipPrevious) primaryText else mutedText,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            // Play/Pause Button with Gothic cathedral frame
            Box(
                modifier = Modifier.size(92.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.matchParentSize()) {
                    drawGothicFrame(size.minDimension, primaryText.copy(alpha = 0.85f), isMain = true)
                }
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(82.dp),
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(
                            if (isPlaying) R.drawable.pause else R.drawable.play
                        ),
                        contentDescription = null,
                        tint = primaryText,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }

            // Next Button with Gothic lancet arch frame
            Box(
                modifier = Modifier.size(68.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.matchParentSize()) {
                    drawGothicFrame(size.minDimension, primaryText.copy(alpha = if (canSkipNext) 0.55f else 0.25f))
                }
                IconButton(
                    onClick = onSeekNext,
                    enabled = canSkipNext && !isListenTogetherGuest,
                    modifier = Modifier.size(58.dp),
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.skip_next),
                        contentDescription = null,
                        tint = if (canSkipNext) primaryText else mutedText,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            // Repeat Button
            IconButton(
                onClick = onToggleRepeat,
                enabled = !isListenTogetherGuest,
                modifier = Modifier.size(52.dp),
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(
                        if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.repeat_one else R.drawable.repeat
                    ),
                    contentDescription = null,
                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) primaryText else mutedText,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** Draws a clean Gothic pointed lancet arch / fourfoil button frame */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGothicFrame(dimension: Float, color: Color, isMain: Boolean = false) {
    val r = dimension / 2f
    val c = Offset(r, r)
    val strokeWidth = (if (isMain) 1.6f else 1.2f) * density
    val stroke = Stroke(strokeWidth)

    // Outer circle
    drawCircle(color, radius = r - strokeWidth * 2, style = stroke)

    if (isMain) {
        // Inner pointed diamond accents at 4 cardinal points
        val innerR = r - strokeWidth * 2
        val d = 4f * density
        listOf(
            Offset(c.x, c.y - innerR),
            Offset(c.x + innerR, c.y),
            Offset(c.x, c.y + innerR),
            Offset(c.x - innerR, c.y),
        ).forEach { pt ->
            val path = Path().apply {
                moveTo(pt.x, pt.y - d)
                lineTo(pt.x + d * 0.7f, pt.y)
                lineTo(pt.x, pt.y + d)
                lineTo(pt.x - d * 0.7f, pt.y)
                close()
            }
            drawPath(path, color = color)
        }
    }
}
