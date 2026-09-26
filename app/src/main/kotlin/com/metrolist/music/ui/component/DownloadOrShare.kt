/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.DownloadService
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.R
import com.metrolist.music.playback.ExoDownloadService
import kotlinx.coroutines.launch

/**
 * The download button of an album or playlist, and what to do with what is downloaded:
 *  - nothing downloaded: the usual [downloadButton];
 *  - part of it: one pill, "download the rest | send 13/16";
 *  - all of it: one pill, "remove | send 16".
 * The count on the send half says exactly what will go out. [compact] = the round VK style.
 */
@Composable
fun DownloadOrShare(
    downloaded: Int,
    total: Int,
    downloadedIds: suspend () -> List<String>,
    onDownloadRest: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    downloadButton: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val downloadUtil = LocalDownloadUtil.current
    val scope = rememberCoroutineScope()
    var confirmIds by remember { mutableStateOf<List<String>?>(null) }

    fun share() {
        val appContext = context.applicationContext
        scope.launch {
            val ids = downloadedIds()
            if (ids.isEmpty()) return@launch
            if (ids.size < total) {
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.sending_part, ids.size, total),
                    Toast.LENGTH_LONG,
                ).show()
            }
            com.metrolist.music.utils.shareDownloadedFiles(appContext, downloadUtil.downloadExporter, ids)
        }
    }

    fun askDelete() {
        scope.launch { confirmIds = downloadedIds().takeIf { it.isNotEmpty() } }
    }

    val state = when {
        total <= 0 || downloaded <= 0 -> 0
        downloaded < total -> 1
        else -> 2
    }

    AnimatedContent(
        targetState = state,
        transitionSpec = {
            (fadeIn(tween(220, delayMillis = 60, easing = FastOutSlowInEasing)) +
                scaleIn(tween(260, easing = FastOutSlowInEasing), initialScale = 0.9f)) togetherWith
                fadeOut(tween(120)) using SizeTransform(clip = false)
        },
        contentAlignment = Alignment.CenterStart,
        label = "downloadOrShare",
        modifier = modifier,
    ) { s ->
        if (s == 0) {
            downloadButton()
            return@AnimatedContent
        }
        val shareLabel = if (s == 1) "$downloaded/$total" else "$downloaded"
        SplitPill(
            height = if (compact) 48.dp else 40.dp,
            left = {
                if (s == 1) {
                    Icon(painterResource(R.drawable.download), stringResource(R.string.download_the_rest_short), Modifier.size(20.dp))
                } else {
                    Icon(painterResource(R.drawable.delete), stringResource(R.string.remove_downloads), Modifier.size(20.dp))
                }
            },
            onLeft = if (s == 1) onDownloadRest else ::askDelete,
            right = {
                Icon(painterResource(R.drawable.share), stringResource(R.string.send_files), Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(shareLabel, style = MaterialTheme.typography.labelLarge)
            },
            onRight = ::share,
        )
    }

    confirmIds?.let { ids ->
        AlertDialog(
            onDismissRequest = { confirmIds = null },
            title = { Text(pluralStringResource(R.plurals.remove_downloads_confirm, ids.size, ids.size)) },
            text = { Text(stringResource(R.string.remove_downloads_note)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmIds = null
                    ids.forEach { id ->
                        DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, id, false)
                    }
                    Toast.makeText(context, R.string.downloads_removed, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.remove_downloads)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmIds = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

/** One outlined pill with two tappable halves and a thin line between them. */
@Composable
private fun SplitPill(
    height: androidx.compose.ui.unit.Dp,
    left: @Composable () -> Unit,
    onLeft: () -> Unit,
    right: @Composable () -> Unit,
    onRight: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.height(height),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxHeight()
                    .clickable(onClick = onLeft)
                    .padding(start = 18.dp, end = 14.dp),
            ) { left() }
            Box(
                Modifier
                    .width(1.dp)
                    .height(height / 2)
                    .background(MaterialTheme.colorScheme.outline),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxHeight()
                    .clickable(onClick = onRight)
                    .padding(start = 14.dp, end = 18.dp),
            ) { right() }
        }
    }
}
