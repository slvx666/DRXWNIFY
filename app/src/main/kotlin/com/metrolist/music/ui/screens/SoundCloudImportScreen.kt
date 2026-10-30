/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.playback.PlaylistImporter
import com.metrolist.music.resolver.AudioFallbackEngine
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.providers.SoundCloudAudioProvider
import kotlinx.coroutines.launch

/**
 * Bringing a SoundCloud profile over, once: its likes and its playlists become playlists here (by
 * a link to the profile, no sign-in, so only what the profile shows to everyone). Each track is
 * found in the catalog like any import; one the catalog hasn't goes in as the SoundCloud upload.
 * Importing again adds to the playlists made the first time instead of making new ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundCloudImportScreen(navController: NavController) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()
    var link by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf<SoundCloudAudioProvider.ImportProfile?>(null) }
    // Urls of what is picked; the likes go by the profile's own url.
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    val ownPlaylists by database.editablePlaylistsByCreateDateAsc().collectAsState(initial = emptyList())

    fun find() {
        val soundCloud = AudioFallbackEngine.provider(AudioProviderId.SOUNDCLOUD) as? SoundCloudAudioProvider ?: return
        loading = true
        failed = false
        profile = null
        scope.launch {
            val found = runCatching { soundCloud.importProfile(link) }.getOrNull()
            profile = found
            picked = found?.let { p -> setOfNotNull(p.url.takeIf { p.likes > 0 }) + p.sets.map { it.url } }.orEmpty()
            failed = found == null
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.soundcloud_import)) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) { Icon(painterResource(R.drawable.arrow_back), null) }
            },
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues().let { PaddingValues(bottom = it.calculateBottomPadding()) }),
        ) {
            Text(
                stringResource(R.string.soundcloud_import_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text(stringResource(R.string.soundcloud_profile_link)) },
                placeholder = { Text("soundcloud.com/…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = ::find,
                enabled = !loading && "soundcloud.com/" in link,
                modifier = Modifier.fillMaxWidth(),
                shape = com.metrolist.music.ui.theme.tuiButtonShape(),
            ) { Text(stringResource(R.string.soundcloud_find)) }

            if (loading) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            if (failed) {
                Text(stringResource(R.string.soundcloud_not_found), color = MaterialTheme.colorScheme.error)
            }

            profile?.let { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AsyncImage(
                        model = p.avatar,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(CircleShape),
                    )
                    Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                if (p.likes == 0 && p.sets.isEmpty()) {
                    Text(stringResource(R.string.soundcloud_nothing_public), style = MaterialTheme.typography.bodyMedium)
                }
                fun toggle(url: String) {
                    picked = if (url in picked) picked - url else picked + url
                }
                // Where each one goes, shown under it: a new playlist, or the one made last time.
                val likesName = stringResource(R.string.soundcloud_likes_playlist, p.name)
                fun existing(name: String) = ownPlaylists.firstOrNull { it.playlist.name == name }?.id
                if (p.likes > 0) {
                    PickRow(
                        title = stringResource(R.string.soundcloud_likes),
                        count = p.likes,
                        destination = likesName,
                        intoExisting = existing(likesName) != null,
                        checked = p.url in picked,
                        onToggle = { toggle(p.url) },
                    )
                }
                p.sets.forEach { set ->
                    PickRow(
                        title = set.title,
                        count = set.trackCount,
                        destination = set.title,
                        intoExisting = existing(set.title) != null,
                        checked = set.url in picked,
                        onToggle = { toggle(set.url) },
                    )
                }
                if (p.likes > 0 || p.sets.isNotEmpty()) {
                    Button(
                        onClick = {
                            val imports = buildList {
                                if (p.url in picked) add(likesName to p.url)
                                p.sets.filter { it.url in picked }.forEach { add(it.title to it.url) }
                            }.map { (name, url) ->
                                // Imported before: the same playlist gets what is new instead of a copy.
                                PlaylistImporter.Queued(name, url, existing(name))
                            }
                            PlaylistImporter.enqueue(context, imports)
                            navController.navigate("import_playlist") { popUpTo("soundcloud_import") { inclusive = true } }
                        },
                        enabled = picked.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = com.metrolist.music.ui.theme.tuiButtonShape(),
                    ) { Text(stringResource(R.string.soundcloud_import_start, picked.size)) }
                    Text(
                        stringResource(R.string.soundcloud_import_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PickRow(
    title: String,
    count: Int,
    destination: String,
    intoExisting: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(if (intoExisting) R.string.soundcloud_goes_existing else R.string.soundcloud_goes_new, destination),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            androidx.compose.ui.res.pluralStringResource(R.plurals.soundcloud_tracks, count, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
