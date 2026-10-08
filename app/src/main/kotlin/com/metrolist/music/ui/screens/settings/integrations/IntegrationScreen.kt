/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.IntegrationCard
import com.metrolist.music.ui.component.IntegrationCardItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.constants.DiscordTokenKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.constants.YandexAccessTokenKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationScreen(
    navController: NavController
) {
    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        val (discordToken) = rememberPreference(DiscordTokenKey, "")
        val (ytCookie) = rememberPreference(InnerTubeCookieKey, "")
        val (spotifyToken) = rememberPreference(SpotifyAccessTokenKey, "")
        val (yandexToken) = rememberPreference(YandexAccessTokenKey, "")
        var vkToken by rememberPreference(com.metrolist.music.constants.VkAccessTokenKey, "")
        var vkUserId by rememberPreference(com.metrolist.music.constants.VkUserIdKey, "")
        var showVkLogout by androidx.compose.runtime.saveable.rememberSaveable {
            androidx.compose.runtime.mutableStateOf(false)
        }
        if (showVkLogout) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showVkLogout = false },
                icon = { Icon(painterResource(R.drawable.vk_music), contentDescription = null) },
                title = { Text(stringResource(R.string.vk_music)) },
                text = {
                    Text(
                        stringResource(R.string.vk_connected) +
                            (vkUserId.takeIf { it.isNotEmpty() }?.let { " · id$it" } ?: ""),
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        vkToken = ""
                        vkUserId = ""
                        showVkLogout = false
                    }) { Text(stringResource(R.string.action_logout)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showVkLogout = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }

        // A check mark on the right marks services this app is already connected to.
        val connected: @Composable () -> Unit = {
            Icon(
                painter = painterResource(R.drawable.check),
                contentDescription = stringResource(R.string.login_connected),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        IntegrationCard(
            title = stringResource(R.string.general),
            items = listOf(
                IntegrationCardItem(
                    icon = painterResource(R.drawable.library_music),
                    title = { Text(stringResource(R.string.music_sources)) },
                    description = { Text(stringResource(R.string.integration_sources_description)) },
                    onClick = {
                        navController.navigate("settings/integrations/sources")
                    }
                ),
                IntegrationCardItem(
                    icon = painterResource(R.drawable.spotify),
                    title = { Text(stringResource(R.string.spotify_integration)) },
                    description = { Text(stringResource(R.string.integration_spotify_description)) },
                    trailingContent = if (spotifyToken.isNotEmpty()) connected else null,
                    onClick = {
                        navController.navigate("settings/integrations/spotify")
                    }
                ),
                IntegrationCardItem(
                    icon = painterResource(R.drawable.yandex_music),
                    title = { Text(stringResource(R.string.yandex_music)) },
                    description = { Text(stringResource(R.string.integration_yandex_description)) },
                    trailingContent = if (yandexToken.isNotEmpty()) connected else null,
                    onClick = {
                        navController.navigate(
                            if (yandexToken.isNotEmpty()) "settings/integrations/sources" else "settings/yandex/login",
                        )
                    }
                ),
                IntegrationCardItem(
                    icon = painterResource(R.drawable.vk_music),
                    title = { Text(stringResource(R.string.vk_music)) },
                    description = { Text(stringResource(R.string.integration_vk_description)) },
                    trailingContent = if (vkToken.isNotEmpty()) connected else null,
                    onClick = {
                        if (vkToken.isNotEmpty()) showVkLogout = true else navController.navigate("settings/vk/login")
                    }
                ),
                IntegrationCardItem(
                    icon = painterResource(R.drawable.youtube_music),
                    title = { Text(stringResource(R.string.youtube_music)) },
                    description = { Text(stringResource(R.string.integration_youtube_description)) },
                    trailingContent = if ("SAPISID" in parseCookieString(ytCookie)) connected else null,
                    onClick = {
                        navController.navigate("settings/integrations/youtube")
                    }
                ),
                IntegrationCardItem(
                    icon = painterResource(R.drawable.discord),
                    title = { Text(stringResource(R.string.discord_integration)) },
                    description = { Text(stringResource(R.string.integration_discord_description)) },
                    trailingContent = if (discordToken.isNotEmpty()) connected else null,
                    onClick = {
                        navController.navigate("settings/integrations/discord")
                    }
                ),
            )
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.integrations)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        }
    )
}
