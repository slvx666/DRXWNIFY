/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.constants.MetadataSource
import com.metrolist.music.playback.AccountTransfer
import com.metrolist.music.playback.RemotePlaylists
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.serviceNameRes
import com.metrolist.spotify.models.SpotifyLibraryEntry
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class TransferTab { TRACKS, ALBUMS, PLAYLISTS }

/** One row to pick: a track, an album or a playlist of the other account. */
private data class Pickable(val key: String, val title: String, val subtitle: String?, val image: String?, val round: Boolean = false)

/**
 * What to bring over from another linked account to the main one: its liked tracks, saved albums
 * and playlists, each tab with a search; ticks add up in the bar at the bottom, one button sends
 * them. Playlists are copied into the app first and then created in the main account in the
 * background; albums are saved and tracks liked in the main account (see [AccountTransfer]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountTransferScreen(navController: NavController, from: MetadataSource) {
    val database = LocalDatabase.current
    val state by Catalog.state.collectAsState()
    val main = state.source
    val progress by AccountTransfer.progress.collectAsState()
    var tab by rememberSaveable { mutableStateOf(TransferTab.PLAYLISTS) }
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<SpotifyLibraryEntry>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<SpotifyTrack>>(emptyList()) }
    var pickedTracks by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Albums the main account has already ("title|artist", folded), to say so on their rows.
    var mainAlbums by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pickedAlbums by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pickedPlaylists by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(from) {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!Catalog.ensureAuthenticated(from)) return@runCatching
                val all = mutableListOf<SpotifyLibraryEntry>()
                var offset = 0
                while (offset < 1000) {
                    val page = Catalog.myLibraryOf(from, null, 50, offset).getOrNull() ?: break
                    all += page.items
                    offset += 50
                    if (page.items.isEmpty() || offset >= page.total) break
                }
                entries = all.distinctBy { it.uri.ifBlank { it.id } }
                val liked = mutableListOf<SpotifyTrack>()
                offset = 0
                while (offset < 3000) {
                    val page = Catalog.likedSongsOf(from, 50, offset).getOrNull() ?: break
                    liked += page.items.map { it.track }.filter { !it.isLocal && it.id.isNotBlank() }
                    offset += 50
                    if (page.items.isEmpty() || offset >= page.total) break
                }
                tracks = liked.distinctBy { it.id }
                main?.let { m ->
                    val have = HashSet<String>()
                    var o = 0
                    while (o < 1000) {
                        val page = Catalog.myLibraryOf(m, "Albums", 50, o).getOrNull() ?: break
                        page.items.forEach { have += albumKey(it.name, it.creator) }
                        o += 50
                        if (page.items.isEmpty() || o >= page.total) break
                    }
                    mainAlbums = have
                }
            }
        }
        loading = false
    }

    val alreadyLabel = main?.let { stringResource(R.string.transfer_already_in, stringResource(serviceNameRes(it))) }.orEmpty()
    val needle = query.trim().lowercase()
    fun matches(vararg texts: String?) = needle.isEmpty() || texts.any { it?.lowercase()?.contains(needle) == true }
    val rows: List<Pickable> = when (tab) {
        TransferTab.TRACKS -> tracks.filter { matches(it.name, it.artists.joinToString { a -> a.name }) }.map { t ->
            Pickable(t.id, t.name, t.artists.joinToString { it.name }, t.album?.images?.firstOrNull()?.url)
        }
        TransferTab.ALBUMS -> entries.filter { it.kind == SpotifyLibraryEntry.Kind.ALBUM && matches(it.name, it.creator) }.map { e ->
            val already = albumKey(e.name, e.creator) in mainAlbums
            Pickable(
                e.id,
                e.name,
                listOfNotNull(e.creator, alreadyLabel.takeIf { already }).joinToString(" · ").ifBlank { null },
                e.imageUrl,
            )
        }
        TransferTab.PLAYLISTS -> entries.filter { it.kind == SpotifyLibraryEntry.Kind.PLAYLIST && matches(it.name, it.creator) }.map { e ->
            Pickable(e.id, e.name, e.creator, e.imageUrl)
        }
    }
    val picked = when (tab) {
        TransferTab.TRACKS -> pickedTracks
        TransferTab.ALBUMS -> pickedAlbums
        TransferTab.PLAYLISTS -> pickedPlaylists
    }
    fun setPicked(value: Set<String>) {
        when (tab) {
            TransferTab.TRACKS -> pickedTracks = value
            TransferTab.ALBUMS -> pickedAlbums = value
            TransferTab.PLAYLISTS -> pickedPlaylists = value
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))
        val p = progress
        if (p != null && p.from == from) {
            TransferProgress(p, onDone = { AccountTransfer.clear() })
            return@Column
        }
        if (main == null || main == from) {
            Text(stringResource(R.string.transfer_no_main), modifier = Modifier.padding(16.dp))
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            FilterChip(selected = tab == TransferTab.PLAYLISTS, onClick = { tab = TransferTab.PLAYLISTS }, label = { Text(countLabel(R.string.filter_playlists, pickedPlaylists.size)) })
            FilterChip(selected = tab == TransferTab.ALBUMS, onClick = { tab = TransferTab.ALBUMS }, label = { Text(countLabel(R.string.filter_albums, pickedAlbums.size)) })
            FilterChip(selected = tab == TransferTab.TRACKS, onClick = { tab = TransferTab.TRACKS }, label = { Text(countLabel(R.string.vk_tab_tracks, pickedTracks.size)) })
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.search)) },
            singleLine = true,
            leadingIcon = { Icon(painterResource(R.drawable.search), null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val allPicked = rows.isNotEmpty() && rows.all { it.key in picked }
            TextButton(onClick = {
                setPicked(if (allPicked) picked - rows.map { it.key }.toSet() else picked + rows.map { it.key })
            }) { Text(stringResource(if (allPicked) R.string.transfer_pick_none else R.string.transfer_pick_all)) }
            Spacer(Modifier.weight(1f))
            Text(
                pluralStringResource(R.plurals.transfer_found, rows.size, rows.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
                    items(rows, key = { it.key }) { row ->
                        val on = row.key in picked
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { setPicked(if (on) picked - row.key else picked + row.key) }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            AsyncImage(
                                model = row.image,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(if (row.round) CircleShape else RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                row.subtitle?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Checkbox(checked = on, onCheckedChange = { setPicked(if (on) picked - row.key else picked + row.key) })
                        }
                    }
                }
            }
        }
        // What is picked across the tabs, and the one button that brings it over.
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(
                        R.string.transfer_picked,
                        pickedPlaylists.size,
                        pickedAlbums.size,
                        pickedTracks.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = pickedPlaylists.size + pickedAlbums.size + pickedTracks.size > 0,
                    onClick = {
                        AccountTransfer.start(
                            database,
                            from,
                            playlists = entries.filter { it.kind == SpotifyLibraryEntry.Kind.PLAYLIST && it.id in pickedPlaylists },
                            albums = entries.filter { it.kind == SpotifyLibraryEntry.Kind.ALBUM && it.id in pickedAlbums },
                            tracks = tracks.filter { it.id in pickedTracks },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = com.metrolist.music.ui.theme.tuiButtonShape(),
                ) { Text(stringResource(R.string.transfer_start, stringResource(serviceNameRes(main)))) }
            }
        }
    }
    TopAppBar(
        title = { Text(stringResource(R.string.transfer_from_to, stringResource(serviceNameRes(from)), stringResource(serviceNameRes(main ?: from)))) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = {}) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}

@Composable
private fun countLabel(res: Int, count: Int): String = stringResource(res) + if (count > 0) " ($count)" else ""

@Composable
private fun TransferProgress(p: AccountTransfer.Progress, onDone: () -> Unit) {
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(if (p.finished) R.string.transfer_done else R.string.transfer_running, p.done, p.total),
            style = MaterialTheme.typography.titleMedium,
        )
        LinearProgressIndicator(progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f }, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.transfer_playlists_later),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (p.missing.isNotEmpty()) {
            Text(pluralStringResource(R.plurals.transfer_missing, p.missing.size, p.missing.size), style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(p.missing.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (p.finished) {
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth(), shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                Text(stringResource(R.string.transfer_again))
            }
        }
    }
}

/**
 * Playlists imported into the app (files, links, another account), created in the main account
 * too — slowly, in the background ([RemotePlaylists]); their progress shows here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushLocalPlaylistsScreen(navController: NavController) {
    val state by Catalog.state.collectAsState()
    val main = state.source
    val jobs by RemotePlaylists.jobs.collectAsState()
    var imported by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(main) {
        imported = withContext(Dispatchers.IO) { RemotePlaylists.importedPlaylists() }
        picked = main?.let { m -> imported.map { it.first }.filter { !RemotePlaylists.isPushed(it, m) }.toSet() }.orEmpty()
    }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))
        if (main == null) {
            Text(stringResource(R.string.transfer_no_main), modifier = Modifier.padding(16.dp))
            return@Column
        }
        Text(
            stringResource(R.string.push_local_explain, stringResource(serviceNameRes(main))),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(imported, key = { it.first }) { (id, name) ->
                val job = jobs.firstOrNull { it.localId == id && it.target == main }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = job == null || job.step == RemotePlaylists.Step.FAILED) {
                            picked = if (id in picked) picked - id else picked + id
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (job?.step) {
                                null -> stringResource(R.string.push_state_app_only)
                                RemotePlaylists.Step.CREATE -> stringResource(R.string.push_state_queued)
                                RemotePlaylists.Step.FILL -> stringResource(R.string.push_state_filling, job.cursor, job.total)
                                RemotePlaylists.Step.DONE -> if (job.missing.isEmpty()) {
                                    stringResource(R.string.push_state_done)
                                } else {
                                    stringResource(R.string.push_state_done_missing, job.missing.size)
                                }
                                RemotePlaylists.Step.FAILED -> stringResource(R.string.push_state_failed, job.error.orEmpty())
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (job?.step == RemotePlaylists.Step.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (job == null || job.step == RemotePlaylists.Step.FAILED) {
                        Checkbox(checked = id in picked, onCheckedChange = { picked = if (it) picked + id else picked - id })
                    }
                }
            }
        }
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = picked.isNotEmpty() && RemotePlaylists.canCreate(main),
                onClick = {
                    RemotePlaylists.push(picked.toList(), main)
                    picked = emptySet()
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = com.metrolist.music.ui.theme.tuiButtonShape(),
            ) { Text(stringResource(R.string.push_local_start, picked.size, stringResource(serviceNameRes(main)))) }
        }
    }
    TopAppBar(
        title = { Text(stringResource(R.string.push_local_screen_title)) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = {}) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}

/** An album's name and first artist, folded, for telling "the same album" across catalogs. */
private fun albumKey(name: String, artist: String?): String =
    (name.lowercase().replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "") + "|" + artist.orEmpty().split(", ").first().lowercase())
        .replace(Regex("""[^\p{L}\p{N}|]+"""), "")
