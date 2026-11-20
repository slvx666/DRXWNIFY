package com.metrolist.music.ui.player

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.playback.rememberTrackLiked
import com.metrolist.music.playback.rememberLikeSyncs
import com.metrolist.music.ui.theme.SatanicThemeConfig
import com.metrolist.music.utils.makeTimeString

/**
 * Custom player controls for the SATANIC Theme matching Reference 01:
 * - Centered Track Title in Blackletter/Gothic font
 * - Centered Artist in Serif font
 * - Thin custom seekable progress bar with progress_decoration.png
 * - Main row: Shuffle | Previous (framed) | Play/Pause (framed) | Next (framed) | Repeat
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SatanicPlayerControls(
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
    val primaryText = SatanicThemeConfig.getPrimaryText(isDark)
    val secondaryText = SatanicThemeConfig.getSecondaryText(isDark)
    val mutedText = SatanicThemeConfig.getMutedText(isDark)
    val accent = SatanicThemeConfig.getAccent(isDark)

    val playButtonFrame = SatanicThemeConfig.rememberAsset(SatanicThemeConfig.PATH_PLAY_BUTTON_FRAME)
    val controlButtonFrame = SatanicThemeConfig.rememberAsset(SatanicThemeConfig.PATH_CONTROL_BUTTON_FRAME)
    val progressDeco = SatanicThemeConfig.rememberAsset(SatanicThemeConfig.PATH_PROGRESS_DECORATION)

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
        // ── 1. Track Title in Blackletter & Artist in Serif with Pentagram Like Button ──
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
                        fontFamily = SatanicThemeConfig.GothicAccentFont,
                        fontSize = 28.sp,
                        color = primaryText,
                        textAlign = TextAlign.Center,
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

                Spacer(Modifier.height(4.dp))

                Text(
                    text = mediaMetadata.artists.joinToString { it.name }.ifEmpty { "—" },
                    style = TextStyle(
                        fontFamily = SatanicThemeConfig.SerifFont,
                        fontSize = 16.sp,
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

            // Pentagram Like Button on the right of track title
            if (playerConnection != null) {
                IconButton(
                    onClick = { playerConnection.service.setCurrentFavorite(!isLiked) },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(44.dp),
                ) {
                    val pentagramIcon = if (isLiked) R.drawable.theme_pentagram_active else R.drawable.theme_pentagram_outline
                    val pentagramTint = if (isLiked) (if (isDark) Color.White else primaryText) else mutedText
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(pentagramIcon),
                        contentDescription = "Favorite",
                        tint = pentagramTint,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── 3. Progress Bar with Decorative Element ─────────────────────────────────
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
                    fontFamily = SatanicThemeConfig.SerifFont,
                    color = secondaryText,
                    fontSize = 13.sp,
                ),
                modifier = Modifier.width(42.dp),
            )

            // Progress Slider Track with progress_decoration.png
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
                // Background Track: progress_decoration.png
                if (progressDeco != null) {
                    Image(
                        bitmap = progressDeco,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        colorFilter = if (!isDark) ColorFilter.tint(primaryText) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .align(Alignment.Center),
                    )
                }

                // Foreground active line
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
                    val w = size.width * currentFrac
                    val cy = size.height / 2f
                    // Active progress line
                    if (w > 0f) {
                        drawLine(
                            color = accent,
                            start = androidx.compose.ui.geometry.Offset(0f, cy),
                            end = androidx.compose.ui.geometry.Offset(w, cy),
                            strokeWidth = 1.8.dp.toPx(),
                        )
                    }
                    // Small progress dot thumb
                    drawCircle(
                        color = if (isDark) Color.White else primaryText,
                        radius = if (dragFrac in 0f..1f) 4.5.dp.toPx() else 3.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(w, cy),
                    )
                }
            }

            // Total duration
            Text(
                text = if (duration > 0) makeTimeString(duration) else "0:00",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = SatanicThemeConfig.SerifFont,
                    color = secondaryText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.End,
                ),
                modifier = Modifier.width(42.dp),
            )
        }

        Spacer(Modifier.height(28.dp))

        // ── 4. Main Controls Row: Shuffle | Prev | Play/Pause | Next | Repeat ────────
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
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.shuffle),
                    contentDescription = null,
                    tint = if (shuffleModeEnabled) primaryText else mutedText,
                    modifier = Modifier.size(24.dp),
                )
            }

            // Previous Button with control_button_frame.png
            Box(
                modifier = Modifier.size(54.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (controlButtonFrame != null) {
                    Image(
                        bitmap = controlButtonFrame,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = if (!isDark) ColorFilter.tint(primaryText) else null,
                        modifier = Modifier.size(54.dp),
                    )
                }
                IconButton(
                    onClick = onSeekPrevious,
                    enabled = canSkipPrevious && !isListenTogetherGuest,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.skip_previous),
                        contentDescription = null,
                        tint = if (canSkipPrevious) primaryText else mutedText,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            // Play/Pause Button with play_button_frame.png
            Box(
                modifier = Modifier.size(78.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (playButtonFrame != null) {
                    Image(
                        bitmap = playButtonFrame,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = if (!isDark) ColorFilter.tint(primaryText) else null,
                        modifier = Modifier.size(78.dp),
                    )
                }
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(68.dp),
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(
                            if (isPlaying) R.drawable.pause else R.drawable.play
                        ),
                        contentDescription = null,
                        tint = primaryText,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }

            // Next Button with control_button_frame.png
            Box(
                modifier = Modifier.size(54.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (controlButtonFrame != null) {
                    Image(
                        bitmap = controlButtonFrame,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = if (!isDark) ColorFilter.tint(primaryText) else null,
                        modifier = Modifier.size(54.dp),
                    )
                }
                IconButton(
                    onClick = onSeekNext,
                    enabled = canSkipNext && !isListenTogetherGuest,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.skip_next),
                        contentDescription = null,
                        tint = if (canSkipNext) primaryText else mutedText,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            // Repeat Button
            IconButton(
                onClick = onToggleRepeat,
                enabled = !isListenTogetherGuest,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(
                        if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.repeat_one else R.drawable.repeat
                    ),
                    contentDescription = null,
                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) primaryText else mutedText,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
