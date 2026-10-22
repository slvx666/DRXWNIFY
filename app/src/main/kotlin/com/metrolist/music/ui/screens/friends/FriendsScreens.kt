/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.friends

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
internal fun LiveWhileShown() {
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

/** The name a profile is shown with: the developer always as drxwnslvt. */
internal fun displayName(pubkey: String, name: String?): String = when {
    FriendsHub.isDeveloper(pubkey) -> FriendsHub.DEVELOPER_NAME
    !name.isNullOrBlank() -> name
    else -> shortKey(pubkey)
}

/**
 * A round profile picture: the developer's from "About", a friend's own one (sent with their
 * profile), else their initial on a colour of their key.
 */
/** Friends' avatars (base64 JPEG in their profile), decoded once per run. */
private object AvatarCache {
    private val cache = android.util.LruCache<Int, androidx.compose.ui.graphics.ImageBitmap>(64)

    fun get(avatar: String) = cache.get(avatar.hashCode())

    fun decode(avatar: String): androidx.compose.ui.graphics.ImageBitmap? {
        get(avatar)?.let { return it }
        return runCatching {
            val bytes = android.util.Base64.decode(avatar, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()?.also { cache.put(avatar.hashCode(), it) }
    }
}

@Composable
fun FriendAvatar(pubkey: String, name: String, avatar: String?, size: Dp, modifier: Modifier = Modifier) {
    val shape = CircleShape
    if (FriendsHub.isDeveloper(pubkey) && avatar == null) {
        Image(
            painterResource(R.drawable.developer_avatar),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
        return
    }
    // Decoded off the main thread and kept: decoding every avatar while the tab opened made it stutter.
    val bitmap by androidx.compose.runtime.produceState(avatar?.let { AvatarCache.get(it) }, avatar) {
        if (value == null && avatar != null) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { AvatarCache.decode(avatar) }
        }
    }
    bitmap?.let { decoded ->
        Image(decoded, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(shape))
        return
    }
    val hue = (pubkey.take(6).toLongOrNull(16) ?: 0L) % 360
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Color.hsv(hue.toFloat(), 0.45f, 0.55f)),
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

@Composable
private fun DeveloperBadge() {
    Icon(
        painterResource(R.drawable.verified),
        contentDescription = stringResource(R.string.friends_developer),
        tint = Color(0xFFE0283A),
        modifier = Modifier.padding(start = 4.dp).size(16.dp),
    )
}

@Composable
private fun NameLine(
    pubkey: String,
    name: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    /** The developer's mark on this profile (shown on the developer's phone only). */
    verified: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(name, style = style, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (FriendsHub.isDeveloper(pubkey) || verified) DeveloperBadge()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(navController: NavController) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.friends_title)) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) { Icon(painterResource(R.drawable.arrow_back), null) }
            },
        )
        FriendsContent(navController, topPadding = 4.dp)
    }
}

/** Everything about friends, as a list: the "Friends" tab of "Together" and the standalone screen. */
@Composable
fun FriendsContent(navController: NavController, topPadding: Dp = 4.dp) {
    LiveWhileShown()
    val context = LocalContext.current
    val profile by FriendsHub.profile.collectAsState()
    val friends by FriendsHub.friends.collectAsState()
    val data by FriendsHub.data.collectAsState()
    val pinned by FriendsHub.pinned.collectAsState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val requests by FriendsHub.requests.collectAsState()
    val listeningTo by FriendsHub.listeningTo.collectAsState()
    val invite by FriendsHub.invite.collectAsState()
    var showBackup by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showHow by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The header first; the rest a couple of frames later, after the opening animation got going:
    // composing everything in the first frame made the tab stutter while it slid in.
    var restReady by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        repeat(3) { androidx.compose.runtime.withFrameNanos { } }
        restReady = true
    }

    // Pick → crop and zoom to a square (the editor used for playlist covers) → set.
    fun setAvatarFrom(uri: android.net.Uri) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val bitmap = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 320) sample *= 2
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }.getOrNull()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (bitmap == null) {
                    Toast.makeText(context, R.string.friends_avatar_failed, Toast.LENGTH_SHORT).show()
                } else {
                    FriendsHub.setAvatar(bitmap)
                }
            }
        }
    }
    var cropTarget by remember { mutableStateOf<android.net.Uri?>(null) }
    val cropAvatar = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            (res.data?.let { com.yalantis.ucrop.UCrop.getOutput(it) } ?: cropTarget)?.let(::setAvatarFrom)
        }
    }
    val colors = MaterialTheme.colorScheme
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val dest = java.io.File(context.cacheDir, "avatar_crop_${System.currentTimeMillis()}.jpg")
        val destUri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", dest)
        cropTarget = destUri
        val options = com.yalantis.ucrop.UCrop.Options().apply {
            setCompressionFormat(android.graphics.Bitmap.CompressFormat.JPEG)
            setCompressionQuality(90)
            setHideBottomControls(true)
            setCircleDimmedLayer(true)
            setShowCropGrid(false)
            setToolbarTitle(context.getString(R.string.friends_avatar_crop))
            setToolbarColor(colors.surface.toArgb())
            setToolbarWidgetColor(colors.onSurface.toArgb())
            setRootViewBackgroundColor(colors.surface.toArgb())
            setLogoColor(colors.surface.toArgb())
        }
        val intent = com.yalantis.ucrop.UCrop.of(uri, destUri)
            .withAspectRatio(1f, 1f)
            .withMaxResultSize(640, 640)
            .withOptions(options)
            .getIntent(context)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { cropAvatar.launch(intent) }.onFailure { setAvatarFrom(uri) }
    }

    LazyColumn(
        contentPadding = PaddingValues(
            top = topPadding,
            bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() + 16.dp,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        val me = profile
        if (me == null) {
            invite?.let { inv -> item { Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { InviteWaitingCard(inv) } } }
            item { Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { CreateProfileCard() } }
            return@LazyColumn
        }

        // Me: avatar, name, how others see me, and the two things done most — invite and add.
        item(key = "me") {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        FriendAvatar(
                            me.pubkey, me.name, me.avatar, 60.dp,
                            Modifier.clickable {
                                pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.BottomEnd).size(20.dp),
                        ) {
                            Icon(painterResource(R.drawable.edit), null, modifier = Modifier.padding(4.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { navController.navigate("friend/${me.pubkey}") }
                            .padding(vertical = 4.dp),
                    ) {
                        NameLine(me.pubkey, displayName(me.pubkey, me.name.ifBlank { stringResource(R.string.friends_no_name) }), MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.friends_view_as_others) + "  ›",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(painterResource(R.drawable.more_vert), stringResource(R.string.friends_more)) }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.friends_name)) },
                                leadingIcon = { Icon(painterResource(R.drawable.edit), null) },
                                onClick = { showMenu = false; showRename = true },
                            )
                            if (me.avatar != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.friends_avatar_remove)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.delete), null) },
                                    onClick = { showMenu = false; FriendsHub.setAvatar(null) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.friends_copy_code)) },
                                leadingIcon = { Icon(painterResource(R.drawable.content_copy), null) },
                                onClick = { showMenu = false; copy(context, me.code) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.friends_privacy)) },
                                leadingIcon = { Icon(painterResource(R.drawable.lock), null) },
                                onClick = { showMenu = false; navController.navigate("friends/privacy") },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.friends_backup_key)) },
                                leadingIcon = { Icon(painterResource(R.drawable.backup), null) },
                                onClick = { showMenu = false; showBackup = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.friends_use_other_key)) },
                                leadingIcon = { Icon(painterResource(R.drawable.restore), null) },
                                onClick = { showMenu = false; showRestore = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.friends_how_it_works_title)) },
                                leadingIcon = { Icon(painterResource(R.drawable.info), null) },
                                onClick = { showMenu = false; showHow = true },
                            )
                        }
                    }
                }
                if (me.isDeveloper) {
                    Text(
                        stringResource(R.string.friends_developer_public),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 12.dp)) {
                    Button(
                        onClick = {
                            val link = FriendsHub.inviteLink() ?: me.code
                            val text = context.getString(R.string.friends_share_text, link)
                            context.startActivity(
                                Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null),
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = com.metrolist.music.ui.theme.tuiButtonShape(),
                    ) {
                        Icon(painterResource(R.drawable.link), null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.friends_invite_short), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledTonalButton(onClick = { showAdd = true }, modifier = Modifier.weight(1f), shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                        Icon(painterResource(R.drawable.person_add), null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.friends_add_by_code_short), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        if (!restReady) return@LazyColumn
        listeningTo?.let { pub ->
            item(key = "listening_with") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.graphic_eq), null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        val name = displayName(pub, friends.firstOrNull { it.pubkey == pub }?.name ?: data[pub]?.name)
                        Text(stringResource(R.string.friends_listening_with, name), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = FriendsHub::stopListenAlong, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(R.string.friends_stop)) }
                    }
                }
            }
        }

        if (requests.isNotEmpty()) {
            item(key = "req_title") { SectionTitle(stringResource(R.string.friends_requests), requests.size) }
            requests.forEach { request ->
                item(key = "req_${request.pubkey}") {
                    val name = displayName(request.pubkey, request.name)
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FriendAvatar(request.pubkey, name, data[request.pubkey]?.avatar, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            NameLine(request.pubkey, name, MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.friends_wants_to_add),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { FriendsHub.dismissRequest(request.pubkey) }) {
                            Icon(painterResource(R.drawable.close), stringResource(R.string.friends_decline))
                        }
                        FilledTonalIconButton(onClick = { FriendsHub.addFriend(request.pubkey, request.name) }) {
                            Icon(painterResource(R.drawable.check), stringResource(R.string.friends_accept))
                        }
                    }
                }
            }
        }

        // Pinned friends first (in the order they were pinned), then the rest as before.
        val others = friends.filter { it.pubkey != me.pubkey }.let { list ->
            val byKey = list.associateBy { it.pubkey }
            pinned.mapNotNull { byKey[it] } + list.filter { it.pubkey !in pinned }
        }

        // Who is playing something right now, as a row of covers: the liveliest part of the tab.
        val playingNow = others.filter { data[it.pubkey]?.now?.playing == true }
        if (playingNow.isNotEmpty()) {
            item(key = "now_title") { SectionTitle(stringResource(R.string.friends_now_listening), null) }
            item(key = "now_row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(playingNow, key = { "now_${it.pubkey}" }) { friend ->
                        val d = data[friend.pubkey]
                        val now = d?.now ?: return@items
                        val name = displayName(friend.pubkey, d.name ?: friend.name)
                        Column(
                            Modifier
                                .width(124.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { navController.navigate("friend/${friend.pubkey}") },
                        ) {
                            Box {
                                AsyncImage(
                                    now.track.cover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(124.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                )
                                FriendAvatar(
                                    friend.pubkey, name, d.avatar, 32.dp,
                                    Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(6.dp)
                                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${now.track.title} · ${now.track.artist}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        item(key = "friends_title") { SectionTitle(stringResource(R.string.friends_title), others.size) }
        if (others.isEmpty()) {
            item(key = "friends_empty") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.group),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.friends_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        others.forEach { friend ->
            item(key = friend.pubkey) {
                val d = data[friend.pubkey]
                FriendRow(
                    pubkey = friend.pubkey,
                    name = displayName(friend.pubkey, d?.name ?: friend.name),
                    avatar = d?.avatar,
                    now = d?.now,
                    pinned = friend.pubkey in pinned,
                    onClick = { navController.navigate("friend/${friend.pubkey}") },
                    onLongClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        val nowPinned = FriendsHub.togglePinned(friend.pubkey)
                        Toast.makeText(context, if (nowPinned) R.string.friends_pinned else R.string.friends_unpinned, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    // An invite link opened in the app: accept or cancel.
    val pending = invite
    if (pending != null && profile != null) {
        val name = displayName(pending.pubkey, pending.name.ifBlank { data[pending.pubkey]?.name })
        AlertDialog(
            onDismissRequest = { FriendsHub.answerInvite(accept = false) },
            icon = { FriendAvatar(pending.pubkey, name, data[pending.pubkey]?.avatar, 56.dp) },
            title = { Text(stringResource(R.string.friends_invite_title, name)) },
            text = { Text(stringResource(R.string.friends_invite_body)) },
            confirmButton = {
                Button(onClick = {
                    FriendsHub.answerInvite(accept = true)
                    Toast.makeText(context, R.string.friends_added, Toast.LENGTH_SHORT).show()
                }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(R.string.friends_accept)) }
            },
            dismissButton = { TextButton(onClick = { FriendsHub.answerInvite(accept = false) }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(android.R.string.cancel)) } },
        )
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
            confirmButton = { TextButton(onClick = { copy(context, key); showBackup = false }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(R.string.friends_copy_code)) } },
            dismissButton = { TextButton(onClick = { showBackup = false }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (showRename) {
        var name by remember { mutableStateOf(profile?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.friends_name)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { FriendsHub.rename(name); showRename = false }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = { TextButton(onClick = { showRename = false }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (showAdd) {
        AddFriendDialog(onDismiss = { showAdd = false })
    }
    if (showHow) {
        AlertDialog(
            onDismissRequest = { showHow = false },
            icon = { Icon(painterResource(R.drawable.info), null) },
            title = { Text(stringResource(R.string.friends_how_it_works_title)) },
            text = { Text(stringResource(R.string.friends_how_it_works), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { showHow = false }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(android.R.string.ok)) } },
        )
    }
    if (showRestore) {
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showRestore = false },
            title = { Text(stringResource(R.string.friends_use_other_key)) },
            text = {
                Column {
                    Text(stringResource(R.string.friends_use_other_key_warning), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = key, onValueChange = { key = it.trim() }, label = { Text("nsec1…") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = key.isNotBlank(), onClick = {
                    if (FriendsHub.importProfile(key, "")) {
                        showRestore = false
                    } else {
                        Toast.makeText(context, R.string.friends_bad_key, Toast.LENGTH_SHORT).show()
                    }
                }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(R.string.friends_restore)) }
            },
            dismissButton = { TextButton(onClick = { showRestore = false }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun InviteWaitingCard(invite: FriendsHub.Invite) {
    val name = displayName(invite.pubkey, invite.name)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            FriendAvatar(invite.pubkey, name, null, 40.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.friends_invite_needs_profile, name), style = MaterialTheme.typography.bodyMedium)
        }
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
                enabled = (restoring && key.isNotBlank()) || (!restoring && name.isNotBlank()),
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
                shape = com.metrolist.music.ui.theme.tuiButtonShape(),
            ) { Text(stringResource(if (restoring) R.string.friends_restore else R.string.friends_create)) }
            TextButton(onClick = { restoring = !restoring }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                Text(stringResource(if (restoring) R.string.friends_create_new_instead else R.string.friends_have_key))
            }
        }
    }
}

/** A section heading of the Friends tab, with a count. */
@Composable
private fun SectionTitle(title: String, count: Int?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (count != null && count > 0) {
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** Add a friend by their code / invite link, or find them by name (if they allowed it). */
@Composable
private fun AddFriendDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val friends by FriendsHub.friends.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<FriendsHub.Found>?>(null) }
    val code = FriendsHub.parseCode(text)

    fun add(pub: String, name: String = "") {
        when {
            pub == FriendsHub.profile.value?.pubkey -> Toast.makeText(context, R.string.friends_own_code, Toast.LENGTH_SHORT).show()
            else -> {
                FriendsHub.addFriend(pub, name)
                Toast.makeText(context, R.string.friends_added, Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        }
    }

    fun search() {
        if (text.trim().length < 2) return
        searching = true
        results = null
        scope.launch {
            results = runCatching { FriendsHub.searchByName(text) }.getOrDefault(emptyList())
            searching = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.person_add), null) },
        title = { Text(stringResource(R.string.friends_add_title)) },
        text = {
            Column {
                Text(stringResource(R.string.friends_add_by_code_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; results = null },
                    placeholder = { Text(stringResource(R.string.friends_add_field)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                            clip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.let { text = it; results = null }
                        }) { Icon(painterResource(R.drawable.content_paste), stringResource(R.string.friends_paste)) }
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { if (code == null) search() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    searching -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.friends_searching), style = MaterialTheme.typography.bodySmall)
                    }
                    results?.isEmpty() == true -> Text(
                        stringResource(R.string.friends_search_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    results != null -> Column(Modifier.padding(top = 8.dp)) {
                        results.orEmpty().forEach { found ->
                            val already = friends.any { it.pubkey == found.pubkey }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                FriendAvatar(found.pubkey, found.name, found.avatar, 36.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(found.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (already) {
                                    Text(stringResource(R.string.friends_already), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    TextButton(onClick = { add(found.pubkey, found.name) }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(R.string.friends_add)) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (code != null) {
                TextButton(onClick = { add(code) }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(R.string.friends_add)) }
            } else {
                TextButton(enabled = text.trim().length >= 2 && !searching, onClick = { search() }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(R.string.friends_search)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FriendRow(
    pubkey: String,
    name: String,
    avatar: String?,
    now: FriendsHub.NowPlaying?,
    pinned: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val verifiedSet by FriendsHub.verified.collectAsState()
    val myProfile by FriendsHub.profile.collectAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box {
            FriendAvatar(pubkey, name, avatar, 50.dp)
            if (now?.playing == true) {
                // A small "on air" dot on the avatar.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(14.dp)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            NameLine(pubkey, name, MaterialTheme.typography.bodyLarge, verified = myProfile?.isDeveloper == true && pubkey in verifiedSet)
            val playing = now?.playing == true
            Text(
                when {
                    now == null -> stringResource(R.string.friends_nothing_shared)
                    playing -> "${now.track.artist} — ${now.track.title}"
                    else -> "${now.track.artist} — ${now.track.title} · ${ago(context, now.at)}"
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (now?.track?.cover != null) {
            Spacer(Modifier.width(10.dp))
            AsyncImage(
                model = now.track.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
            )
        }
        if (pinned) {
            Icon(
                painterResource(R.drawable.ic_push_pin),
                contentDescription = stringResource(R.string.friends_pinned),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp).size(18.dp),
            )
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
    val reviewsAccess by FriendsHub.reviewsAccess.collectAsState()
    val verifiedSet by FriendsHub.verified.collectAsState()
    val myProfile by FriendsHub.profile.collectAsState()
    val iAmDeveloper = myProfile?.isDeveloper == true
    val friend = friends.firstOrNull { it.pubkey == pubkey }
    val d = all[pubkey]
    val name = displayName(pubkey, d?.name ?: friend?.name?.ifBlank { null })
    var confirmRemove by remember { mutableStateOf(false) }
    var showAllHistory by rememberSaveable { mutableStateOf(false) }
    // Own profile before the first daily build: build it now, so the portrait is there to see.
    androidx.compose.runtime.LaunchedEffect(pubkey, myProfile?.pubkey) {
        if (pubkey == myProfile?.pubkey && d?.portrait == null) FriendsHub.publishPortraitIfStale()
    }

    fun play(tracks: List<FriendsHub.TrackRef>, index: Int) {
        val connection = playerConnection ?: return
        scope.launch { connection.playQueue(FriendPlayback.listQueue(tracks, index)) }
    }

    val menuState = com.metrolist.music.ui.component.LocalMenuState.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    /** The usual track menu for a friend's track (long press). */
    fun openMenu(track: FriendsHub.TrackRef) = scope.launch {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        val (metadata, catalogTrack) = FriendPlayback.menuTarget(track) ?: run {
            Toast.makeText(context, R.string.friends_track_menu_unavailable, Toast.LENGTH_SHORT).show()
            return@launch
        }
        menuState.show {
            com.metrolist.music.ui.menu.PlayerMenu(
                mediaMetadata = metadata,
                navController = navController,
                isCurrentTrack = false,
                spotifyTrack = catalogTrack,
                onDismiss = menuState::dismiss,
            )
        }
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    FriendAvatar(pubkey, name, d?.avatar, 72.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        NameLine(pubkey, name, MaterialTheme.typography.headlineSmall, verified = iAmDeveloper && pubkey in verifiedSet)
                        if (FriendsHub.isDeveloper(pubkey)) {
                            Text(stringResource(R.string.friends_developer), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    // drxwnslvt's album reviews, for the profiles he marked.
                    if (FriendsHub.isDeveloper(pubkey) && reviewsAccess) {
                        IconButton(onClick = { navController.navigate("drxw_reviews") }) {
                            Icon(painterResource(R.drawable.album), contentDescription = stringResource(R.string.reviews_title), modifier = Modifier.size(28.dp))
                        }
                    }
                    // The developer's own mark, seen by nobody else.
                    if (iAmDeveloper && !FriendsHub.isDeveloper(pubkey)) {
                        val marked = pubkey in verifiedSet
                        IconButton(onClick = {
                            FriendsHub.setVerified(pubkey, !marked)
                            Toast.makeText(context, if (marked) R.string.friends_unverified else R.string.friends_verified_set, Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                painterResource(R.drawable.verified),
                                contentDescription = stringResource(R.string.friends_verify),
                                tint = if (marked) Color(0xFFE0283A) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }
                // Three main genres of the last months, like roles in Discord.
                d?.portrait?.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                    Spacer(Modifier.height(10.dp))
                    GenreTags(tags)
                }
                if (friend == null && pubkey != FriendsHub.profile.value?.pubkey) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            FriendsHub.addFriend(pubkey, name)
                            Toast.makeText(context, R.string.friends_added, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = com.metrolist.music.ui.theme.tuiButtonShape(),
                    ) {
                        Icon(painterResource(R.drawable.person_add), null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.friends_add_to_friends))
                    }
                }
            }
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
                                    OutlinedButton(onClick = FriendsHub::stopListenAlong, modifier = Modifier.fillMaxWidth(), shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                                        Text(stringResource(R.string.friends_stop_listening))
                                    }
                                } else {
                                    Button(onClick = { FriendsHub.startListenAlong(pubkey) }, modifier = Modifier.fillMaxWidth(), shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                                        Text(stringResource(R.string.friends_listen_along))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            d?.portrait?.let { portrait ->
                item(key = "portrait") {
                    PortraitSection(portrait, onPlay = { list, i -> play(list, i) }, onMenu = { openMenu(it) })
                }
            }
            // From a friend on an older version: the old weekly numbers.
            if (d?.portrait == null) d?.stats?.let { s ->
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
            // The friend's liked tracks (the newest 500): a card here, the whole list as a playlist.
            d?.likes?.takeIf { it.tracks.isNotEmpty() }?.let { likes ->
                item(key = "likes") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { navController.navigate("friend_likes/$pubkey") },
                    ) {
                        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painterResource(R.drawable.favorite),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.friend_likes_title), fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (likes.total > likes.tracks.size) {
                                            stringResource(R.string.friend_likes_shown, likes.tracks.size, likes.total)
                                        } else {
                                            stringResource(R.string.friends_tracks_count, likes.tracks.size)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                IconButton(onClick = { play(likes.tracks, 0) }) { Icon(painterResource(R.drawable.play), null) }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                likes.tracks.take(6).forEach { t ->
                                    AsyncImage(
                                        t.cover,
                                        contentDescription = t.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            d?.history?.takeIf { it.isNotEmpty() }?.let { history ->
                item(key = "history") {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.friends_history), fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.friends_tracks_count, history.size), style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = { play(history, 0) }) { Icon(painterResource(R.drawable.play), null) }
                            }
                            (if (showAllHistory) history else history.take(5)).forEachIndexed { i, t ->
                                TrackRow(
                                    t,
                                    onClick = { play(history, i) },
                                    onLongClick = { openMenu(t) },
                                    at = t.at.takeIf { it > 0 }?.let { ago(context, it) },
                                )
                            }
                            if (history.size > 5) {
                                TextButton(onClick = { showAllHistory = !showAllHistory }, modifier = Modifier.padding(start = 4.dp), shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                                    Text(
                                        if (showAllHistory) stringResource(R.string.friends_show_less)
                                        else stringResource(R.string.friends_show_all, history.size),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (d == null || (d.history == null && d.likes == null && d.stats == null && d.portrait == null && d.now == null)) {
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
                }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(R.string.friends_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(track: FriendsHub.TrackRef, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, at: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
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
            modifier = Modifier.fillMaxSize(),
        ) {
            item { FriendsPrivacyItems() }
        }
    }
}

/** Who sees what: in Friends → Privacy and again in Settings → Privacy → Friends. */
@Composable
fun FriendsPrivacyItems(modifier: Modifier = Modifier) {
    val privacy by FriendsHub.privacy.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AudienceRow(stringResource(R.string.friends_privacy_now), privacy.nowPlaying) { FriendsHub.setPrivacy(privacy.copy(nowPlaying = it)) }
        AudienceRow(stringResource(R.string.friends_privacy_history), privacy.history) { FriendsHub.setPrivacy(privacy.copy(history = it)) }
        AudienceRow(stringResource(R.string.friends_privacy_playlists), privacy.playlists) { FriendsHub.setPrivacy(privacy.copy(playlists = it)) }
        AudienceRow(stringResource(R.string.friends_privacy_stats), privacy.stats) { FriendsHub.setPrivacy(privacy.copy(stats = it)) }
        SwitchRow(stringResource(R.string.friends_privacy_along), stringResource(R.string.friends_privacy_along_desc), privacy.listenAlong) {
            FriendsHub.setPrivacy(privacy.copy(listenAlong = it))
        }
        SwitchRow(stringResource(R.string.friends_privacy_discoverable), stringResource(R.string.friends_privacy_discoverable_desc), privacy.discoverable) {
            FriendsHub.setPrivacy(privacy.copy(discoverable = it))
        }
        Text(
            stringResource(R.string.friends_privacy_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AudienceRow(title: String, value: FriendsHub.Audience, onChange: (FriendsHub.Audience) -> Unit) {
    // "Verified" — only the people the developer marked — exists for the developer's profile alone.
    val profile by FriendsHub.profile.collectAsState()
    val developer = profile?.isDeveloper == true
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
        ) {
            listOfNotNull(
                FriendsHub.Audience.NOBODY to R.string.friends_audience_nobody,
                (FriendsHub.Audience.VERIFIED to R.string.friends_audience_verified).takeIf { developer || value == FriendsHub.Audience.VERIFIED },
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
        com.metrolist.music.ui.theme.ThemedSwitch(checked = checked, onCheckedChange = onChange)
    }
}
