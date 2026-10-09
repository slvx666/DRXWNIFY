/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import com.metrolist.music.R
import com.metrolist.music.constants.AudioSourceVkKey
import com.metrolist.music.constants.VkAccessTokenKey
import com.metrolist.music.resolver.AudioDiagnostics
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.ResolverPreferences
import com.metrolist.music.utils.rememberPreference

/** What the user sees on the cover when a track can't play: a plain reason and, if possible, a fix. */
private data class Explanation(
    val title: Int,
    val message: Int,
    val hint: Int? = null,
    val action: Int? = null,
    val actionRoute: String? = null,
)

private const val ROUTE_SOURCES = "settings/integrations/sources"
private const val ROUTE_VK_LOGIN = "settings/vk/login"
private const val ROUTE_LOG = "settings/integrations/sources/log"

@Composable
fun PlaybackError(
    error: PlaybackException,
    isLoggedIn: Boolean,
    retry: () -> Unit,
    mediaId: String? = null,
    onNavigate: ((String) -> Unit)? = null,
) {
    val (vkEnabled) = rememberPreference(AudioSourceVkKey, true)
    val (vkToken) = rememberPreference(VkAccessTokenKey, "")
    val problems = AudioDiagnostics.problemsFor(mediaId)

    val rawMessage = error.cause?.cause?.message ?: error.cause?.message ?: error.message.orEmpty()
    val rawRestricted = listOf("age", "LOGIN_REQUIRED", "AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "CONTENT_CHECK_REQUIRED", "country")
        .any { rawMessage.contains(it, ignoreCase = true) } || error.errorCode == PlaybackException.ERROR_CODE_REMOTE_ERROR
    val someSourcesOff = AudioProviderId.entries.any { !ResolverPreferences.isEnabled(it) }
    // With the shared account VK is already searched: no "connect" nudge then.
    val canConnectVk = vkEnabled && vkToken.isEmpty() &&
        com.metrolist.music.utils.RemoteConfig.config.collectAsState().value.vkShared == null

    // A way to fix it, best first: connect VK (large catalog) → enable disabled sources → YouTube login.
    fun withFix(base: Explanation, restricted: Boolean): Explanation = when {
        canConnectVk -> base.copy(hint = R.string.playback_hint_connect_vk, action = R.string.playback_action_connect_vk, actionRoute = ROUTE_VK_LOGIN)
        someSourcesOff -> base.copy(hint = R.string.playback_hint_enable_sources, action = R.string.playback_action_sources, actionRoute = ROUTE_SOURCES)
        restricted && !isLoggedIn -> base.copy(hint = R.string.playback_hint_youtube_login)
        else -> base
    }

    val explanation = when {
        problems?.failure == AudioDiagnostics.FailureKind.ALL_SOURCES_DISABLED ->
            Explanation(R.string.playback_err_all_disabled_title, R.string.playback_err_all_disabled,
                action = R.string.playback_action_sources, actionRoute = ROUTE_SOURCES)
        problems?.youtubeRestricted == true || (problems == null && rawRestricted) ->
            withFix(Explanation(R.string.playback_err_yt_restricted_title, R.string.playback_err_yt_restricted), restricted = true)
        problems?.youtubeUnreachable == true && problems.failure != null ->
            withFix(Explanation(R.string.playback_err_yt_unreachable_title, R.string.playback_err_yt_unreachable), restricted = false)
        problems?.failure != null ->
            withFix(Explanation(R.string.playback_err_not_found_title, R.string.playback_err_not_found), restricted = false)
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT ->
            Explanation(R.string.playback_err_network_title, R.string.playback_err_network)
        error.errorCode in 3000..4999 ->
            Explanation(R.string.playback_err_file_title, R.string.playback_err_file)
        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            withFix(Explanation(R.string.playback_err_http_title, R.string.playback_err_http), restricted = false)
        else -> Explanation(R.string.playback_err_generic_title, R.string.playback_err_generic)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.error),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(explanation.title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(explanation.message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        explanation.hint?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = retry,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(painter = painterResource(R.drawable.replay), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = stringResource(R.string.retry))
            }
            val route = explanation.actionRoute
            val action = explanation.action
            if (route != null && action != null && onNavigate != null) {
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = { onNavigate(route) }, shape = RoundedCornerShape(20.dp)) {
                    Text(text = stringResource(action), maxLines = 1)
                }
            }
        }
        if (onNavigate != null) {
            TextButton(onClick = { onNavigate(ROUTE_LOG) }) {
                Text(text = stringResource(R.string.playback_action_log), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
