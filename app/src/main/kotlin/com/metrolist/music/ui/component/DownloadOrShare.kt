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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
 * The download button of an album or playlist. Once everything in it is downloaded the button splits
 * — smoothly — into two: remove the downloads, and send all the files at once (to Telegram, a cloud,
 * another phone…). [downloadedIds] gives the downloaded tracks' ids when one of them is used.
 * [compact] = the round icon buttons of the VK pages instead of text buttons.
 */
@Composable
fun DownloadOrShare(
    allDownloaded: Boolean,
    downloadedIds: suspend () -> List<String>,
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
            com.metrolist.music.utils.shareDownloadedFiles(appContext, downloadUtil.downloadExporter, ids)
        }
    }

    fun askDelete() {
        scope.launch { confirmIds = downloadedIds().takeIf { it.isNotEmpty() } }
    }

    AnimatedContent(
        targetState = allDownloaded,
        transitionSpec = {
            (fadeIn(tween(220, delayMillis = 60, easing = FastOutSlowInEasing)) +
                scaleIn(tween(260, easing = FastOutSlowInEasing), initialScale = 0.9f)) togetherWith
                fadeOut(tween(120)) using SizeTransform(clip = false)
        },
        contentAlignment = Alignment.CenterStart,
        label = "downloadOrShare",
        modifier = modifier,
    ) { done ->
        when {
            !done -> downloadButton()
            compact -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = ::askDelete, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.delete), stringResource(R.string.remove_downloads), Modifier.size(22.dp))
                }
                FilledTonalIconButton(onClick = ::share, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.share), stringResource(R.string.send_files), Modifier.size(22.dp))
                }
            }
            // One pill the size of the download button, split down the middle: remove | send.
            else -> androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                color = androidx.compose.ui.graphics.Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outline),
                modifier = Modifier.height(40.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.IconButton(onClick = ::askDelete, modifier = Modifier.width(52.dp)) {
                        Icon(painterResource(R.drawable.delete), stringResource(R.string.remove_downloads), Modifier.size(20.dp))
                    }
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .width(1.dp)
                            .height(22.dp)
                            .background(androidx.compose.material3.MaterialTheme.colorScheme.outline),
                    )
                    androidx.compose.material3.IconButton(onClick = ::share, modifier = Modifier.width(52.dp)) {
                        Icon(painterResource(R.drawable.share), stringResource(R.string.send_files), Modifier.size(20.dp))
                    }
                }
            }
        }
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
