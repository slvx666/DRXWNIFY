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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.R
import com.metrolist.music.friends.ChatHub
import com.metrolist.music.friends.ChatMusic
import com.metrolist.music.friends.FriendsHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a share button opens: send it as a file (when it's downloaded), as a link (the system's
 * share), or to a chat ("Forward…" on the Chats tab, then a tap on a conversation).
 */
object ShareSheet {
    data class Request(
        val title: String,
        /** The cards it becomes in a chat (empty: not something a chat can show, e.g. a VK track). */
        val music: List<ChatMusic>,
        /** The text a link share sends, null when there's no link. */
        val linkText: String?,
        /** The downloaded files' song ids (looked up when the sheet opens), null when there are none. */
        val files: (suspend () -> List<String>)? = null,
        /** A single track's id in the app: its bitrate and format show under the title. */
        val mediaId: String? = null,
        /** The tracks' song ids (rows already written), null when they can't go into a playlist. */
        val playlistSongIds: (suspend () -> List<String>)? = null,
    )

    val request = MutableStateFlow<Request?>(null)

    /** "To a playlist" was picked: the playlist picker is open for these song ids. */
    val playlistPick = MutableStateFlow<(suspend () -> List<String>)?>(null)

    fun open(
        title: String,
        music: List<ChatMusic>,
        linkText: String?,
        files: (suspend () -> List<String>)? = null,
        mediaId: String? = null,
        playlistSongIds: (suspend () -> List<String>)? = null,
    ) {
        request.value = Request(title, music, linkText, files, mediaId, playlistSongIds)
    }

    fun close() {
        request.value = null
    }

    /** A track from the player: its card, its link, and its file when [downloadedId] is set. */
    fun openTrack(meta: com.metrolist.music.models.MediaMetadata, catalogId: String?, downloadedId: String?) {
        val by = meta.artists.joinToString { it.name }
        val card = catalogId?.let { ChatMusic(com.metrolist.music.utils.AppLinks.Kind.TRACK, it, meta.title, by, meta.thumbnailUrl, meta.duration * 1000L) }
            ?: ChatMusic.ofMedia(meta.id, meta.title, by, meta.thumbnailUrl, meta.duration * 1000L)
        open(
            title = if (by.isNotBlank()) "$by — ${meta.title}" else meta.title,
            music = listOfNotNull(card),
            linkText = com.metrolist.music.ui.menu.shareLinkOf(meta, catalogId),
            files = downloadedId?.let { id -> { listOf(id) } },
            mediaId = meta.id,
        )
    }
}

/** Shown over everything (from MainActivity) while a share is asked for. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheetHost(navController: NavController) {
    val request by ShareSheet.request.collectAsState()
    val playlistPick by ShareSheet.playlistPick.collectAsState()
    playlistPick?.let { songIds ->
        // A fresh picker per request: the dialog remembers the ids it was opened with.
        androidx.compose.runtime.key(songIds) {
            com.metrolist.music.ui.menu.AddToPlaylistDialog(
                isVisible = true,
                onGetSong = { songIds() },
                onDismiss = { ShareSheet.playlistPick.value = null },
            )
        }
    }
    val r = request ?: return
    val context = LocalContext.current
    val downloadUtil = LocalDownloadUtil.current
    val scope = rememberCoroutineScope()
    val profile by FriendsHub.profile.collectAsState()
    val fileIds by produceState<List<String>?>(null, r) {
        value = r.files?.let { lookup -> withContext(Dispatchers.IO) { runCatching { lookup() }.getOrDefault(emptyList()) } }.orEmpty()
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { ShareSheet.close() }
    }

    ModalBottomSheet(onDismissRequest = ShareSheet::close, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                r.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            r.mediaId?.let { ShareQualityLine(it, Modifier.padding(horizontal = 24.dp, vertical = 2.dp)) }
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            ) {
                if (r.music.isNotEmpty() && profile != null) {
                    Option(R.drawable.chat_bubble, stringResource(R.string.share_to_chats)) {
                        ChatHub.startForward(r.music)
                        dismiss()
                        runCatching { navController.navigate("chats") }
                    }
                }
                val ids = fileIds
                if (!ids.isNullOrEmpty()) {
                    Option(R.drawable.download, stringResource(R.string.share_as_file)) {
                        val appContext = context.applicationContext
                        scope.launch { com.metrolist.music.utils.shareDownloadedFiles(appContext, downloadUtil.downloadExporter, ids) }
                        dismiss()
                    }
                }
                r.playlistSongIds?.let { songIds ->
                    Option(R.drawable.playlist_add, stringResource(R.string.selection_to_playlist)) {
                        ShareSheet.playlistPick.value = songIds
                        dismiss()
                    }
                }
                r.linkText?.let { link ->
                    Option(R.drawable.link, stringResource(R.string.share_as_link)) {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                        context.startActivity(Intent.createChooser(send, null))
                        dismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun Option(icon: Int, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(26.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * Under the title: the track's bitrate (in its quality colour) and format — "320 kbps · MP3".
 * Genuine lossless says "FLAC" in shimmering gold. Nothing when neither is known yet.
 */
@Composable
private fun ShareQualityLine(mediaId: String, modifier: Modifier = Modifier) {
    val database = com.metrolist.music.LocalDatabase.current
    val format by androidx.compose.runtime.remember(mediaId) { database.format(mediaId) }.collectAsState(initial = null)
    // No format record (never played here): what is known of the chosen version.
    val chosenKbps by produceState<Int?>(null, mediaId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                com.metrolist.music.resolver.AudioFallbackEngine.currentChoice(mediaId, database.getSongByIdBlocking(mediaId))
                    ?.let { com.metrolist.music.resolver.AudioFallbackEngine.knownBitrate(it.provider, it.trackId) }
            }.getOrNull()
        }
    }
    val f = format
    val kbps = f?.bitrate?.takeIf { it > 0 }?.let { AudioQualityLevel.kbps(it) } ?: chosenKbps
    val codec = f?.let { AudioQualityLevel.codecName(it.mimeType, it.codecs) }
    val lossless = codec.equals("FLAC", ignoreCase = true) || codec.equals("ALAC", ignoreCase = true)
    val verdict by com.metrolist.music.playback.SpectrumCheck.verdictFlow(mediaId).collectAsState(initial = null)
    val gold = lossless && verdict?.upscaled != true
    // How people know the file: the container for AAC (m4a), else the codec.
    val formatName = when {
        f == null -> null
        codec == "AAC" -> "M4A"
        codec != null -> codec.uppercase()
        else -> f.mimeType.substringAfter('/').uppercase().takeIf { it.isNotBlank() }
    }
    if (kbps == null && formatName == null) return
    val style = MaterialTheme.typography.labelLarge
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        kbps?.let {
            Text(
                "$it kbps",
                style = style,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = if (gold) LosslessGold else com.metrolist.music.ui.dialog.bitrateColor(it),
            )
        }
        if (kbps != null && formatName != null) Text(" · ", style = style, color = dim)
        formatName?.let { name ->
            if (gold) GoldShimmerText(name, style) else Text(name, style = style, color = dim)
        }
    }
}

/** Lossless in gold with a light sweeping across it. */
@Composable
private fun GoldShimmerText(text: String, style: androidx.compose.ui.text.TextStyle) {
    val sweep = androidx.compose.animation.core.rememberInfiniteTransition(label = "gold")
    val x by sweep.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1800, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "goldSweep",
    )
    val light = androidx.compose.ui.graphics.Color(0xFFFFF1C1)
    val width = 160f
    val brush = androidx.compose.ui.graphics.Brush.linearGradient(
        colors = listOf(LosslessGold, light, LosslessGold),
        start = androidx.compose.ui.geometry.Offset(x * width - width / 2, 0f),
        end = androidx.compose.ui.geometry.Offset(x * width + width / 2, 0f),
    )
    Text(text, style = style.copy(brush = brush, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
}
