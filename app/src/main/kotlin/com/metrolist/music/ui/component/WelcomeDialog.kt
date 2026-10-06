/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.metrolist.music.R

const val DRXWNIFY_GITHUB_URL = "https://github.com/slvx666/DRXWNIFY"
private val SpotifyGreen = Color(0xFF1ED760)
val YandexMusicYellow = Color(0xFFFFCC00)

/**
 * First-launch welcome: explains what the app is for (metadata + library from Spotify or Yandex Music,
 * audio found in parallel across YouTube / Qobuz / VK / SoundCloud, one-tap downloads with proper tags),
 * lets the user pick which account to connect and links the author's GitHub.
 */
@Composable
fun WelcomeDialog(
    onConnectSpotify: () -> Unit,
    onConnectYandex: () -> Unit,
    onDismiss: () -> Unit,
    /** Already connected: no account picker, just a "Continue" button. */
    accountsLinked: Boolean = false,
) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(SpotifyGreen, Color(0xFF0B6E3A)))),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.spotify),
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.welcome_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.welcome_subtitle_sources),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))

                WelcomePoint(R.drawable.library_music, R.string.welcome_point_connect_sources)
                WelcomePoint(R.drawable.search, R.string.welcome_point_match_sources)
                WelcomePoint(R.drawable.download, R.string.welcome_point_download_sources)
                WelcomePoint(R.drawable.favorite, R.string.welcome_point_sync_sources)

                Spacer(Modifier.height(12.dp))

                // For people worried about privacy: what happens to their data, in plain words.
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.lock),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.welcome_privacy_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.welcome_privacy_text),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Author's GitHub card.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, DRXWNIFY_GITHUB_URL.toUri())
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.github),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.welcome_github_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "github.com/slvx666/DRXWNIFY",
                            style = MaterialTheme.typography.bodySmall,
                            color = SpotifyGreen,
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.arrow_forward),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }

                if (accountsLinked) {
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text(stringResource(R.string.welcome_continue), fontWeight = FontWeight.Bold)
                    }
                    return@Column
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.welcome_choose_account),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onConnectSpotify,
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(painterResource(R.drawable.spotify), null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.welcome_connect_spotify), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onConnectYandex,
                    colors = ButtonDefaults.buttonColors(containerColor = YandexMusicYellow, contentColor = Color.Black),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    YandexMusicBadge(size = 20.dp, background = Color.Black, foreground = YandexMusicYellow)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.welcome_connect_yandex), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.welcome_later))
                }
            }
        }
    }
}

/** Small round "Я" badge standing in for the Yandex Music logo (no brand asset is bundled). */
@Composable
fun YandexMusicBadge(
    size: androidx.compose.ui.unit.Dp = 24.dp,
    background: Color = YandexMusicYellow,
    foreground: Color = Color.Black,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
    ) {
        Text(
            text = "Я",
            color = foreground,
            fontWeight = FontWeight.Black,
            fontSize = androidx.compose.ui.unit.TextUnit(size.value * 0.6f, androidx.compose.ui.unit.TextUnitType.Sp),
            lineHeight = androidx.compose.ui.unit.TextUnit(size.value * 0.6f, androidx.compose.ui.unit.TextUnitType.Sp),
        )
    }
}

@Composable
private fun WelcomePoint(icon: Int, text: Int) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(SpotifyGreen.copy(alpha = 0.15f)),
        ) {
            Icon(painterResource(icon), null, tint = SpotifyGreen, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
