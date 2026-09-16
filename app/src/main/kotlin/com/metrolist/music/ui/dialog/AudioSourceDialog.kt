/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.ParallelAudioResolver
import com.metrolist.music.resolver.ProviderMatch

/**
 * Lets the user pick which recording plays for a track when the automatic choice is wrong: every
 * enabled source is searched and its best match listed. The choice is remembered for the track;
 * "Automatic" forgets it.
 */
@Composable
fun AudioSourceDialog(
    mediaId: String,
    onDismiss: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val service = playerConnection.service

    var loading by remember { mutableStateOf(true) }
    var candidates by remember { mutableStateOf<List<ProviderMatch>>(emptyList()) }
    var current by remember { mutableStateOf<ProviderMatch?>(null) }
    var selected by remember { mutableStateOf<ProviderMatch?>(null) }

    LaunchedEffect(mediaId) {
        current = service.currentAudioSource(mediaId)
        candidates = service.audioSourceCandidates(mediaId)
        selected = candidates.firstOrNull { it.provider == current?.provider && it.trackId == current?.trackId }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_source_choose)) },
        text = {
            Column {
                current?.let {
                    Text(
                        text = stringResource(R.string.audio_source_current, "${providerName(it.provider)}: ${it.artist} – ${it.title}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                when {
                    loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(vertical = 16.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.audio_source_searching))
                    }

                    candidates.isEmpty() -> Text(
                        text = stringResource(R.string.audio_source_none),
                        modifier = Modifier.padding(vertical = 16.dp),
                    )

                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                    ) {
                        items(candidates, key = { "${it.provider}:${it.trackId}" }) { candidate ->
                            val isSelected = selected?.provider == candidate.provider && selected?.trackId == candidate.trackId
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = candidate }
                                    .padding(vertical = 6.dp),
                            ) {
                                RadioButton(selected = isSelected, onClick = { selected = candidate })
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(
                                        text = "${candidate.artist} – ${candidate.title}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val percent = (candidate.confidence.coerceAtMost(1.0) * 100).toInt()
                                    Text(
                                        text = stringResource(R.string.audio_source_candidate_info, providerName(candidate.provider), percent),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = {
                    selected?.let { service.setAudioSource(mediaId, it) }
                    onDismiss()
                },
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            Row {
                if (current != null && current!!.confidence >= ParallelAudioResolver.MANUAL_CONFIDENCE) {
                    TextButton(onClick = {
                        service.setAudioSource(mediaId, null)
                        onDismiss()
                    }) { Text(stringResource(R.string.audio_source_auto)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun providerName(id: AudioProviderId): String = stringResource(
    when (id) {
        AudioProviderId.YOUTUBE -> R.string.audio_source_youtube
        AudioProviderId.QOBUZ -> R.string.audio_source_qobuz
        AudioProviderId.VK -> R.string.audio_source_vk
        AudioProviderId.SOUNDCLOUD -> R.string.audio_source_soundcloud
        AudioProviderId.BANDCAMP -> R.string.audio_source_bandcamp
        AudioProviderId.AUDIUS -> R.string.audio_source_audius
        AudioProviderId.SOULSEEK -> R.string.audio_source_soulseek
    },
)
