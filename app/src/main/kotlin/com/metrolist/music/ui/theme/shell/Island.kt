/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.playback.PlayerConnection
import kotlinx.coroutines.delay

/** Something just happened in the island: it takes a breath (counted, so each event plays once). */
private object IslandEvents {
    var breaths by mutableIntStateOf(0)
}

/**
 * The island's breath: on a new track or a note it swells a little and settles back with a jelly
 * spring — the capsule feels alive, never jumpy.
 */
@Composable
fun Modifier.islandBreath(): Modifier {
    val scale = remember { Animatable(1f) }
    val breaths = IslandEvents.breaths
    LaunchedEffect(breaths) {
        if (breaths == 0 || ShellRuntime.calm) return@LaunchedEffect
        scale.animateTo(1.035f, tween(140))
        scale.animateTo(1f, ShellMotion.jelly())
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = 1f + (scale.value - 1f) * 1.6f
    }
}

/**
 * Notes in the island: a like or an unlike of what plays shows over the mini player for a
 * moment ("Added to liked songs"), then the track comes back. A new track also makes it breathe.
 */
@Composable
fun IslandNotes(playerConnection: PlayerConnection, modifier: Modifier = Modifier) {
    var note by remember { mutableStateOf<Boolean?>(null) }
    var noteId by remember { mutableIntStateOf(0) }
    LaunchedEffect(playerConnection) {
        var lastId: String? = null
        var lastLiked: Boolean? = null
        playerConnection.currentSong.collect { song ->
            val id = song?.id
            val liked = song?.song?.liked
            if (id != null && id == lastId && liked != null && lastLiked != null && liked != lastLiked) {
                note = liked
                noteId++
                IslandEvents.breaths++
            } else if (id != null && id != lastId && lastId != null) {
                IslandEvents.breaths++
            }
            lastId = id
            lastLiked = liked
        }
    }
    LaunchedEffect(noteId) {
        if (noteId == 0) return@LaunchedEffect
        delay(1_700)
        note = null
    }
    AnimatedVisibility(
        visible = note != null,
        enter = fadeIn(tween(180, delayMillis = 60)) + scaleIn(ShellMotion.jelly(), initialScale = 0.92f),
        exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
        modifier = modifier,
    ) {
        val liked = note ?: true
        Box(Modifier.fillMaxSize().background(islandColor()), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
                Icon(
                    painterResource(if (liked) R.drawable.favorite else R.drawable.favorite_border),
                    contentDescription = null,
                    tint = if (liked) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(if (liked) R.string.shell_island_liked else R.string.shell_island_unliked),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}
