/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.playback.PlaylistImporter
import com.metrolist.music.utils.PlaylistImportParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bringing a playlist over from another app: a file (Telegram export, M3U/AIMP, CSV) or pasted text
 * and links. The user names the playlist; the tracks are found in the catalog like a search would
 * find them, and nothing is downloaded. The "?" explains what each app can export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPlaylistScreen(navController: NavController, targetPlaylistId: String? = null) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    // Where the tracks go: a new playlist (named below) or one of the user's own playlists.
    var target by rememberSaveable { mutableStateOf(targetPlaylistId) }
    var intoExisting by rememberSaveable { mutableStateOf(targetPlaylistId != null) }
    val ownPlaylists by database.editablePlaylistsByCreateDateAsc().collectAsState(initial = emptyList())
    // The user's own audio files, added as they are (no lookup).
    var audioUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var starting by remember { mutableStateOf(false) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) audioUris = (audioUris + uris).distinct()
    }
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var fileName by rememberSaveable { mutableStateOf<String?>(null) }
    var fileText by remember { mutableStateOf<String?>(null) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val progress by PlaylistImporter.progress.collectAsState()
    DisposableEffect(Unit) { onDispose { PlaylistImporter.reset() } }

    // Several files at once: a Telegram HTML export is split into messages.html, messages2.html, …
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            fileText = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching { context.contentResolver.openInputStream(uri)?.use { readText(it.readBytes()) } }.getOrNull()
                }.joinToString("\n")
            }
            fileName = if (uris.size == 1) uris[0].lastPathSegment?.substringAfterLast('/') else context.getString(R.string.import_files_count, uris.size)
            if (name.isBlank() && uris.size == 1) {
                fileName?.substringBeforeLast('.')?.takeIf { it != "result" && !it.startsWith("messages") }?.let { name = it }
            }
        }
    }
    // A job left by a killed app goes on as soon as the screen is opened.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (PlaylistImporter.isRunning(context) && progress == null) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, android.content.Intent(context, com.metrolist.music.playback.PlaylistImportService::class.java),
            )
        }
    }
    val parsed = remember(text, fileText) {
        val all = listOfNotNull(fileText, text.takeIf { it.isNotBlank() })
        val results = all.map { PlaylistImportParser.parse(it) }
        PlaylistImportParser.Parsed(
            results.flatMap { it.entries }.distinctBy { it.label.lowercase() },
            results.flatMap { it.links }.distinct(),
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.import_playlist)) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) { Icon(painterResource(R.drawable.arrow_back), null) }
            },
            actions = {
                IconButton(onClick = { showHelp = true }) { Icon(painterResource(R.drawable.info), stringResource(R.string.import_help)) }
            },
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues().let { androidx.compose.foundation.layout.PaddingValues(bottom = it.calculateBottomPadding()) }),
        ) {
            val p = progress
            if (p != null) {
                ImportProgress(p, onOpen = { id -> navController.navigate("local_playlist/$id") })
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(
                    selected = !intoExisting,
                    onClick = { intoExisting = false },
                    label = { Text(stringResource(R.string.import_into_new)) },
                )
                androidx.compose.material3.FilterChip(
                    selected = intoExisting,
                    onClick = { intoExisting = true },
                    enabled = ownPlaylists.isNotEmpty(),
                    label = { Text(stringResource(R.string.import_into_existing)) },
                )
            }
            if (!intoExisting) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.import_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    ownPlaylists.forEach { playlist ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { target = playlist.id }
                                .padding(vertical = 2.dp),
                        ) {
                            androidx.compose.material3.RadioButton(selected = target == playlist.id, onClick = { target = playlist.id })
                            Text(
                                text = playlist.playlist.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            Text(playlist.songCount.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Icon(painterResource(R.drawable.add), null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.import_pick_file))
                }
                fileName?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }) {
                    Icon(painterResource(R.drawable.music_note), null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.import_pick_audio))
                }
                if (audioUris.isNotEmpty()) {
                    Text(stringResource(R.string.import_audio_count, audioUris.size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { audioUris = emptyList() }) { Icon(painterResource(R.drawable.close), null) }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.import_paste)) },
                placeholder = { Text("Artist - Title\nhttps://open.spotify.com/playlist/…") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            )
            Text(
                text = stringResource(R.string.import_recognized, parsed.entries.size, parsed.links.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val destination = if (intoExisting) ownPlaylists.firstOrNull { it.id == target } else null
            Button(
                enabled = !starting && (if (intoExisting) destination != null else name.isNotBlank()) &&
                    (parsed.entries.isNotEmpty() || parsed.links.isNotEmpty() || audioUris.isNotEmpty()),
                onClick = {
                    starting = true
                    scope.launch {
                        val localIds = com.metrolist.music.playback.LocalAudioImport.importFiles(context, database, audioUris)
                        PlaylistImporter.start(
                            context,
                            destination?.playlist?.name ?: name.trim(),
                            parsed,
                            targetPlaylistId = destination?.id,
                            localSongIds = localIds,
                        )
                        starting = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (intoExisting) R.string.import_start_add else R.string.import_start)) }
            Text(
                text = stringResource(R.string.import_no_download),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showHelp) ImportHelpDialog(onDismiss = { showHelp = false })
}

@Composable
private fun ImportProgress(p: PlaylistImporter.Progress, onOpen: (String) -> Unit) {
    Text(
        text = stringResource(if (p.finished) R.string.import_done else R.string.import_running, p.found, p.total),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    LinearProgressIndicator(
        progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
        modifier = Modifier.fillMaxWidth(),
    )
    if (!p.finished) {
        Text(
            stringResource(R.string.import_background),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // The playlist exists from the start and fills as the import goes.
    p.playlistId?.let { id -> Button(onClick = { onOpen(id) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_open)) } }
    if (p.finished) {
        if (p.notFound.isNotEmpty()) {
            Text(stringResource(R.string.import_not_found, p.notFound.size), style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(p.notFound.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ImportHelpDialog(onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val prompt = stringResource(R.string.import_ai_prompt)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_help)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.import_help_body), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.import_help_ai_title), style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(prompt, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { clipboard.setText(AnnotatedString(prompt)) }) { Text(stringResource(R.string.import_copy_prompt)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
}

/** Text in UTF-8, or UTF-16 (AIMP writes its playlists so) when the bytes say so. */
private fun readText(bytes: ByteArray): String = when {
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, Charsets.UTF_16LE).drop(1)
    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, Charsets.UTF_16BE).drop(1)
    else -> String(bytes, Charsets.UTF_8)
}
