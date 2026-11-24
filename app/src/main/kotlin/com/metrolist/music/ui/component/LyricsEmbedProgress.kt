/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.utils.LyricsEmbedJob

/**
 * "Write lyrics into the tracks" at work, as a batch download shows itself: how many of how many,
 * a bar, and how many were written, not found or failed. A tap on the cross stops it (while
 * running) or puts the finished line away.
 */
@Composable
fun LyricsEmbedProgressLine(modifier: Modifier = Modifier) {
    val progress by LyricsEmbedJob.progress.collectAsState()
    val p = progress ?: return
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (p.done) {
                        stringResource(R.string.embed_lyrics_done, p.written, p.notFound, p.failed)
                    } else {
                        stringResource(R.string.embed_lyrics_progress, p.checked, p.total)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!p.done) {
                    Text(
                        stringResource(R.string.embed_lyrics_counts, p.written, p.notFound, p.failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { if (p.done) LyricsEmbedJob.dismiss() else LyricsEmbedJob.cancel() }) {
                Icon(painterResource(R.drawable.close), contentDescription = stringResource(R.string.cancel), modifier = Modifier.size(20.dp))
            }
        }
        if (!p.done) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (p.total > 0) p.checked.toFloat() / p.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
