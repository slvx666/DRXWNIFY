/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.catalog.MainAccount
import com.metrolist.music.constants.MetadataSource
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.PreferenceEntry
import com.metrolist.music.ui.component.PreferenceGroupTitle
import com.metrolist.music.ui.component.SwitchPreference
import com.metrolist.music.ui.component.serviceIconRes
import com.metrolist.music.ui.component.serviceNameRes
import com.metrolist.music.utils.rememberPreference

/**
 * After a login: back where it came from on a re-login; for an account linked the first time, on
 * to its setup ([AccountSetupScreen]) in place of the login page.
 */
fun NavController.afterAccountLinked(service: MetadataSource, relogin: Boolean, mainBefore: MetadataSource?) {
    if (relogin) {
        navigateUp()
        return
    }
    val route = currentDestination?.route
    navigate("account_setup/${service.name}/${mainBefore?.name ?: "NONE"}") {
        route?.let { popUpTo(it) { inclusive = true } }
    }
}

/**
 * The window right after an account was linked: is it the main one, does it get a library tab,
 * where likes go — and, when another account is the main one, what to bring over from it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSetupScreen(navController: NavController, service: MetadataSource, mainBefore: MetadataSource?) {
    // The default happens on arrival (leaving the screen keeps it): Spotify becomes the main
    // account, another service only when there was none.
    var applied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (applied) return@LaunchedEffect
        applied = true
        Catalog.awaitAccounts()
        if (MainAccount.defaultMakeMain(service, mainBefore)) MainAccount.choose(service)
    }
    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Icon(painterResource(serviceIconRes(service)), null, Modifier.size(40.dp), tint = com.metrolist.music.ui.theme.serviceColor(service))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    stringResource(R.string.account_linked_title, stringResource(serviceNameRes(service))),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.account_linked_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AccountCommonSettings(navController, service, setup = true)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { navController.navigateUp() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = com.metrolist.music.ui.theme.tuiButtonShape(),
        ) { Text(stringResource(R.string.account_setup_done)) }
        Spacer(Modifier.height(24.dp))
    }
    TopAppBar(
        title = { Text(stringResource(serviceNameRes(service))) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = {}) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}

/**
 * What every linked music account has: being the main one, a tab of its own in the library (off by
 * default), where likes go, bringing items over to the main account, and its statistics.
 */
@Composable
fun AccountCommonSettings(navController: NavController, service: MetadataSource, setup: Boolean = false) {
    val context = LocalContext.current
    val state by Catalog.state.collectAsState()
    val tabs by MainAccount.tabs.collectAsState()
    val likeMode by MainAccount.likeMode.collectAsState()
    val isMain = state.source == service
    val main = state.source
    var showLikeMode by remember { mutableStateOf(false) }

    PreferenceGroupTitle(title = stringResource(R.string.account_section_main))
    SwitchPreference(
        title = { Text(stringResource(R.string.account_is_main)) },
        description = when {
            isMain -> stringResource(R.string.account_is_main_on)
            main != null -> stringResource(R.string.account_is_main_off, stringResource(serviceNameRes(main)))
            else -> null
        },
        icon = { Icon(painterResource(R.drawable.star), null) },
        checked = isMain,
        onCheckedChange = { on ->
            if (on) {
                MainAccount.choose(service)
            } else {
                // The main account changes to another linked one; with none, this one stays.
                val other = state.connected.firstOrNull { it != service }
                if (other != null) {
                    MainAccount.choose(other)
                } else {
                    Toast.makeText(context, R.string.account_only_one, Toast.LENGTH_SHORT).show()
                }
            }
        },
    )
    if (!isMain) {
        SwitchPreference(
            title = { Text(stringResource(R.string.account_library_tab)) },
            description = stringResource(R.string.account_library_tab_desc),
            icon = { Icon(painterResource(R.drawable.library_music), null) },
            checked = service in tabs,
            onCheckedChange = { MainAccount.setTab(service, it) },
        )
    }
    PreferenceEntry(
        title = { Text(stringResource(R.string.like_mode_title)) },
        description = stringResource(likeModeLabel(likeMode)),
        icon = { Icon(painterResource(R.drawable.favorite), null) },
        onClick = { showLikeMode = true },
    )

    if (main != null && !isMain) {
        PreferenceGroupTitle(title = stringResource(R.string.account_section_transfer))
        PreferenceEntry(
            title = { Text(stringResource(R.string.transfer_from_to, stringResource(serviceNameRes(service)), stringResource(serviceNameRes(main)))) },
            description = stringResource(R.string.transfer_from_desc),
            icon = { Icon(painterResource(R.drawable.playlist_add), null) },
            onClick = { navController.navigate("account_transfer/${service.name}") },
        )
    }
    // The main account: playlists imported into the app (files, links) can be created in it too.
    if (isMain) {
        val imports by com.metrolist.music.playback.RemotePlaylists.localImports.collectAsState()
        if (imports > 0) {
            PreferenceGroupTitle(title = stringResource(R.string.account_section_transfer))
            PreferenceEntry(
                title = { Text(stringResource(R.string.push_local_title, stringResource(serviceNameRes(service)))) },
                description = pluralString(R.plurals.push_local_desc, imports),
                icon = { Icon(painterResource(R.drawable.playlist_add), null) },
                onClick = { navController.navigate("account_push_local") },
            )
        }
    }
    // Spotify's tops come over the web player's own query (the public API is rate limited for everyone).
    if (service == MetadataSource.YANDEX || service == MetadataSource.SPOTIFY) {
        PreferenceGroupTitle(title = stringResource(R.string.account_section_stats))
        PreferenceEntry(
            title = { Text(stringResource(R.string.service_stats_title)) },
            description = stringResource(if (service == MetadataSource.SPOTIFY) R.string.service_stats_desc_spotify else R.string.service_stats_desc_yandex),
            icon = { Icon(painterResource(R.drawable.stats), null) },
            onClick = { navController.navigate("account_stats/${service.name}") },
        )
    }

    if (showLikeMode) {
        EnumDialog(
            onDismiss = { showLikeMode = false },
            onSelect = {
                MainAccount.setLikeMode(it)
                showLikeMode = false
            },
            title = stringResource(R.string.like_mode_title),
            current = likeMode,
            values = MainAccount.LikeMode.entries.toList(),
            valueText = { stringResource(likeModeLabel(it)) },
        )
    }
}

@Composable
private fun pluralString(res: Int, count: Int): String =
    androidx.compose.ui.res.pluralStringResource(res, count, count)

fun likeModeLabel(mode: MainAccount.LikeMode): Int = when (mode) {
    MainAccount.LikeMode.MAIN_ONLY -> R.string.like_mode_main_only
    MainAccount.LikeMode.MAIN_AND_SOURCE -> R.string.like_mode_main_and_source
    MainAccount.LikeMode.ALL -> R.string.like_mode_all
}

/** The page of a linked Yandex Music or VK account in Integrations (Spotify has its own, richer one). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPageScreen(navController: NavController, service: MetadataSource) {
    var yandexToken by rememberPreference(com.metrolist.music.constants.YandexAccessTokenKey, "")
    var yandexUid by rememberPreference(com.metrolist.music.constants.YandexUidKey, "")
    var yandexName by rememberPreference(com.metrolist.music.constants.YandexUsernameKey, "")
    var vkToken by rememberPreference(com.metrolist.music.constants.VkAccessTokenKey, "")
    var vkUserId by rememberPreference(com.metrolist.music.constants.VkUserIdKey, "")
    val connected = when (service) {
        MetadataSource.YANDEX -> yandexToken.isNotEmpty()
        MetadataSource.VK -> vkToken.isNotEmpty()
        MetadataSource.SPOTIFY -> true
    }
    // Signed out from here: nothing left to set.
    LaunchedEffect(connected) { if (!connected) navController.navigateUp() }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))
        PreferenceEntry(
            title = { Text(stringResource(serviceNameRes(service))) },
            description = when (service) {
                MetadataSource.YANDEX -> stringResource(R.string.yandex_connected_as, yandexName.ifBlank { yandexUid })
                MetadataSource.VK -> stringResource(R.string.vk_connected) + (vkUserId.takeIf { it.isNotEmpty() }?.let { " · id$it" } ?: "")
                MetadataSource.SPOTIFY -> null
            },
            icon = { Icon(painterResource(serviceIconRes(service)), null, tint = com.metrolist.music.ui.theme.serviceColor(service)) },
        )
        AccountCommonSettings(navController, service)

        PreferenceGroupTitle(title = stringResource(R.string.account_section_account))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedButton(
                onClick = {
                    navController.navigate(if (service == MetadataSource.YANDEX) "settings/yandex/login" else "settings/vk/login")
                },
                shape = com.metrolist.music.ui.theme.tuiButtonShape(),
            ) { Text(stringResource(R.string.login_switch_account)) }
            OutlinedButton(
                onClick = {
                    when (service) {
                        MetadataSource.YANDEX -> {
                            yandexToken = ""
                            yandexUid = ""
                            yandexName = ""
                            com.metrolist.yandex.YandexMusic.accessToken = null
                            com.metrolist.yandex.YandexMusic.uid = null
                        }
                        MetadataSource.VK -> {
                            vkToken = ""
                            vkUserId = ""
                        }
                        MetadataSource.SPOTIFY -> Unit
                    }
                    MainAccount.setTab(service, false)
                    Catalog.invalidateCaches()
                },
                shape = com.metrolist.music.ui.theme.tuiButtonShape(),
            ) { Text(stringResource(R.string.action_logout)) }
        }
        Spacer(Modifier.height(24.dp))
    }
    TopAppBar(
        title = { Text(stringResource(serviceNameRes(service))) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = {}) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}
