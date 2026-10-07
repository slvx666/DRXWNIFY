/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.friends

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.friends.FriendPlayback
import com.metrolist.music.friends.FriendsHub
import kotlinx.coroutines.launch

/** Keeps friends' updates flowing while the screen is shown. */
@Composable
private fun LiveWhileShown() {
    DisposableEffect(Unit) {
        FriendsHub.startLive()
        onDispose { FriendsHub.stopLive() }
    }
}

private fun shortKey(pubkey: String) = pubkey.take(8) + "…" + pubkey.takeLast(4)

private fun ago(context: Context, ms: Long): String {
    val minutes = ((System.currentTimeMillis() - ms) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> context.getString(R.string.friends_just_now)
        minutes < 60 -> context.getString(R.string.friends_minutes_ago, minutes)
        minutes < 24 * 60 -> context.getString(R.string.friends_hours_ago, minutes / 60)
        else -> context.getString(R.string.friends_days_ago, minutes / (24 * 60))
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("code", text))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(navController: NavController) {
    LiveWhileShown()
    val context = LocalContext.current
    val profile by FriendsHub.profile.collectAsState()
    val friends by FriendsHub.friends.collectAsState()
    val data by FriendsHub.data.collectAsState()
    val requests by FriendsHub.requests.collectAsState()
    val listeningTo by FriendsHub.listeningTo.collectAsState()
    var showBackup by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.friends_title)) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) { Icon(painterResource(R.drawable.arrow_back), null) }
            },
            actions = {
                if (profile != null) {
                    IconButton(onClick = { navController.navigate("friends/privacy") }) {
                        Icon(painterResource(R.drawable.lock), stringResource(R.string.friends_privacy))
                    }
                }
            },
        )
        val me = profile
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp,
                bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (me == null) {
                item { CreateProfileCard() }
                return@LazyColumn
            }
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                me.name.ifBlank { stringResource(R.string.friends_no_name) },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { showRename = true }) { Icon(painterResource(R.drawable.edit), null) }
                        }
                        Text(stringResource(R.string.friends_my_code), style = MaterialTheme.typography.labelMedium)
                        Text(me.code, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { copy(context, me.code) }) { Text(stringResource(R.string.friends_copy_code)) }
                            OutlinedButton(onClick = {
                                val text = context.getString(R.string.friends_share_text, me.code)
                                context.startActivity(
                                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null),
                                )
                            }) { Text(stringResource(R.string.share)) }
                        }
                        TextButton(onClick = { showBackup = true }) { Text(stringResource(R.string.friends_backup_key)) }
                    }
                }
            }
            listeningTo?.let { pub ->
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val name = friends.firstOrNull { it.pubkey == pub }?.name ?: shortKey(pub)
                            Text(stringResource(R.string.friends_listening_with, name), modifier = Modifier.weight(1f))
                            TextButton(onClick = FriendsHub::stopListenAlong) { Text(stringResource(R.string.friends_stop)) }
                        }
                    }
                }
            }
            if (requests.isNotEmpty()) {
                item { Text(stringResource(R.string.friends_requests), style = MaterialTheme.typography.titleMedium) }
                requests.forEach { request ->
                    item(key = "req_${request.pubkey}") {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(request.name.ifBlank { shortKey(request.pubkey) }, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.friends_wants_to_add), style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { FriendsHub.dismissRequest(request.pubkey) }) { Text(stringResource(R.string.friends_decline)) }
                                Button(onClick = { FriendsHub.addFriend(request.pubkey, request.name) }) { Text(stringResource(R.string.friends_accept)) }
                            }
                        }
                    }
                }
            }
            item { AddFriendRow() }
            item {
                Text(stringResource(R.string.friends_list_title, friends.size), style = MaterialTheme.typography.titleMedium)
            }
            if (friends.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.friends_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            friends.forEach { friend ->
                item(key = friend.pubkey) {
                    val d = data[friend.pubkey]
                    FriendRow(
                        name = d?.name ?: friend.name.ifBlank { shortKey(friend.pubkey) },
                        now = d?.now,
                        onClick = { navController.navigate("friend/${friend.pubkey}") },
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.friends_how_it_works),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showBackup) {
        val key = remember { FriendsHub.backupKey().orEmpty() }
        AlertDialog(
            onDismissRequest = { showBackup = false },
            title = { Text(stringResource(R.string.friends_backup_key)) },
            text = {
                Column {
                    Text(stringResource(R.string.friends_backup_warning), color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(key, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { copy(context, key); showBackup = false }) { Text(stringResource(R.string.friends_copy_code)) } },
            dismissButton = { TextButton(onClick = { showBackup = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (showRename) {
        var name by remember { mutableStateOf(profile?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.friends_name)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { FriendsHub.rename(name); showRename = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun CreateProfileCard() {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var restoring by rememberSaveable { mutableStateOf(false) }
    var key by rememberSaveable { mutableStateOf("") }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.friends_create_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.friends_create_explain), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text(stringResource(R.string.friends_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (restoring) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    label = { Text("nsec1…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                enabled = name.isNotBlank() && (!restoring || key.isNotBlank()),
                onClick = {
                    if (restoring) {
                        if (!FriendsHub.importProfile(key, name)) {
                            Toast.makeText(context, R.string.friends_bad_key, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        FriendsHub.createProfile(name)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (restoring) R.string.friends_restore else R.string.friends_create)) }
            TextButton(onClick = { restoring = !restoring }) {
                Text(stringResource(if (restoring) R.string.friends_create_new_instead else R.string.friends_have_key))
            }
        }
    }
}

@Composable
private fun AddFriendRow() {
    val context = LocalContext.current
    var code by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(stringResource(R.string.friends_add_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(enabled = code.isNotBlank(), onClick = {
            val pub = FriendsHub.parseCode(code)
            when {
                pub == null -> Toast.makeText(context, R.string.friends_bad_code, Toast.LENGTH_SHORT).show()
                pub == FriendsHub.profile.value?.pubkey -> Toast.makeText(context, R.string.friends_own_code, Toast.LENGTH_SHORT).show()
                else -> {
                    FriendsHub.addFriend(pub)
                    code = ""
                    Toast.makeText(context, R.string.friends_added, Toast.LENGTH_SHORT).show()
                }
            }
        }) { Text(stringResource(R.string.friends_add)) }
    }
}

@Composable
private fun FriendRow(name: String, now: FriendsHub.NowPlaying?, onClick: () -> Unit) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = now?.track?.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = when {
                    now == null -> stringResource(R.string.friends_nothing_shared)
                    now.playing -> "▶ ${now.track.artist} — ${now.track.title}"
                    else -> "${now.track.artist} — ${now.track.title} · ${ago(context, now.at)}"
                }
                Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendScreen(navController: NavController, pubkey: String) {
    LiveWhileShown()
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val friends by FriendsHub.friends.collectAsState()
    val all by FriendsHub.data.collectAsState()
    val listeningTo by FriendsHub.listeningTo.collectAsState()
    val friend = friends.firstOrNull { it.pubkey == pubkey }
    val d = all[pubkey]
    val name = d?.name ?: friend?.name?.ifBlank { null } ?: shortKey(pubkey)
    var confirmRemove by remember { mutableStateOf(false) }
    var openPlaylist by rememberSaveable { mutableStateOf<String?>(null) }

    fun play(tracks: List<FriendsHub.TrackRef>, index: Int) {
        val connection = playerConnection ?: return
        scope.launch { connection.playQueue(FriendPlayback.listQueue(tracks, index)) }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = navController::navigateUp) { Icon(painterResource(R.drawable.arrow_back), null) } },
            actions = {
                if (friend != null) IconButton(onClick = { confirmRemove = true }) { Icon(painterResource(R.drawable.delete), stringResource(R.string.friends_remove)) }
            },
        )
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp,
                bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                val now = d?.now
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(stringResource(R.string.friends_now_playing), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        if (now == null) {
                            Text(stringResource(R.string.friends_nothing_shared), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { play(listOf(now.track), 0) }) {
                                AsyncImage(now.track.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(now.track.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(now.track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    Text(
                                        if (now.playing) stringResource(R.string.friends_playing_now) else stringResource(R.string.friends_paused, ago(context, now.at)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            if (now.listenAlong) {
                                Spacer(Modifier.height(10.dp))
                                if (listeningTo == pubkey) {
                                    OutlinedButton(onClick = FriendsHub::stopListenAlong, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.friends_stop_listening))
                                    }
                                } else {
                                    Button(onClick = { FriendsHub.startListenAlong(pubkey) }, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.friends_listen_along))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            d?.stats?.let { s ->
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(stringResource(R.string.friends_week_stats), style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(R.string.friends_stats_line, s.weekMinutes, s.weekPlays, s.likedCount), style = MaterialTheme.typography.bodyMedium)
                            if (s.topArtists.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(stringResource(R.string.friends_top_artists), style = MaterialTheme.typography.labelMedium)
                                s.topArtists.forEachIndexed { i, (artist, count) ->
                                    Text("${i + 1}. $artist · $count", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            d?.playlists?.takeIf { it.isNotEmpty() }?.let { playlists ->
                item { Text(stringResource(R.string.friends_playlists), style = MaterialTheme.typography.titleMedium) }
                playlists.forEach { playlist ->
                    item(key = "pl_${playlist.name}") {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { openPlaylist = if (openPlaylist == playlist.name) null else playlist.name }
                                        .padding(12.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(playlist.name, fontWeight = FontWeight.SemiBold)
                                        Text(stringResource(R.string.friends_tracks_count, playlist.total), style = MaterialTheme.typography.bodySmall)
                                    }
                                    IconButton(onClick = { play(playlist.tracks, 0) }) { Icon(painterResource(R.drawable.play), null) }
                                }
                                if (openPlaylist == playlist.name) {
                                    playlist.tracks.forEachIndexed { i, t ->
                                        TrackRow(t, onClick = { play(playlist.tracks, i) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            d?.history?.takeIf { it.isNotEmpty() }?.let { history ->
                item { Text(stringResource(R.string.friends_history), style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(history, key = { i, t -> "h_${i}_${t.title}" }) { i, t ->
                    TrackRow(t, onClick = { play(history, i) }, at = t.at.takeIf { it > 0 }?.let { ago(context, it) })
                }
            }
            if (d == null || (d.history == null && d.playlists == null && d.stats == null && d.now == null)) {
                item {
                    Text(stringResource(R.string.friends_waiting_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.friends_remove)) },
            text = { Text(stringResource(R.string.friends_remove_confirm, name)) },
            confirmButton = {
                TextButton(onClick = {
                    FriendsHub.removeFriend(pubkey)
                    confirmRemove = false
                    navController.navigateUp()
                }) { Text(stringResource(R.string.friends_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun TrackRow(track: FriendsHub.TrackRef, onClick: () -> Unit, at: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (track.cover != null) {
            AsyncImage(track.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(track.artist, at).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsPrivacyScreen(navController: NavController) {
    val privacy by FriendsHub.privacy.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.friends_privacy)) },
            navigationIcon = { IconButton(onClick = navController::navigateUp) { Icon(painterResource(R.drawable.arrow_back), null) } },
        )
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp,
                bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { AudienceRow(stringResource(R.string.friends_privacy_now), privacy.nowPlaying) { FriendsHub.setPrivacy(privacy.copy(nowPlaying = it)) } }
            item { AudienceRow(stringResource(R.string.friends_privacy_history), privacy.history) { FriendsHub.setPrivacy(privacy.copy(history = it)) } }
            item { AudienceRow(stringResource(R.string.friends_privacy_playlists), privacy.playlists) { FriendsHub.setPrivacy(privacy.copy(playlists = it)) } }
            item { AudienceRow(stringResource(R.string.friends_privacy_stats), privacy.stats) { FriendsHub.setPrivacy(privacy.copy(stats = it)) } }
            item {
                SwitchRow(stringResource(R.string.friends_privacy_along), stringResource(R.string.friends_privacy_along_desc), privacy.listenAlong) {
                    FriendsHub.setPrivacy(privacy.copy(listenAlong = it))
                }
            }
            item {
                SwitchRow(stringResource(R.string.friends_privacy_discoverable), stringResource(R.string.friends_privacy_discoverable_desc), privacy.discoverable) {
                    FriendsHub.setPrivacy(privacy.copy(discoverable = it))
                }
            }
            item {
                Text(
                    stringResource(R.string.friends_privacy_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AudienceRow(title: String, value: FriendsHub.Audience, onChange: (FriendsHub.Audience) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            listOf(
                FriendsHub.Audience.NOBODY to R.string.friends_audience_nobody,
                FriendsHub.Audience.FRIENDS to R.string.friends_audience_friends,
                FriendsHub.Audience.EVERYONE to R.string.friends_audience_everyone,
            ).forEach { (audience, label) ->
                FilterChip(selected = value == audience, onClick = { onChange(audience) }, label = { Text(stringResource(label)) })
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
