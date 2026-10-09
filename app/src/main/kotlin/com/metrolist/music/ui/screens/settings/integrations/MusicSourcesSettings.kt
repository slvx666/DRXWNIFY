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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
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
import com.metrolist.music.ui.component.SwitchPreference
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
        val (bandcamp, setBandcamp) = rememberPreference(com.metrolist.music.constants.AudioSourceBandcampKey, true)
        val (audius, setAudius) = rememberPreference(com.metrolist.music.constants.AudioSourceAudiusKey, true)
        val (soulseek, setSoulseek) = rememberPreference(com.metrolist.music.constants.AudioSourceSoulseekKey, true)
        var slskUser by rememberPreference(com.metrolist.music.constants.SoulseekUsernameKey, "")
        var slskPass by rememberPreference(com.metrolist.music.constants.SoulseekPasswordKey, "")
        val (slskWifiOnly, setSlskWifiOnly) = rememberPreference(com.metrolist.music.constants.SoulseekWifiOnlyKey, true)
        var showSlskLogin by remember { mutableStateOf(false) }
        val (lossless, setLossless) = rememberPreference(com.metrolist.music.constants.AudioSourceLosslessKey, true)
        val losslessMirrorCount = com.metrolist.music.utils.RemoteConfig.config.collectAsState().value.losslessMirrors.size
        val slskReady = slskUser.isNotBlank() && slskPass.isNotBlank()
        var orderPref by rememberPreference(AudioSourceOrderKey, "")
        var vkToken by rememberPreference(VkAccessTokenKey, "")
        val (vkLikesToAccount, setVkLikesToAccount) =
            rememberPreference(com.metrolist.music.constants.VkLikesToAccountKey, true)
        // The shared VK account (from the remote config) stands in for a missing own one.
        val sharedVk = com.metrolist.music.utils.RemoteConfig.config.collectAsState().value.vkShared
        // Sources that need an account stay switched off (and locked) until the account is set.
        fun needsAccount(id: AudioProviderId) = when (id) {
            AudioProviderId.VK -> vkToken.isEmpty() && sharedVk == null
            AudioProviderId.SOULSEEK -> !slskReady
            AudioProviderId.LOSSLESS -> losslessMirrorCount == 0
            else -> false
        }
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
                AudioProviderId.BANDCAMP -> Triple(R.string.audio_source_bandcamp, R.string.audio_source_bandcamp_description, R.drawable.album)
                AudioProviderId.AUDIUS -> Triple(R.string.audio_source_audius, R.string.audio_source_audius_description, R.drawable.graphic_eq)
                AudioProviderId.SOULSEEK -> Triple(R.string.audio_source_soulseek, R.string.audio_source_soulseek_description, R.drawable.download)
                AudioProviderId.LOSSLESS -> Triple(R.string.audio_source_lossless, R.string.audio_source_lossless_description, R.drawable.graphic_eq)
            }
            val checked = when (id) {
                AudioProviderId.YOUTUBE -> youtube
                AudioProviderId.QOBUZ -> qobuz
                AudioProviderId.VK -> vk
                AudioProviderId.SOUNDCLOUD -> soundcloud
                AudioProviderId.BANDCAMP -> bandcamp
                AudioProviderId.AUDIUS -> audius
                AudioProviderId.SOULSEEK -> soulseek
                AudioProviderId.LOSSLESS -> lossless
            }
            val extra = when {
                id == AudioProviderId.VK && vkToken.isEmpty() && sharedVk == null -> "\n" + stringResource(R.string.vk_login_required)
                id == AudioProviderId.SOULSEEK && !slskReady -> "\n" + stringResource(R.string.soulseek_login_required)
                id == AudioProviderId.LOSSLESS && losslessMirrorCount == 0 -> "\n" + stringResource(R.string.lossless_no_mirrors)
                else -> ""
            }
            PreferenceEntry(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${stringResource(title)}")
                        // VK: the source with the most music and the best quality.
                        if (id == AudioProviderId.VK) {
                            Icon(
                                painterResource(R.drawable.crown),
                                contentDescription = null,
                                tint = androidx.compose.ui.graphics.Color(0xFFF5C542),
                                modifier = Modifier.padding(start = 6.dp).size(18.dp),
                            )
                        }
                    }
                },
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
                            // Soulseek can't work without an account: locked until it is set.
                            enabled = !needsAccount(id),
                            checked = checked && !needsAccount(id),
                            onCheckedChange = { enabled ->
                                when (id) {
                                    AudioProviderId.YOUTUBE -> { setYoutube(enabled); ResolverPreferences.youtubeEnabled = enabled }
                                    AudioProviderId.QOBUZ -> { setQobuz(enabled); ResolverPreferences.qobuzFallback = enabled }
                                    AudioProviderId.VK -> { setVk(enabled); ResolverPreferences.vkEnabled = enabled }
                                    AudioProviderId.SOUNDCLOUD -> { setSoundcloud(enabled); ResolverPreferences.soundCloudEnabled = enabled }
                                    AudioProviderId.BANDCAMP -> { setBandcamp(enabled); ResolverPreferences.bandcampEnabled = enabled }
                                    AudioProviderId.AUDIUS -> { setAudius(enabled); ResolverPreferences.audiusEnabled = enabled }
                                    AudioProviderId.SOULSEEK -> { setSoulseek(enabled); ResolverPreferences.soulseekEnabled = enabled }
                                    AudioProviderId.LOSSLESS -> { setLossless(enabled); ResolverPreferences.losslessEnabled = enabled }
                                }
                            },
                        )
                    }
                },
            )
            if (id == AudioProviderId.VK && vkToken.isEmpty()) {
                // Own account not linked: the login in a row of its own (with the demo explained),
                // so the source row keeps its text readable.
                PreferenceEntry(
                    title = { Text(stringResource(R.string.vk_login_own)) },
                    description = sharedVk?.let { stringResource(R.string.vk_shared_in_use, it.dailyTracks) },
                    icon = { Spacer(Modifier.size(24.dp)) },
                    trailingContent = {
                        OutlinedButton(onClick = { navController.navigate("settings/vk/login") }) {
                            Text(stringResource(R.string.action_login))
                        }
                    },
                )
            }
            if (id == AudioProviderId.VK && vkToken.isNotEmpty()) {
                // Where hearts on VK tracks go: the user's VK music, or the app's "Local" only.
                PreferenceEntry(
                    title = { Text(stringResource(R.string.vk_likes_to_account)) },
                    description = stringResource(
                        if (vkLikesToAccount) R.string.vk_likes_to_account_on else R.string.vk_likes_to_account_off,
                    ),
                    icon = { Spacer(Modifier.size(24.dp)) },
                    trailingContent = { Switch(checked = vkLikesToAccount, onCheckedChange = setVkLikesToAccount) },
                )
            }
            if (id == AudioProviderId.SOULSEEK) {
                PreferenceEntry(
                    title = { Text(if (slskReady) slskUser else stringResource(R.string.soulseek_login)) },
                    description = stringResource(R.string.soulseek_login_hint),
                    icon = { Spacer(Modifier.size(24.dp)) },
                    trailingContent = {
                        if (slskReady) {
                            OutlinedButton(onClick = {
                                slskUser = ""
                                slskPass = ""
                            }) { Text(stringResource(R.string.action_logout)) }
                        } else {
                            OutlinedButton(onClick = { showSlskLogin = true }) { Text(stringResource(R.string.action_login)) }
                        }
                    },
                )
                PreferenceEntry(
                    title = { Text(stringResource(R.string.soulseek_wifi_only)) },
                    description = stringResource(R.string.soulseek_wifi_only_description),
                    icon = { Spacer(Modifier.size(24.dp)) },
                    trailingContent = { Switch(checked = slskWifiOnly, onCheckedChange = setSlskWifiOnly) },
                )
            }
        }

        if (showSlskLogin) {
            val userLabel = stringResource(R.string.soulseek_username)
            val passLabel = stringResource(R.string.soulseek_password)
            var fields by remember {
                mutableStateOf(
                    listOf(
                        userLabel to androidx.compose.ui.text.input.TextFieldValue(slskUser),
                        passLabel to androidx.compose.ui.text.input.TextFieldValue(""),
                    ),
                )
            }
            com.metrolist.music.ui.component.TextFieldDialog(
                title = { Text(stringResource(R.string.soulseek_login)) },
                textFields = fields,
                onTextFieldsChange = { index, value ->
                    fields = fields.mapIndexed { i, pair -> if (i == index) pair.first to value else pair }
                },
                onDoneMultiple = { values ->
                    val user = values.getOrNull(0)?.trim().orEmpty()
                    val pass = values.getOrNull(1).orEmpty()
                    if (user.isNotBlank() && pass.isNotBlank()) {
                        slskUser = user
                        slskPass = pass
                    }
                },
                onDismiss = { showSlskLogin = false },
                extraContent = {
                    Text(
                        text = stringResource(R.string.soulseek_account_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                },
            )
        }

        if (!youtube && !qobuz && !vk && !soundcloud && !bandcamp && !audius && !soulseek) {
            Text(
                text = stringResource(R.string.audio_sources_all_disabled),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // ── Experimental search ──────────────────────────────────────────────────────────────────
        PreferenceGroupTitle(title = stringResource(R.string.search_sources_title))
        val (experimentalSearch, setExperimentalSearch) = rememberPreference(
            com.metrolist.music.constants.ExperimentalSearchEnabledKey,
            defaultValue = true,
        )
        SwitchPreference(
            title = { Text(stringResource(R.string.search_sources_setting)) },
            description = stringResource(R.string.search_sources_setting_description),
            icon = { Icon(painterResource(R.drawable.search_experimental), null) },
            checked = experimentalSearch,
            onCheckedChange = setExperimentalSearch,
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.audio_search_log)) },
            description = stringResource(R.string.audio_search_log_description),
            icon = { Icon(painterResource(R.drawable.info), null) },
            onClick = { navController.navigate("settings/integrations/sources/log") },
        )

        // Where the remote config (mirrors, shared VK account) is read from; empty = built-in hosts.
        val (configUrl, onConfigUrlChange) = com.metrolist.music.utils.rememberPreference(
            com.metrolist.music.utils.RemoteConfig.UrlOverrideKey, "",
        )
        var configDraft by remember(configUrl) { mutableStateOf(configUrl) }
        val remoteConfig by com.metrolist.music.utils.RemoteConfig.config.collectAsState()
        val context = androidx.compose.ui.platform.LocalContext.current
        val configScope = rememberCoroutineScope()
        androidx.compose.material3.OutlinedTextField(
            value = configDraft,
            onValueChange = { configDraft = it },
            label = { Text(stringResource(R.string.remote_config_url)) },
            supportingText = {
                Text(
                    stringResource(
                        R.string.remote_config_state,
                        remoteConfig.losslessMirrors.size,
                        stringResource(if (remoteConfig.vkShared != null) R.string.remote_config_vk_yes else R.string.remote_config_vk_no),
                    ),
                )
            },
            singleLine = true,
            trailingIcon = {
                androidx.compose.material3.IconButton(onClick = {
                    onConfigUrlChange(configDraft.trim())
                    configScope.launch {
                        kotlinx.coroutines.delay(300)
                        val ok = com.metrolist.music.utils.RemoteConfig.refresh(context)
                        android.widget.Toast.makeText(
                            context,
                            if (ok) R.string.remote_config_loaded else R.string.remote_config_failed,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) { Icon(painterResource(R.drawable.check), contentDescription = null) }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // For the app's owner: the VK account signed in right now becomes the shared demo account.
        // Gives the config file text to put on the host (a gist, the config repo); the token itself
        // never leaves the phone any other way.
        val (vkTokenNow) = com.metrolist.music.utils.rememberPreference(com.metrolist.music.constants.VkAccessTokenKey, "")
        if (vkTokenNow.isNotBlank()) {
            androidx.compose.material3.TextButton(
                onClick = {
                    val json = org.json.JSONObject().apply {
                        put("version", 1)
                        put("vkShared", org.json.JSONObject().put("token", vkTokenNow).put("dailyTracks", SHARED_VK_DAILY_TRACKS))
                        put("losslessMirrors", org.json.JSONArray(remoteConfig.losslessMirrors))
                        put("disabledSources", org.json.JSONArray(remoteConfig.disabledSources.toList()))
                    }.toString(2)
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("config.json", json))
                    android.widget.Toast.makeText(context, R.string.shared_vk_config_copied, android.widget.Toast.LENGTH_LONG).show()
                    runCatching {
                        context.startActivity(
                            android.content.Intent.createChooser(
                                android.content.Intent(android.content.Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(android.content.Intent.EXTRA_TEXT, json),
                                null,
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(stringResource(R.string.shared_vk_make_config)) }
        }
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

/** Tracks a phone may play a day through the shared demo VK account. */
private const val SHARED_VK_DAILY_TRACKS = 60
