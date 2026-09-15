/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.constants.AudioSourceOrderKey
import com.metrolist.music.constants.AudioSourceQobuzKey
import com.metrolist.music.constants.AudioSourceSoundCloudKey
import com.metrolist.music.constants.AudioSourceVkKey
import com.metrolist.music.constants.AudioSourceYouTubeKey
import com.metrolist.music.constants.EnableSpotifyKey
import com.metrolist.music.constants.MetadataSource
import com.metrolist.music.constants.PrimaryMetadataSourceKey
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.constants.SpotifyUsernameKey
import com.metrolist.music.constants.VkAccessTokenKey
import com.metrolist.music.constants.VkUserIdKey
import com.metrolist.music.constants.YandexAccessTokenKey
import com.metrolist.music.constants.YandexUidKey
import com.metrolist.music.constants.YandexUsernameKey
import com.metrolist.music.resolver.AudioDiagnostics
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.ResolverPreferences
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.PreferenceEntry
import com.metrolist.music.ui.component.PreferenceGroupTitle
import com.metrolist.music.ui.component.YandexMusicBadge
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.yandex.YandexMusic

/**
 * One place for "where music comes from":
 *  - Data source: the Spotify or the Yandex Music account (never mixed; with both signed in the user
 *    picks one, Spotify by default).
 *  - Audio sources: SoundCloud, YouTube, VK Music, Qobuz — each on by default, each explained, in a
 *    user-defined priority order, plus the audio search log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSourcesSettings(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") scrollBehavior: TopAppBarScrollBehavior,
) {
    val (spotifyToken) = rememberPreference(SpotifyAccessTokenKey, "")
    val (spotifyName) = rememberPreference(SpotifyUsernameKey, "")
    val (spotifyEnabled) = rememberPreference(EnableSpotifyKey, false)
    var yandexToken by rememberPreference(YandexAccessTokenKey, "")
    var yandexUid by rememberPreference(YandexUidKey, "")
    var yandexName by rememberPreference(YandexUsernameKey, "")
    var preferred by rememberEnumPreference(PrimaryMetadataSourceKey, MetadataSource.SPOTIFY)

    val spotifyConnected = spotifyToken.isNotEmpty()
    val yandexConnected = yandexToken.isNotEmpty()
    val catalogState by Catalog.state.collectAsState()
    var showSourceDialog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))

        // ── Data source ──────────────────────────────────────────────────────────────────────────
        PreferenceGroupTitle(title = stringResource(R.string.metadata_sources_title))
        InfoText(stringResource(R.string.metadata_sources_info))

        PreferenceEntry(
            title = { Text("Spotify") },
            description = when {
                !spotifyConnected -> stringResource(R.string.not_connected)
                !spotifyEnabled -> "$spotifyName · ${stringResource(R.string.spotify_enable)}"
                else -> spotifyName
            },
            icon = { Icon(painterResource(R.drawable.spotify), null) },
            onClick = { navController.navigate("settings/integrations/spotify") },
            trailingContent = if (!spotifyConnected) {
                {
                    OutlinedButton(onClick = { navController.navigate("settings/spotify/login") }) {
                        Text(stringResource(R.string.action_login))
                    }
                }
            } else null,
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.yandex_music)) },
            description = if (yandexConnected) yandexName.ifBlank { yandexUid } else stringResource(R.string.not_connected),
            icon = { YandexMusicBadge(size = 24.dp) },
            trailingContent = {
                if (yandexConnected) {
                    OutlinedButton(onClick = {
                        yandexToken = ""
                        yandexUid = ""
                        yandexName = ""
                        YandexMusic.accessToken = null
                        YandexMusic.uid = null
                        Catalog.invalidateCaches()
                    }) { Text(stringResource(R.string.action_logout)) }
                } else {
                    OutlinedButton(onClick = { navController.navigate("settings/yandex/login") }) {
                        Text(stringResource(R.string.action_login))
                    }
                }
            },
        )

        val bothConnected = spotifyConnected && yandexConnected
        PreferenceEntry(
            title = { Text(stringResource(R.string.data_source_account)) },
            description = when {
                bothConnected -> sourceName(preferred) + " — " + stringResource(R.string.data_source_account_description)
                catalogState.source != null -> sourceName(catalogState.source!!) + " — " + stringResource(R.string.data_source_account_single)
                else -> stringResource(R.string.data_source_account_single)
            },
            onClick = { showSourceDialog = true },
            isEnabled = bothConnected,
        )

        // ── Audio sources ────────────────────────────────────────────────────────────────────────
        PreferenceGroupTitle(title = stringResource(R.string.audio_sources_title))
        InfoText(stringResource(R.string.audio_sources_info))

        val (youtube, setYoutube) = rememberPreference(AudioSourceYouTubeKey, true)
        val (qobuz, setQobuz) = rememberPreference(AudioSourceQobuzKey, true)
        val (vk, setVk) = rememberPreference(AudioSourceVkKey, true)
        val (soundcloud, setSoundcloud) = rememberPreference(AudioSourceSoundCloudKey, true)
        var orderPref by rememberPreference(AudioSourceOrderKey, "")
        var vkToken by rememberPreference(VkAccessTokenKey, "")
        var vkUserId by rememberPreference(VkUserIdKey, "")
        val order = AudioProviderId.parseOrder(orderPref)

        fun move(id: AudioProviderId, delta: Int) {
            val list = order.toMutableList()
            val from = list.indexOf(id)
            val to = (from + delta).coerceIn(0, list.lastIndex)
            if (from < 0 || from == to) return
            list.removeAt(from)
            list.add(to, id)
            orderPref = list.joinToString(",") { it.name }
            ResolverPreferences.order = list
        }

        order.forEachIndexed { index, id ->
            val (title, description, icon) = when (id) {
                AudioProviderId.YOUTUBE -> Triple(R.string.audio_source_youtube, R.string.audio_source_youtube_description, R.drawable.play)
                AudioProviderId.QOBUZ -> Triple(R.string.audio_source_qobuz, R.string.audio_source_qobuz_description, R.drawable.graphic_eq)
                AudioProviderId.VK -> Triple(R.string.audio_source_vk, R.string.audio_source_vk_description, R.drawable.music_note)
                AudioProviderId.SOUNDCLOUD -> Triple(R.string.audio_source_soundcloud, R.string.audio_source_soundcloud_description, R.drawable.cloud)
            }
            val checked = when (id) {
                AudioProviderId.YOUTUBE -> youtube
                AudioProviderId.QOBUZ -> qobuz
                AudioProviderId.VK -> vk
                AudioProviderId.SOUNDCLOUD -> soundcloud
            }
            val extra = if (id == AudioProviderId.VK && vk && vkToken.isEmpty()) "\n" + stringResource(R.string.vk_login_required) else ""
            PreferenceEntry(
                title = { Text("${index + 1}. ${stringResource(title)}") },
                description = stringResource(description) + extra,
                icon = {
                    Icon(
                        painterResource(icon),
                        null,
                        tint = if (checked) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { move(id, -1) }, onLongClick = {}) {
                            Icon(painterResource(R.drawable.arrow_upward), contentDescription = stringResource(R.string.move_up))
                        }
                        IconButton(onClick = { move(id, 1) }, onLongClick = {}) {
                            Icon(painterResource(R.drawable.arrow_downward), contentDescription = stringResource(R.string.move_down))
                        }
                        Switch(
                            checked = checked,
                            onCheckedChange = { enabled ->
                                when (id) {
                                    AudioProviderId.YOUTUBE -> { setYoutube(enabled); ResolverPreferences.youtubeEnabled = enabled }
                                    AudioProviderId.QOBUZ -> { setQobuz(enabled); ResolverPreferences.qobuzFallback = enabled }
                                    AudioProviderId.VK -> { setVk(enabled); ResolverPreferences.vkEnabled = enabled }
                                    AudioProviderId.SOUNDCLOUD -> { setSoundcloud(enabled); ResolverPreferences.soundCloudEnabled = enabled }
                                }
                            },
                        )
                    }
                },
            )
            if (id == AudioProviderId.VK && vk) {
                PreferenceEntry(
                    title = { Text(if (vkToken.isNotEmpty()) stringResource(R.string.vk_connected) else stringResource(R.string.vk_login)) },
                    description = vkUserId.takeIf { vkToken.isNotEmpty() && it.isNotEmpty() }?.let { "id$it" }
                        ?: stringResource(R.string.vk_login_hint),
                    icon = { Spacer(Modifier.size(24.dp)) },
                    trailingContent = {
                        if (vkToken.isNotEmpty()) {
                            OutlinedButton(onClick = {
                                vkToken = ""
                                vkUserId = ""
                            }) { Text(stringResource(R.string.action_logout)) }
                        } else {
                            OutlinedButton(onClick = { navController.navigate("settings/vk/login") }) {
                                Text(stringResource(R.string.action_login))
                            }
                        }
                    },
                )
            }
        }

        if (!youtube && !qobuz && !vk && !soundcloud) {
            Text(
                text = stringResource(R.string.audio_sources_all_disabled),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        PreferenceEntry(
            title = { Text(stringResource(R.string.audio_search_log)) },
            description = stringResource(R.string.audio_search_log_description),
            icon = { Icon(painterResource(R.drawable.info), null) },
            onClick = { navController.navigate("settings/integrations/sources/log") },
        )
    }

    if (showSourceDialog) {
        EnumDialog(
            onDismiss = { showSourceDialog = false },
            onSelect = {
                preferred = it
                Catalog.invalidateCaches()
                showSourceDialog = false
            },
            title = stringResource(R.string.data_source_account),
            current = preferred,
            values = MetadataSource.entries.toList(),
            valueText = { sourceName(it) },
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.music_sources)) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}

/** The audio search journal ([AudioDiagnostics]): what every provider answered for each track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSearchLogScreen(navController: NavController) {
    val context = LocalContext.current
    val lines by AudioDiagnostics.lines.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.audio_search_log)) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
            actions = {
                TextButton(onClick = { AudioDiagnostics.clear() }) { Text(stringResource(R.string.audio_search_log_clear)) }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, AudioDiagnostics.asText())
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, null)) }
                }) { Text(stringResource(R.string.audio_search_log_share)) }
            },
        )
        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.audio_search_log_empty),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = if (" W " in line.take(16)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun sourceName(source: MetadataSource): String = when (source) {
    MetadataSource.SPOTIFY -> "Spotify"
    MetadataSource.YANDEX -> stringResource(R.string.yandex_music)
}

@Composable
private fun InfoText(text: String) {
    PreferenceEntry(
        title = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        icon = { Icon(painterResource(R.drawable.info), null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}
