/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.friends

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.friends.ChatHub
import com.metrolist.music.friends.ChatMessage
import com.metrolist.music.friends.ChatMusic
import com.metrolist.music.friends.ChatPreview
import com.metrolist.music.friends.ChatReaction
import com.metrolist.music.friends.ChatReactions
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import com.metrolist.music.friends.FriendPlayback
import com.metrolist.music.friends.FriendsHub
import com.metrolist.music.friends.Stickers
import com.metrolist.music.utils.AppLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ── Helpers ──────────────────────────────────────────────────────────────────────────────────────

private val zone: ZoneId get() = ZoneId.systemDefault()
private fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
private fun clock(ms: Long): String = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(zone))

/** The chat list's time: today the clock, this week the day, else the date. */
private fun listTime(ms: Long): String {
    val day = dateOf(ms)
    val today = LocalDate.now(zone)
    return when {
        day == today -> clock(ms)
        day.isAfter(today.minusDays(6)) -> DateTimeFormatter.ofPattern("EE", Locale.getDefault()).format(day)
        else -> DateTimeFormatter.ofPattern("dd.MM.yy").format(day)
    }
}

private fun dayTitle(context: Context, day: LocalDate): String {
    val today = LocalDate.now(zone)
    return when (day) {
        today -> context.getString(R.string.chat_today)
        today.minusDays(1) -> context.getString(R.string.chat_yesterday)
        else -> DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMMM" else "d MMMM yyyy", Locale.getDefault()).format(day)
    }
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** A friend's name for the chats (their name in Friends, else what their profile says). */
@Composable
private fun peerName(peer: String): String {
    val friends by FriendsHub.friends.collectAsState()
    val data by FriendsHub.data.collectAsState()
    // Someone who isn't a friend (writing to the developer) is known by the name in their request.
    val requests by FriendsHub.requests.collectAsState()
    return displayName(
        peer,
        friends.firstOrNull { it.pubkey == peer }?.name?.takeIf { it.isNotBlank() }
            ?: data[peer]?.name
            ?: requests.firstOrNull { it.pubkey == peer }?.name?.takeIf { it.isNotBlank() },
    )
}

/** Friends typing to me now (refreshed every second, the signal lasts a few seconds). */
@Composable
private fun typingNow(): Set<String> {
    val typing by ChatHub.typing.collectAsState()
    val now by produceState(SystemClock.elapsedRealtime(), typing) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    return typing.filterValues { it > now }.keys
}

// ── The Chats tab ────────────────────────────────────────────────────────────────────────────────

/** Conversations, newest first, then the friends with none yet (a tap starts one). */
@Composable
fun ChatsContent(navController: NavController) {
    val profile by FriendsHub.profile.collectAsState()
    // Null until the database answers: the list is drawn once, complete, instead of sliding into place.
    val loaded by ChatHub.previewList.collectAsState()
    val friends by FriendsHub.friends.collectAsState()
    val typing = typingNow()
    LiveWhileShown()
    LaunchedEffect(profile?.pubkey) { if (profile != null) ChatHub.refreshPeers() }

    if (profile == null) {
        EmptyNote(stringResource(R.string.chat_need_profile))
        return
    }
    val previews = loaded ?: return
    val withChat = previews.map { it.last.peer }.toSet()
    val others = friends.filter { it.pubkey !in withChat }
    LazyColumn(
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (previews.isEmpty()) {
            item(key = "empty") { EmptyNote(stringResource(R.string.chat_no_chats), Modifier.padding(top = 24.dp, bottom = 8.dp)) }
        }
        items(previews, key = { it.last.peer }) { preview ->
            ChatRow(preview, typing = preview.last.peer in typing, modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                navController.navigate("chat/${preview.last.peer}")
            }
        }
        if (others.isNotEmpty()) {
            item(key = "write_to") {
                Text(
                    stringResource(R.string.chat_write_to),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp),
                )
            }
            items(others, key = { "f_" + it.pubkey }) { friend ->
                val name = peerName(friend.pubkey)
                val data by FriendsHub.data.collectAsState()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate("chat/${friend.pubkey}") }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    PeerAvatar(friend.pubkey, name, 44.dp)
                    Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp).weight(1f))
                    Icon(painterResource(R.drawable.chat_bubble), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun ChatRow(preview: ChatPreview, typing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val m = preview.last
    val name = peerName(m.peer)
    val data by FriendsHub.data.collectAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        PeerAvatar(m.peer, name, 54.dp)
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (m.mine) Ticks(m, Modifier.padding(end = 4.dp))
                Text(listTime(m.ts), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                val text = when {
                    typing -> stringResource(R.string.chat_typing)
                    m.mine -> stringResource(R.string.chat_you) + ": " + ChatHub.preview(context, m)
                    else -> ChatHub.preview(context, m)
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (typing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AnimatedVisibility(preview.unread > 0, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .heightIn(min = 22.dp)
                            .widthIn(min = 22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 6.dp),
                    ) {
                        Text(preview.unread.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Clock while sending, one tick when a relay has it, two when read, a red mark when it didn't go. */
@Composable
private fun Ticks(m: ChatMessage, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    val (icon, color, label) = when {
        m.state == ChatMessage.STATE_FAILED -> Triple(R.drawable.error, MaterialTheme.colorScheme.error, R.string.chat_state_failed)
        m.state == ChatMessage.STATE_SENDING -> Triple(R.drawable.chat_schedule, tint.copy(alpha = 0.7f), R.string.chat_state_sending)
        m.read -> Triple(R.drawable.chat_done_all, tint, R.string.chat_state_read)
        else -> Triple(R.drawable.check, tint.copy(alpha = 0.8f), R.string.chat_state_sent)
    }
    Icon(painterResource(icon), stringResource(label), tint = color, modifier = modifier.size(16.dp))
}

/** A friend's avatar, with the "on air" dot (as in Friends) while they are playing something. */
@Composable
private fun PeerAvatar(peer: String, name: String, size: androidx.compose.ui.unit.Dp) {
    val data by FriendsHub.data.collectAsState()
    val d = data[peer]
    Box {
        FriendAvatar(peer, name, d?.avatar, size)
        androidx.compose.animation.AnimatedVisibility(
            visible = d?.now?.isLive(com.metrolist.music.ui.screens.friends.rememberLiveClock()) == true,
            enter = androidx.compose.animation.scaleIn() + fadeIn(),
            exit = androidx.compose.animation.scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Box(
                Modifier
                    .size(size * 0.27f)
                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

// ── A conversation ───────────────────────────────────────────────────────────────────────────────

private sealed interface Line {
    val key: String

    /** [m] the newest of [batch] (tracks sent together, oldest first; else just [m]). */
    data class Msg(val m: ChatMessage, val batch: List<ChatMessage>, val firstOfGroup: Boolean) : Line {
        // The oldest stays the key while the rest of a batch comes in.
        override val key = "m_" + batch.first().id
    }

    data class Day(val day: LocalDate) : Line {
        override val key = "d_$day"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(navController: NavController, peer: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Drawn at once from the last look, while the database answers; null until it first has.
    val cached = remember(peer) { ChatHub.cachedMessages(peer) }
    val loaded by remember(peer) { ChatHub.messages(peer) }.collectAsState(initial = cached)
    val messages = loaded.orEmpty()
    // The chat's tracks top to bottom (the database gives newest first), read only when one is
    // started: a lambda that never changes, so new messages don't recompose the list for it.
    val latestMessages = androidx.compose.runtime.rememberUpdatedState(messages)
    val chatTracks: () -> List<Pair<String, ChatMusic>> = remember {
        {
            latestMessages.value.asReversed().flatMap { msg ->
                ChatMusic.fromJson(msg.music)?.takeIf { it.kind == AppLinks.Kind.TRACK }?.let { listOf(msg.id to it) }.orEmpty()
            }
        }
    }
    val reactions by remember(peer) { ChatHub.reactions(peer) }.collectAsState(initial = emptyMap())
    val me = FriendsHub.profile.collectAsState().value?.pubkey
    val name = peerName(peer)
    val data by FriendsHub.data.collectAsState()
    val peers by ChatHub.peers.collectAsState()
    val listState = rememberLazyListState()
    val playerConnection = LocalPlayerConnection.current
    val hasPlayer = (playerConnection?.mediaMetadata ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState().value != null

    DisposableEffect(peer) {
        ChatHub.opened(peer)
        onDispose { ChatHub.opened(null) }
    }
    LiveWhileShown()
    // Sent from here: the list goes to the newest message even if it was scrolled up.
    val jumpToNewest = remember { BooleanArray(1) }
    // New messages read while the conversation is open.
    LaunchedEffect(messages.firstOrNull()?.id) {
        if (messages.any { !it.mine && !it.read }) ChatHub.markRead(peer)
    }

    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    // Bumped by a swipe or "Reply": the field takes the focus and the keyboard comes up.
    var replyFocus by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<MenuTarget?>(null) }
    BackHandler(enabled = menuFor != null) { menuFor = null }
    // The bubble stops being followed once its menu is gone.
    DisposableEffect(menuFor) {
        val open = menuFor
        onDispose { open?.bubble?.tracking = false }
    }
    // The top of the messages (under the bar): the menu stays below it.
    var listTopPx by remember { mutableIntStateOf(0) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current

    // Built off the main thread (cards are parsed on the way); the first frame from the last look.
    val lines by produceState(remember(peer) { linesFor(peer, cached.orEmpty()) }, messages) {
        value = withContext(Dispatchers.Default) { linesFor(peer, messages) }
    }
    // A new message at the bottom: the list shows it in the same frame it comes in (asked during
    // composition, so it never first lands off screen and then jumps).
    val newestKey = lines.firstOrNull()?.key
    val seenNewest = remember(peer) { arrayOfNulls<String>(1) }
    if (newestKey != seenNewest[0]) {
        val firstLook = seenNewest[0] == null
        seenNewest[0] = newestKey
        if (!firstLook && (jumpToNewest[0] || listState.firstVisibleItemIndex <= 1)) listState.requestScrollToItem(0)
        jumpToNewest[0] = false
    }

    // Picked messages (a long press → "Select", then taps): forwarded, copied or deleted together.
    val selected = remember(peer) { mutableStateListOf<String>() }
    val selecting = selected.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = selecting) { selected.clear() }
    fun toggle(batch: List<ChatMessage>) {
        if (batch.all { it.id in selected }) selected.removeAll(batch.map { it.id }.toSet()) else batch.forEach { if (it.id !in selected) selected += it.id }
    }
    fun forward(list: List<ChatMessage>) {
        ChatHub.startForwardMessages(list)
        selected.clear()
        runCatching { navController.navigate("chats") }
    }

    // The music panel (recent, library, search), drawn over the chat with the keyboard left as it is.
    var musicOpen by rememberSaveable(peer) { mutableStateOf(false) }
    LaunchedEffect(musicOpen) { ChatHub.musicPanelOpen.value = musicOpen }
    DisposableEffect(Unit) { onDispose { ChatHub.musicPanelOpen.value = false } }

    // A sticker held in the panel: large over the blurred chat, its code above it.
    var preview by remember { mutableStateOf<Stickers.Sticker?>(null) }
    var previewShown by remember { mutableStateOf<Stickers.Sticker?>(null) }
    val blur by androidx.compose.animation.core.animateDpAsState(if (preview != null) 18.dp else 0.dp, tween(180), label = "previewBlur")
    val byId = remember(messages) { messages.associateBy { it.id } }

    Box(
        Modifier
            .fillMaxSize()
            // The held sticker goes once no finger is down anywhere (seen here before the panel's
            // own handling, so the preview never stays up after letting go).
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (preview != null && event.changes.none { it.pressed }) preview = null
                    }
                }
            },
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(if (blur > 0.dp) Modifier.blur(blur) else Modifier),
    ) {
        if (selecting) {
            val picked = messages.filter { it.id in selected }
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { selected.clear() }) { Icon(painterResource(R.drawable.close), null) }
                },
                title = { Text(selected.size.toString()) },
                actions = {
                    val copyText = picked.sortedBy { it.ts }.mapNotNull { copyTextOf(it).ifEmpty { null } }.joinToString("\n")
                    if (copyText.isNotEmpty()) {
                        IconButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("messages", copyText))
                            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                            selected.clear()
                        }) { Icon(painterResource(R.drawable.content_copy), stringResource(R.string.chat_copy)) }
                    }
                    IconButton(onClick = { forward(picked) }) { Icon(painterResource(R.drawable.share), stringResource(R.string.chat_forwarding)) }
                    IconButton(onClick = {
                        picked.forEach { ChatHub.delete(it, forBoth = false) }
                        selected.clear()
                    }) { Icon(painterResource(R.drawable.delete), stringResource(R.string.chat_delete_me)) }
                },
            )
        } else TopAppBar(
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) { Icon(painterResource(R.drawable.arrow_back), null) }
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { navController.navigate("friend/$peer") },
                ) {
                    PeerAvatar(peer, name, 40.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            AppVersion(peer, Modifier.padding(start = 6.dp))
                        }
                        // Read here, so its once-a-second tick redraws only this line.
                        val typing = peer in typingNow()
                        val now = data[peer]?.now
                        val status = when {
                            typing -> stringResource(R.string.chat_typing)
                            now != null && now.isLive(rememberLiveClock()) -> stringResource(R.string.chat_listening, "${now.track.artist} — ${now.track.title}")
                            else -> null
                        }
                        androidx.compose.animation.AnimatedContent(status, label = "status") { s ->
                            if (s != null) {
                                Text(
                                    s,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
        )
        val status = peers[peer]
        val note = when {
            FriendsHub.isDeveloper(peer) || FriendsHub.profile.collectAsState().value?.isDeveloper == true -> null
            status == null -> null
            !status.addedMe -> stringResource(R.string.chat_not_added, name)
            !status.hasChat -> stringResource(R.string.chat_old_app, name)
            else -> null
        }
        if (note != null) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        Box(Modifier.weight(1f).onGloballyPositioned { listTopPx = it.positionInRoot().y.roundToInt() }) {
            if (loaded != null && messages.isEmpty()) {
                EmptyNote(stringResource(R.string.chat_say_hi), Modifier.align(Alignment.Center))
            }
            // A tap on a message waits less than the system's 0.3 s for a second one (a double tap
            // is the quick reaction), so the menu comes sooner.
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalViewConfiguration provides rememberChatViewConfiguration(),
                LocalChatTracks provides chatTracks,
            ) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(lines, key = { it.key }, contentType = { it::class }) { line ->
                    when (line) {
                        is Line.Day -> DayChip(dayTitle(context, line.day), Modifier.animateItem(fadeInSpec = tween(220), placementSpec = messagePlacement))
                        is Line.Msg -> MessageItem(
                            m = line.m,
                            batch = line.batch,
                            firstOfGroup = line.firstOfGroup,
                            reply = line.batch.first().replyTo?.let(byId::get),
                            reactions = reactions[line.batch.first().id],
                            me = me,
                            peerName = name,
                            navController = navController,
                            selecting = selecting,
                            selected = line.batch.all { it.id in selected },
                            onSelect = { toggle(line.batch) },
                            onReply = { replyTo = it; editing = null; replyFocus++ },
                            onMenu = { target ->
                                // At once, as in Telegram: the keyboard goes down meanwhile and the
                                // menu follows the message to where it ends up.
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                menuFor = target()
                            },
                            onQuoteClick = { id ->
                                val index = lines.indexOfFirst { it is Line.Msg && it.batch.any { b -> b.id == id } }
                                if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
                            },
                            modifier = Modifier.animateItem(fadeInSpec = tween(220), fadeOutSpec = tween(160), placementSpec = messagePlacement),
                        )
                    }
                }
            }
            }
        }
        Composer(
            peer = peer,
            peerName = name,
            replyTo = replyTo,
            editing = editing,
            onCancel = { replyTo = null; editing = null },
            onSent = { jumpToNewest[0] = true },
            replyFocus = replyFocus,
            hasPlayer = hasPlayer,
            onPreview = { s ->
                preview = s
                if (s != null) previewShown = s
            },
            onOpenMusic = { musicOpen = true },
        )
    }
        MusicPickerPanel(
            visible = musicOpen,
            onDismiss = { musicOpen = false },
            onSend = { picked ->
                musicOpen = false
                ChatHub.sendAll(peer, "", picked, replyTo?.id)
                replyTo = null
                editing = null
                jumpToNewest[0] = true
            },
        )
        val menuTarget = menuFor
        val menuStatus = peers[peer]
        val density = LocalDensity.current
        MessageMenu(
            target = menuTarget,
            topPx = listTopPx,
            // Clear of the mini player (it stays drawn over the chat).
            bottomPx = LocalPlayerAwareWindowInsets.current.getBottom(density) +
                if (hasPlayer) with(density) { (com.metrolist.music.constants.MiniPlayerBottomSpacing + 8.dp).roundToPx() } else 0,
            actions = menuActions(
                menuTarget,
                onReply = { replyTo = it; editing = null; replyFocus++ },
                onForward = ::forward,
                onSelect = ::toggle,
                onEdit = { editing = it; replyTo = null },
                close = { menuFor = null },
            ),
            myKeys = menuTarget?.let { t -> reactions[t.batch.first().id]?.firstOrNull { it.reactor == me }?.keyList }.orEmpty(),
            oldAppNote = if (menuStatus != null && menuStatus.hasChat && !menuStatus.hasReactions) stringResource(R.string.chat_reactions_old_app, name) else null,
            onReact = { key ->
                menuTarget?.let { ChatHub.react(it.batch.first(), key) }
                menuFor = null
            },
            onDismiss = { menuFor = null },
        )
        // The shade only fades (with the blur under it); the sticker itself grows in.
        androidx.compose.animation.AnimatedVisibility(
            visible = preview != null,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(160)),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = preview != null,
            enter = fadeIn(tween(160)) + androidx.compose.animation.scaleIn(tween(220), initialScale = 0.8f),
            exit = fadeOut(tween(140)) + androidx.compose.animation.scaleOut(tween(160), targetScale = 0.9f),
        ) {
            val s = previewShown
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                if (s != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text(
                            s.code,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 20.dp),
                        )
                        val aspect = s.aspect.coerceIn(0.3f, 4f)
                        AsyncImage(
                            model = s.uri,
                            contentDescription = s.code,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth(if (aspect >= 1f) 0.9f else 0.9f * aspect).aspectRatio(aspect),
                        )
                    }
                }
            }
        }
    }
}

/** Gap between tracks sent together, at most (they go one after another, a few ms apart). */
private const val BATCH_GAP_MS = 3_000L

/**
 * A chat's lines, kept from the last look: opening a chat again finds them ready instead of
 * building them on the main thread (each card's JSON parsed again) in its first frame.
 */
private val linesCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<ChatMessage>, List<Line>>>()

private fun linesFor(peer: String, messages: List<ChatMessage>): List<Line> =
    linesCache[peer]?.takeIf { it.first === messages }?.second
        ?: buildLines(messages).also { linesCache[peer] = messages to it }

/** The conversation's lines, newest first: messages (tracks sent together as one) and day chips. */
private fun buildLines(messages: List<ChatMessage>): List<Line> {
    val out = mutableListOf<Line>()
    val lone = messages.map { isLoneTrack(it) }
    var i = 0
    while (i < messages.size) {
        val m = messages[i]
        // Tracks sent together (picked at once in the music panel) make one bubble, as in Telegram.
        var j = i + 1
        if (lone[i]) {
            while (j < messages.size && lone[j] && messages[j].mine == m.mine && messages[j - 1].ts - messages[j].ts <= BATCH_GAP_MS) j++
        }
        val batch = messages.subList(i, j).reversed()
        val oldest = batch.first()
        val older = messages.getOrNull(j)
        val firstOfGroup = older == null || older.mine != oldest.mine || oldest.ts - older.ts > 5 * 60_000 || dateOf(older.ts) != dateOf(oldest.ts)
        out += Line.Msg(m, batch, firstOfGroup)
        if (older == null || dateOf(older.ts) != dateOf(oldest.ts)) out += Line.Day(dateOf(oldest.ts))
        i = j
    }
    return out
}

/** A track card alone (no text, not a reply): such ones sent together share a bubble. */
private fun isLoneTrack(m: ChatMessage): Boolean =
    m.text.isBlank() && m.replyTo == null && ChatMusic.fromJson(m.music)?.kind == AppLinks.Kind.TRACK

/** What "Copy" takes from a message: its text (stickers as codes), else its card's link. */
private fun copyTextOf(m: ChatMessage): String =
    Stickers.plain(m.text).trim().ifEmpty { ChatMusic.fromJson(m.music)?.link().orEmpty() }

private val messagePlacement = spring<androidx.compose.ui.unit.IntOffset>(
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold,
)

@Composable
private fun DayChip(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(
    m: ChatMessage,
    batch: List<ChatMessage>,
    firstOfGroup: Boolean,
    reply: ChatMessage?,
    reactions: List<ChatReaction>?,
    me: String?,
    peerName: String,
    navController: NavController,
    selecting: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onReply: (ChatMessage) -> Unit,
    onMenu: (() -> MenuTarget?) -> Unit,
    onQuoteClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = batch.first()
    val music = remember(m.music) { ChatMusic.fromJson(m.music) }
    val tracks = remember(batch) { if (batch.size > 1) batch.mapNotNull { b -> ChatMusic.fromJson(b.music)?.let { b.id to it } } else emptyList() }
    val stickersOnly = remember(m.text, music) { if (music == null && m.replyTo == null) Stickers.stickerOnly(m.text) else null }
    // A batch shows its worst: a failure, else a wait, else unread, else read.
    val shown = remember(batch) {
        batch.firstOrNull { it.state == ChatMessage.STATE_FAILED }
            ?: batch.firstOrNull { it.state == ChatMessage.STATE_SENDING }
            ?: batch.firstOrNull { !it.read }
            ?: batch.last()
    }
    val haptic = LocalHapticFeedback.current
    val launch = rememberLauncher()
    val replyLabel = stringResource(R.string.chat_reply)
    val quickLabel = stringResource(R.string.chat_quick_reaction)
    val selectLabel = stringResource(R.string.chat_select)
    val (edgePx, replyPx) = rememberReplyPx()
    val swipe = remember { SwipeReply() }
    // Where the bubble is, followed only while its menu is open (no work while scrolling).
    val bubble = remember { BubbleBounds() }

    // My own new message rises into place from the field, as in Telegram.
    // Only that one gets an animation: every message having its own made opening a chat heavier.
    val appear = remember(first.id) {
        if (m.mine && m.state == ChatMessage.STATE_SENDING && System.currentTimeMillis() - m.ts < 1_500) Animatable(1f) else null
    }
    if (appear != null) {
        LaunchedEffect(first.id) {
            appear.animateTo(0f, tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }

    val bubbleColor = if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = if (m.mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(
        topStart = if (!m.mine && firstOfGroup) 4.dp else 18.dp,
        topEnd = if (m.mine && firstOfGroup) 4.dp else 18.dp,
        bottomStart = 18.dp,
        bottomEnd = 18.dp,
    )
    // Animated only while picking messages (no animation per message otherwise).
    val highlight = if (selecting) {
        androidx.compose.animation.animateColorAsState(
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
            label = "selected",
        ).value
    } else {
        Color.Transparent
    }
    // A double tap anywhere on the message's line puts the quick reaction (or takes it back).
    val canReact = !selecting && !first.deleted && !(first.mine && first.state == ChatMessage.STATE_SENDING && first.event == null)
    val quick: (() -> Unit)? = if (canReact) ({
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        ChatHub.react(first, ChatReactions.quick.value)
    }) else null
    fun openMenu() {
        onMenu { bubble.start()?.let { MenuTarget(m, batch, bubble, m.mine) } }
    }
    val onTap: () -> Unit = { if (selecting) onSelect() else openMenu() }
    val onHold: () -> Unit = { if (selecting) onSelect() else openMenu() }
    val pick: (() -> Unit)? = if (selecting) onSelect else null
    Box(
        modifier
            .fillMaxWidth()
            .then(if (appear != null) Modifier.graphicsLayer { translationY = appear.value * 48.dp.toPx() } else Modifier)
            .then(if (selecting) Modifier.background(highlight) else Modifier)
            .swipeToReply(
                state = swipe,
                enabled = !selecting,
                edgePx = edgePx,
                replyPx = replyPx,
                onCross = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                onReply = { onReply(first) },
                launch = launch,
            )
            // Beside the bubble: a tap opens the message's menu (a pick while picking), a hold picks it.
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onTap,
                onDoubleClick = quick,
                onLongClick = onSelect,
            )
            .semantics {
                customActions = listOfNotNull(
                    androidx.compose.ui.semantics.CustomAccessibilityAction(replyLabel) { onReply(first); true },
                    quick?.let { q -> androidx.compose.ui.semantics.CustomAccessibilityAction(quickLabel) { q(); true } },
                    androidx.compose.ui.semantics.CustomAccessibilityAction(selectLabel) { onSelect(); true },
                )
            }
            .padding(top = if (firstOfGroup) 6.dp else 2.dp, bottom = 1.dp),
        contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        ReplyArrow(swipe, replyPx, Modifier.align(Alignment.CenterEnd))
        Column(
            horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start,
            modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(swipe.offset.roundToInt(), 0) },
        ) {
            // A tap waits the double-tap time (0.3 s, as in Telegram) to tell it from a double tap.
            val clickable = Modifier.combinedClickable(onClick = onTap, onDoubleClick = quick, onLongClick = onHold)
            val footer: @Composable () -> Unit = { TimeAndTicks(shown, textColor, m.edited) }
            val place = Modifier.onGloballyPositioned(bubble::place)
            if (stickersOnly != null) {
                Column(horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start, modifier = place.then(clickable)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        stickersOnly.forEach { part ->
                            if (stickersOnly.size == 1) BigSticker(part.sticker, 150.dp, 200.dp) else BigSticker(part.sticker, 110.dp, 120.dp)
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(clock(m.ts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (m.mine) Ticks(m, Modifier.padding(start = 3.dp))
                    }
                }
            } else {
                Column(
                    place
                        .widthIn(max = 300.dp)
                        .clip(shape)
                        .background(bubbleColor)
                        .then(clickable)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    if (first.replyTo != null) Quote(reply, peerName, m.mine) { if (selecting) onSelect() else onQuoteClick(first.replyTo) }
                    when {
                        tracks.size > 1 -> tracks.forEachIndexed { i, (id, t) ->
                            TrackRow(t, id, textColor, onTap = pick, onHold = onHold, trailing = if (i == tracks.lastIndex) footer else null)
                        }
                        // A track alone: the time on the line of its length, as in Telegram.
                        music != null && music.kind == AppLinks.Kind.TRACK && m.text.isBlank() ->
                            TrackRow(music, m.id, textColor, onTap = pick, onHold = onHold, trailing = footer)
                        else -> {
                            music?.let {
                                if (it.kind == AppLinks.Kind.TRACK) {
                                    TrackRow(it, m.id, textColor, onTap = pick, onHold = onHold, trailing = null)
                                } else {
                                    CollectionCard(it, navController, textColor, onTap = pick, onHold = onHold)
                                }
                            }
                            if (m.text.isNotBlank()) {
                                MessageText(m.text, textColor, Modifier.padding(top = if (music != null) 6.dp else 0.dp))
                            }
                            Box(Modifier.align(Alignment.End).padding(top = 2.dp)) { footer() }
                        }
                    }
                }
            }
            ReactionChips(
                reactions = reactions,
                me = me,
                mine = m.mine,
                onToggle = { key -> if (canReact) ChatHub.react(first, key) },
            )
        }
    }
}

/** What the menu of [t] offers: retry, reply, forward, select, copy, edit, delete. */
@Composable
private fun menuActions(
    t: MenuTarget?,
    onReply: (ChatMessage) -> Unit,
    onForward: (List<ChatMessage>) -> Unit,
    onSelect: (List<ChatMessage>) -> Unit,
    onEdit: (ChatMessage) -> Unit,
    close: () -> Unit,
): List<MenuAction> {
    val context = LocalContext.current
    val retry = stringResource(R.string.chat_retry)
    val reply = stringResource(R.string.chat_reply)
    val forward = stringResource(R.string.chat_forwarding)
    val select = stringResource(R.string.chat_select)
    val copy = stringResource(R.string.chat_copy)
    val edit = stringResource(R.string.chat_edit)
    val deleteBoth = stringResource(R.string.chat_delete_both)
    val deleteMe = stringResource(R.string.chat_delete_me)
    if (t == null) return emptyList()
    val m = t.message
    val batch = t.batch
    val failed = batch.filter { it.mine && it.state == ChatMessage.STATE_FAILED }
    val copyText = batch.mapNotNull { copyTextOf(it).ifEmpty { null } }.joinToString("\n")
    return buildList {
        if (failed.isNotEmpty()) add(MenuAction(retry, R.drawable.refresh) { close(); failed.forEach(ChatHub::retry) })
        add(MenuAction(reply, R.drawable.chat_reply) { close(); onReply(batch.first()) })
        add(MenuAction(forward, R.drawable.share) { close(); onForward(batch) })
        add(MenuAction(select, R.drawable.check) { close(); onSelect(batch) })
        if (copyText.isNotEmpty()) {
            add(MenuAction(copy, R.drawable.content_copy) {
                close()
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("message", copyText))
                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
            })
        }
        if (batch.size == 1 && m.mine && m.text.isNotBlank() && m.state != ChatMessage.STATE_FAILED) {
            add(MenuAction(edit, R.drawable.edit) { close(); onEdit(m) })
        }
        if (m.mine) add(MenuAction(deleteBoth, R.drawable.delete, danger = true) { close(); batch.forEach { ChatHub.delete(it, forBoth = true) } })
        add(MenuAction(deleteMe, R.drawable.delete, danger = true) { close(); batch.forEach { ChatHub.delete(it, forBoth = false) } })
    }
}

/** "edited", the time, and (on one's own) the ticks. */
@Composable
private fun TimeAndTicks(m: ChatMessage, textColor: Color, edited: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (edited) {
            Text(stringResource(R.string.chat_edited), style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.6f), modifier = Modifier.padding(end = 4.dp))
        }
        Text(clock(m.ts), style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.6f))
        if (m.mine) Ticks(m, Modifier.padding(start = 3.dp), tint = textColor)
    }
}

/** The message this one answers: who wrote it and its first line, a tap scrolls to it. */
@Composable
private fun Quote(reply: ChatMessage?, peerName: String, mine: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val accent = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.1f))
            .clickable(enabled = reply != null, onClick = onClick)
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                if (reply == null) "" else if (reply.mine) stringResource(R.string.chat_you) else peerName,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                reply?.let { ChatHub.preview(context, it) } ?: stringResource(R.string.chat_reply_gone),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Text with stickers inside it, each the size of a Twitch emote (about two lines high). */
@Composable
private fun MessageText(text: String, color: Color, modifier: Modifier = Modifier) {
    val parts = remember(text) { Stickers.parts(text) }
    val inline = mutableMapOf<String, InlineTextContent>()
    val annotated = buildAnnotatedString {
        parts.forEachIndexed { i, part ->
            when (part) {
                is Stickers.Part.Text -> append(part.text)
                is Stickers.Part.Image -> {
                    val sticker = part.sticker
                    if (sticker == null) {
                        append("[" + stringResource(R.string.chat_sticker) + "]")
                    } else {
                        val key = "s$i"
                        appendInlineContent(key, sticker.code)
                        val height = 2.0f
                        inline[key] = InlineTextContent(
                            Placeholder((height * sticker.aspect.coerceIn(0.5f, 3.2f)).em, height.em, PlaceholderVerticalAlign.TextCenter),
                        ) {
                            AsyncImage(model = sticker.uri, contentDescription = sticker.code, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
    Text(annotated, inlineContent = inline, color = color, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
}

@Composable
private fun BigSticker(sticker: Stickers.Sticker?, height: androidx.compose.ui.unit.Dp, maxWidth: androidx.compose.ui.unit.Dp) {
    if (sticker == null) {
        Text("[" + stringResource(R.string.chat_sticker) + "]", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val aspect = sticker.aspect.coerceIn(0.5f, 3.2f)
    // An animated one (a GIF) a little larger than a picture, with soft corners like a video.
    val grow = if (sticker.animated) 1.3f else 1f
    val width = minOf(height * grow * aspect, maxWidth * grow)
    AsyncImage(
        model = sticker.uri,
        contentDescription = sticker.code,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(width, width / aspect)
            .then(if (sticker.animated) Modifier.clip(RoundedCornerShape(12.dp)) else Modifier),
    )
}

// ── Music cards ──────────────────────────────────────────────────────────────────────────────────

/** The card last started from a chat (so its button shows pause while it plays). */
private val startedFromChat = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

/**
 * Every track of the open conversation, top to bottom, with the message it is in: a track started
 * from the chat goes on with the ones below it instead of stopping.
 */
private val LocalChatTracks = androidx.compose.runtime.staticCompositionLocalOf<() -> List<Pair<String, ChatMusic>>> { { emptyList() } }

/**
 * A track: the cover with play/pause, the name, its length (and [trailing] on that line: the time).
 * A tap anywhere on it plays or pauses it ([onTap] instead, while picking messages).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    music: ChatMusic,
    messageId: String,
    textColor: Color,
    onTap: (() -> Unit)?,
    onHold: () -> Unit,
    trailing: (@Composable () -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val player = LocalPlayerConnection.current
    val current by (player?.mediaMetadata ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    val isPlaying by (player?.isPlaying ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
    val started by startedFromChat.collectAsState()
    val chatTracks = LocalChatTracks.current
    val currentId = current?.id
    val isThis = currentId != null && (
        com.metrolist.music.resolver.FallbackIds.catalogIdOf(currentId) == music.id ||
            AppLinks.YOUTUBE_PREFIX + currentId == music.id ||
            (started == music.id && current?.title.equals(music.title, ignoreCase = true))
        )
    val playingThis = isThis && isPlaying
    val play: () -> Unit = {
        if (isThis) {
            player?.togglePlayPause()
        } else {
            startedFromChat.value = music.id
            scope.launch {
                // This track, then the chat's next ones (down the conversation), not just this one.
                val all = chatTracks()
                val at = all.indexOfFirst { it.first == messageId && it.second.id == music.id }
                val queue = if (at < 0) {
                    FriendPlayback.listQueue(listOf(music.trackRef()), 0)
                } else {
                    FriendPlayback.listQueue(all.map { it.second.trackRef() }, at)
                }
                player?.playQueue(queue)
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(min = 240.dp)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onTap ?: play, onLongClick = onHold)
            .padding(vertical = 3.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).clip(CircleShape),
        ) {
            AsyncImage(model = music.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            androidx.compose.animation.Crossfade(playingThis, label = "play") { playing ->
                Icon(
                    painterResource(if (playing) R.drawable.pause else R.drawable.play),
                    stringResource(if (playing) R.string.chat_pause else R.string.chat_play),
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                music.label(),
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (music.durationMs > 0) duration(music.durationMs) else "",
                    color = textColor.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
        }
    }
}

/** An album, playlist or artist: its cover large; a tap opens it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionCard(music: ChatMusic, navController: NavController, textColor: Color, onTap: (() -> Unit)?, onHold: () -> Unit) {
    val scope = rememberCoroutineScope()
    val kindLabel = stringResource(
        when (music.kind) {
            AppLinks.Kind.ALBUM -> R.string.chat_album
            AppLinks.Kind.PLAYLIST -> R.string.chat_playlist
            else -> R.string.chat_artist
        },
    )
    Column(
        Modifier
            .width(240.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onTap ?: { scope.launch { openMusic(music, navController) }; Unit }, onLongClick = onHold),
    ) {
        AsyncImage(
            model = music.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(if (music.kind == AppLinks.Kind.ARTIST) CircleShape else RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Text(
            music.title,
            color = textColor,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        val sub = listOfNotNull(
            kindLabel,
            music.by.takeIf { it.isNotBlank() },
            music.count.takeIf { it > 0 }?.let { pluralStringRes(R.plurals.chat_tracks, it) },
        ).joinToString(" · ")
        Text(sub, color = textColor.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun pluralStringRes(id: Int, count: Int): String = androidx.compose.ui.res.pluralStringResource(id, count, count)

/** Opens an album, playlist or artist from a card, as its app link would. */
private suspend fun openMusic(music: ChatMusic, navController: NavController) {
    val target = AppLinks.Target(music.kind, music.id, music.title, music.by)
    val fromYouTube = music.isYouTube
    val id = if (fromYouTube) {
        music.id.removePrefix(AppLinks.YOUTUBE_PREFIX)
    } else {
        withContext(Dispatchers.IO) {
            runCatching {
                com.metrolist.music.catalog.Catalog.ensureAuthenticated()
                AppLinks.reachableId(target)
            }.getOrDefault(music.id)
        }
    }
    val route = when (music.kind) {
        AppLinks.Kind.ALBUM -> if (fromYouTube) "album/$id" else "spotify_album/$id"
        AppLinks.Kind.PLAYLIST -> if (fromYouTube) "online_playlist/$id" else "spotify_playlist/$id"
        AppLinks.Kind.ARTIST -> if (fromYouTube) "artist/$id" else "spotify_artist/$id"
        AppLinks.Kind.TRACK -> return
    }
    runCatching { navController.navigate(route) }
}

// ── Writing ──────────────────────────────────────────────────────────────────────────────────────

/**
 * The field and what's under it. The keyboard and the sticker panel take turns in one space of the
 * keyboard's height: one slides away while the other is already there, so nothing jumps. With
 * neither, the space is just what the mini player covers.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Composer(
    peer: String,
    peerName: String,
    replyTo: ChatMessage?,
    editing: ChatMessage?,
    onCancel: () -> Unit,
    onSent: () -> Unit,
    replyFocus: Int,
    hasPlayer: Boolean,
    onPreview: (Stickers.Sticker?) -> Unit,
    onOpenMusic: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    // Stickers sit in the field as themselves (shown as their codes), so ordinary words never turn into one.
    var field by rememberSaveable(peer, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var panel by rememberSaveable { mutableStateOf(false) }
    // The panel stays until the keyboard has risen over it (then it goes, unseen).
    var toKeyboard by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    val forward by ChatHub.forward.collectAsState()

    val imePx = WindowInsets.ime.getBottom(density)
    val imeTargetPx = WindowInsets.imeAnimationTarget.getBottom(density)
    val navPx = WindowInsets.navigationBars.getBottom(density)
    var keyboardPx by remember { mutableIntStateOf(ChatHub.keyboardHeightPx) }
    LaunchedEffect(imeTargetPx) {
        // Where a keyboard that opens ends up: the panel's height from now on.
        if (imeTargetPx > navPx + with(density) { 120.dp.roundToPx() }) {
            keyboardPx = imeTargetPx
            ChatHub.keyboardHeightPx = imeTargetPx
        }
    }
    val panelPx = if (keyboardPx > 0) keyboardPx else with(density) { 300.dp.roundToPx() } + navPx
    LaunchedEffect(imePx, toKeyboard) {
        if (toKeyboard && imePx >= panelPx - with(density) { 2.dp.roundToPx() }) {
            panel = false
            toKeyboard = false
        }
    }
    // The panel's space: at once when the keyboard is there to swap with, else sliding up/down.
    val panelSpace = remember { Animatable(0f) }
    LaunchedEffect(panel, panelPx) {
        val target = if (panel) panelPx.toFloat() else 0f
        when {
            // The keyboard is rising over the panel but ends a little lower or higher than it:
            // the field follows smoothly instead of jumping to the keyboard's height at once.
            toKeyboard && panel -> panelSpace.animateTo(target, tween(220))
            imePx > navPx -> panelSpace.snapTo(target)
            else -> panelSpace.animateTo(target, tween(260))
        }
    }
    val playerPx = LocalPlayerAwareWindowInsets.current.getBottom(density) +
        if (hasPlayer) with(density) { (com.metrolist.music.constants.MiniPlayerBottomSpacing + 8.dp).roundToPx() } else 0
    val bottomPx = maxOf(imePx, panelSpace.value.toInt(), playerPx)

    BackHandler(enabled = panel && !toKeyboard) { panel = false }
    // The mini player goes while the panel is open (the keyboard, over it, hides it anyway).
    LaunchedEffect(panel) { ChatHub.stickerPanelOpen.value = panel }
    DisposableEffect(Unit) { onDispose { ChatHub.stickerPanelOpen.value = false } }

    // The field's focus: gaining it brings the keyboard (at the app's pace, see ImeMotion).
    var focused by remember { mutableStateOf(false) }

    fun showKeyboard() {
        toKeyboard = true
        if (focused) ImeMotion.show(view) { keyboard?.show() } else focus.requestFocus()
    }

    fun showPanel() {
        toKeyboard = false
        panel = true
        if (imePx > navPx) {
            // The keyboard slides away under the panel; the field lets go once it's gone.
            ImeMotion.hide(view, onDone = { focusManager.clearFocus() }) { keyboard?.hide() }
        } else {
            focusManager.clearFocus()
        }
    }

    // Editing puts the text in the field; leaving it for a reply (a swipe) takes that text away again,
    // so it never goes out as a new message.
    val wasEditing = remember { BooleanArray(1) }
    LaunchedEffect(editing?.id) {
        val e = editing
        if (e != null) {
            field = TextFieldValue(e.text, TextRange(e.text.length))
            wasEditing[0] = true
        } else if (wasEditing[0]) {
            wasEditing[0] = false
            field = TextFieldValue()
        }
    }
    // A reply was started (swipe, menu): the keyboard comes up for it.
    LaunchedEffect(replyFocus) { if (replyFocus > 0) showKeyboard() }

    /** The sticker at the cursor, in place of the [replace] characters before it (its typed code). */
    fun insertSticker(s: Stickers.Sticker, replace: Int = 0) {
        val raw = field.text
        val end = field.selection.max.coerceIn(0, raw.length)
        val from = (field.selection.min - replace).coerceIn(0, end)
        val before = raw.substring(0, from)
        val piece = (if (before.isNotEmpty() && !before.last().isWhitespace()) " " else "") + Stickers.token(s) + " "
        field = TextFieldValue(before + piece + raw.substring(end), TextRange(from + piece.length))
    }

    fun send() {
        val body = field.text.trim()
        val edit = editing
        val fwd = forward
        when {
            edit != null -> if (body.isNotEmpty()) ChatHub.edit(edit, body) else return
            fwd != null -> {
                // What was typed goes first, then what is forwarded (each as it was).
                ChatHub.sendItems(peer, listOfNotNull(body.takeIf { it.isNotEmpty() }?.let { ChatHub.Outgoing(it, null) }) + fwd, replyTo?.id)
                ChatHub.cancelForward()
            }
            body.isNotEmpty() -> ChatHub.send(peer, body, replyTo = replyTo?.id)
            else -> return
        }
        field = TextFieldValue()
        onCancel()
        onSent()
    }

    // Codes that start like what is typed before the cursor: a tap puts the sticker in its place.
    val beforeCursor = field.text.substring(0, field.selection.min.coerceIn(0, field.text.length))
    val suggestions = remember(beforeCursor) { Stickers.suggest(beforeCursor).takeIf { it.isNotEmpty() } }
    val suggestionsShown = lastNonNull(suggestions).orEmpty()
    Box {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column {
                // Each strip keeps what it showed while it slides away (else it would go blank first and
                // look like it vanished at once).
                // Forwarding: what goes with the next send.
                val fwd = forward?.takeIf { editing == null }
                val fwdShown = lastNonNull(fwd)
                AnimatedVisibility(fwd != null, enter = stripEnter, exit = stripExit) {
                    if (fwdShown != null) {
                        Strip(
                            icon = R.drawable.share,
                            title = stringResource(R.string.chat_forwarding),
                            text = fwdShown.joinToString(", ") { it.music?.label() ?: Stickers.plain(it.text).trim() },
                            onClose = ChatHub::cancelForward,
                        )
                    }
                }
                // Replying to / editing (one turning into the other crossfades in place).
                val target = (editing ?: replyTo)?.let { t ->
                    StripText(
                        icon = if (editing != null) R.drawable.edit else R.drawable.chat_reply,
                        title = if (editing != null) stringResource(R.string.chat_editing) else if (t.mine) stringResource(R.string.chat_you) else peerName,
                        text = ChatHub.preview(context, t),
                        editing = editing != null,
                    )
                }
                val targetShown = lastNonNull(target)
                AnimatedVisibility(target != null, enter = stripEnter, exit = stripExit) {
                    if (targetShown != null) {
                        androidx.compose.animation.AnimatedContent(
                            targetShown,
                            transitionSpec = { fadeIn(tween(CHAT_MOTION_MS)) togetherWith fadeOut(tween(CHAT_MOTION_MS)) },
                            contentKey = { it.icon to it.text },
                            label = "strip",
                        ) { s ->
                            Strip(
                                icon = s.icon,
                                title = s.title,
                                text = s.text,
                                onClose = { if (s.editing) field = TextFieldValue(); onCancel() },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    val stickersShown = panel && !toKeyboard
                    IconButton(onClick = { if (stickersShown) showKeyboard() else showPanel() }) {
                        androidx.compose.animation.Crossfade(stickersShown, label = "stickerIcon") { shown ->
                            Icon(painterResource(if (shown) R.drawable.chat_keyboard else R.drawable.chat_mood), stringResource(R.string.chat_stickers))
                        }
                    }
                    val codeColor = MaterialTheme.colorScheme.primary
                    val showCodes = remember(codeColor) { StickerCodes(codeColor) }
                    TextField(
                        value = field,
                        onValueChange = {
                            field = keepStickersWhole(field, it)
                            if (it.text.isNotBlank() && editing == null) ChatHub.typingNow(peer)
                        },
                        visualTransformation = showCodes,
                        // The keyboard is brought by the focus handler above, at the app's own pace.
                        keyboardOptions = KeyboardOptions(showKeyboardOnFocus = false),
                        placeholder = { Text(stringResource(R.string.chat_message_hint)) },
                        maxLines = 6,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focus)
                            // A tap on the field while the panel is open: the keyboard comes up over it.
                            .onFocusChanged {
                                val gained = it.isFocused && !focused
                                focused = it.isFocused
                                if (gained) {
                                    // A tap on the field while the panel is open: the keyboard comes up over it.
                                    if (panel) toKeyboard = true
                                    ImeMotion.show(view) { keyboard?.show() }
                                }
                            },
                    )
                    val canSend = field.text.isNotBlank() || (forward != null && editing == null)
                    androidx.compose.animation.AnimatedContent(
                        canSend || editing != null,
                        transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                        label = "action",
                    ) { showSend ->
                        if (showSend) {
                            IconButton(onClick = ::send, enabled = canSend) {
                                Icon(painterResource(R.drawable.chat_send), stringResource(R.string.chat_send), tint = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            // Nothing typed: the music panel (recent, library, search).
                            // The keyboard slides away and the panel takes the screen's lower part.
                            IconButton(onClick = {
                                if (imePx > navPx) {
                                    ImeMotion.hide(view, onDone = { focusManager.clearFocus() }) { keyboard?.hide() }
                                } else {
                                    focusManager.clearFocus()
                                }
                                onOpenMusic()
                            }) {
                                Icon(painterResource(R.drawable.music_note), stringResource(R.string.chat_send_music), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                // The keyboard's space: the sticker panel, the keyboard over it, or the mini player's room.
                Box(Modifier.fillMaxWidth().height(with(density) { bottomPx.toDp() })) {
                    if (panel || panelSpace.value > 0f) {
                        StickerPanel(
                            // Only the system's navigation bar is under it now, not the mini player.
                            bottomPadding = with(density) { navPx.toDp() },
                            modifier = Modifier.fillMaxSize(),
                            onPreview = onPreview,
                            onPick = { s ->
                                if (field.text.isBlank() && editing == null && forward == null) {
                                    ChatHub.send(peer, Stickers.token(s), replyTo = replyTo?.id)
                                    onCancel()
                                    onSent()
                                } else {
                                    insertSticker(s)
                                }
                            },
                        )
                    }
                }
            }
        }
        // The suggestions float over the chat just above the field, as in Telegram: nothing moves
        // under them, and only they are drawn again while they come and go (pushing the whole chat
        // up and down redrew the screen each frame).
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0))
                    layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
                },
        ) {
            AnimatedVisibility(
                suggestions != null,
                enter = fadeIn(tween(140)) + androidx.compose.animation.scaleIn(tween(CHAT_MOTION_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing), initialScale = 0.9f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)),
                exit = fadeOut(tween(140)) + androidx.compose.animation.scaleOut(tween(160), targetScale = 0.94f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 4.dp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(suggestionsShown, key = { it.first.id }) { (s, typed) ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { insertSticker(s, typed) }
                                    .padding(4.dp),
                            ) {
                                AsyncImage(model = s.uri, contentDescription = s.code, modifier = Modifier.size(40.dp))
                                Text(s.code, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * An edit that cuts into a sticker in the field (a backspace at its end, a selection across part
 * of it) takes the whole sticker, so no half of one is ever left; typing inside one goes after it.
 */
private fun keepStickersWhole(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    val a = old.text
    val b = new.text
    if (a == b) return new
    val tokens = Stickers.tokens(a)
    if (tokens.isEmpty()) return new
    var p = 0
    while (p < a.length && p < b.length && a[p] == b[p]) p++
    var q = 0
    while (q < a.length - p && q < b.length - p && a[a.length - 1 - q] == b[b.length - 1 - q]) q++
    val inserted = b.substring(p, b.length - q)
    var start = p
    var end = a.length - q
    var cut = false
    for ((range, _) in tokens) {
        val first = range.first
        val after = range.last + 1
        if (start == end) {
            // Typed or pasted inside a sticker: after it instead.
            if (start in (first + 1) until after) {
                start = after
                end = after
                cut = true
            }
        } else if (first < end && after > start && (first < start || after > end)) {
            start = minOf(start, first)
            end = maxOf(end, after)
            cut = true
        }
    }
    if (!cut) return new
    val text = a.substring(0, start) + inserted + a.substring(end)
    return TextFieldValue(text, TextRange(start + inserted.length))
}

/** The field shows each sticker as its code, coloured; underneath it stays the sticker itself. */
private class StickerCodes(private val color: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val tokens = Stickers.tokens(text.text)
        if (tokens.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        // Each sticker's place in the field's text and in what is shown.
        val origStart = IntArray(tokens.size)
        val origEnd = IntArray(tokens.size)
        val shownStart = IntArray(tokens.size)
        val shownEnd = IntArray(tokens.size)
        val shown = buildAnnotatedString {
            var at = 0
            tokens.forEachIndexed { i, (range, id) ->
                append(text.text.substring(at, range.first))
                origStart[i] = range.first
                origEnd[i] = range.last + 1
                shownStart[i] = length
                pushStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium))
                append(Stickers.byId(id)?.code ?: "?")
                pop()
                shownEnd[i] = length
                at = range.last + 1
            }
            append(text.text.substring(at))
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                for (i in tokens.indices) {
                    if (offset <= origStart[i]) return offset - origStart[i] + shownStart[i]
                    if (offset < origEnd[i]) return shownEnd[i]
                }
                val last = tokens.lastIndex
                return (offset - origEnd[last] + shownEnd[last]).coerceAtMost(shown.length)
            }

            override fun transformedToOriginal(offset: Int): Int {
                for (i in tokens.indices) {
                    if (offset <= shownStart[i]) return offset - shownStart[i] + origStart[i]
                    if (offset < shownEnd[i]) return origEnd[i]
                }
                val last = tokens.lastIndex
                return (offset - shownEnd[last] + origEnd[last]).coerceAtMost(text.length)
            }
        }
        return TransformedText(shown, mapping)
    }
}

private data class StripText(val icon: Int, val title: String, val text: String, val editing: Boolean)

private val stripEnter = expandVertically(tween(CHAT_MOTION_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing), expandFrom = Alignment.Bottom) +
    fadeIn(tween(CHAT_MOTION_MS))
private val stripExit = shrinkVertically(tween(CHAT_MOTION_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing), shrinkTowards = Alignment.Bottom) +
    fadeOut(tween(CHAT_MOTION_MS - 60))

/** A line above the field: forwarding, replying to, editing — with a cross to drop it. */
@Composable
private fun Strip(icon: Int, title: String, text: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp)) {
        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onClose) { Icon(painterResource(R.drawable.close), null) }
    }
}

@Composable
private fun StickerPanel(
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onPreview: (Stickers.Sticker?) -> Unit,
    onPick: (Stickers.Sticker) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Column(modifier) {
        HorizontalDivider()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(76.dp),
            // The last row can scroll up from under the mini player.
            contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = bottomPadding + 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(Stickers.all, key = { it.id }) { s ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .pointerInput(s.id) {
                            detectTapGestures(
                                onPress = {
                                    tryAwaitRelease()
                                    onPreview(null)
                                },
                                onLongPress = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onPreview(s)
                                },
                                onTap = { onPick(s) },
                            )
                        }
                        .padding(4.dp),
                ) {
                    AsyncImage(model = s.uri, contentDescription = s.code, contentScale = ContentScale.Fit, modifier = Modifier.size(64.dp))
                    Text(s.code, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
