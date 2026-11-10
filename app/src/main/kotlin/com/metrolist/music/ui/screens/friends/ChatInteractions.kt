/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.friends

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.metrolist.music.R
import com.metrolist.music.friends.ChatHub
import com.metrolist.music.friends.ChatMessage
import com.metrolist.music.friends.ChatReaction
import com.metrolist.music.friends.ChatReactions
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ── Keeping what goes ────────────────────────────────────────────────────────────────────────────

/**
 * [value], or the last non-null one while it is null: content that animates away (a strip above the
 * field, a menu) keeps showing what it showed instead of going blank before the animation runs.
 */
@Composable
internal fun <T : Any> lastNonNull(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return value ?: holder[0] as T?
}

/** One duration for what slides in and out above the field and around messages. */
internal const val CHAT_MOTION_MS = 200

/** How long a tap on a message waits for a second one (the system's 300 ms felt slow). */
private const val CHAT_DOUBLE_TAP_MS = 175L

@Composable
internal fun rememberChatViewConfiguration(): androidx.compose.ui.platform.ViewConfiguration {
    val base = androidx.compose.ui.platform.LocalViewConfiguration.current
    return remember(base) {
        object : androidx.compose.ui.platform.ViewConfiguration by base {
            override val doubleTapTimeoutMillis: Long get() = CHAT_DOUBLE_TAP_MS
        }
    }
}

// ── Swipe to reply ───────────────────────────────────────────────────────────────────────────────

/**
 * How far a message has been pulled left to reply (px, ≤ 0), and the gesture that pulls it: past
 * [REPLY_AT] it answers on release (with a tick of haptics as it crosses). Drags that start at the
 * screen's very edges are left to the system's back gesture; vertical ones to the list.
 */
internal class SwipeReply {
    var offset by mutableFloatStateOf(0f)
}

private val REPLY_AT = 64.dp
private val EDGE = 24.dp

internal fun Modifier.swipeToReply(
    state: SwipeReply,
    enabled: Boolean,
    edgePx: Float,
    replyPx: Float,
    onCross: () -> Unit,
    onReply: () -> Unit,
    launch: (suspend () -> Unit) -> Unit,
): Modifier = if (!enabled) this else pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.position.x < edgePx || down.position.x > size.width - edgePx) return@awaitEachGesture
        // Only leftwards; anything else (and a list scroll that takes it first) ends it here.
        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            if (over < 0f) change.consume()
        } ?: return@awaitEachGesture
        var raw = 0f
        var crossed = false
        horizontalDrag(start.id) { change ->
            raw = (raw + change.positionChange().x).coerceAtMost(0f)
            change.consume()
            // Past the point it pulls harder, as a rubber band.
            val pulled = abs(raw)
            state.offset = -(if (pulled <= replyPx) pulled else replyPx + (pulled - replyPx) * 0.3f)
            if (!crossed && pulled >= replyPx) {
                crossed = true
                onCross()
            } else if (crossed && pulled < replyPx) {
                crossed = false
            }
        }
        if (crossed) onReply()
        val from = state.offset
        launch { animate(from, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f)) { v, _ -> state.offset = v } }
    }
}

@Composable
internal fun rememberReplyPx(): Pair<Float, Float> {
    val density = LocalDensity.current
    return remember(density) { with(density) { EDGE.toPx() to REPLY_AT.toPx() } }
}

/** The arrow that comes out from behind a message as it is pulled to reply. */
@Composable
internal fun ReplyArrow(state: SwipeReply, replyPx: Float, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .padding(end = 12.dp)
            .size(32.dp)
            .graphicsLayer {
                val p = (abs(state.offset) / replyPx).coerceIn(0f, 1f)
                alpha = p
                scaleX = 0.5f + 0.5f * p
                scaleY = 0.5f + 0.5f * p
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Icon(painterResource(R.drawable.chat_reply), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
    }
}

// ── A reaction, drawn ────────────────────────────────────────────────────────────────────────────

/**
 * An emoji as text, a picture sticker as its first frame. An animated sticker moves, filling the
 * same square as the others (cropped, with soft corners) so every reaction is the same size.
 */
@Composable
internal fun ReactionGlyph(key: String, size: Dp) {
    val reaction = remember(key) { ChatReactions.of(key) }
    when (reaction) {
        is ChatReactions.Reaction.Emoji -> Text(
            reaction.emoji,
            fontSize = with(LocalDensity.current) { (size * 0.82f).toSp() },
            lineHeight = with(LocalDensity.current) { size.toSp() },
            maxLines = 1,
        )
        is ChatReactions.Reaction.Sticker -> {
            val context = LocalContext.current
            val animated = reaction.sticker.animated
            val request = remember(key) {
                ImageRequest.Builder(context)
                    .data(reaction.sticker.uri)
                    .apply { if (!animated) decoderFactory(coil3.decode.BitmapFactoryDecoder.Factory()) }
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = reaction.sticker.code,
                contentScale = if (animated) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier
                    .size(size)
                    .then(if (animated) Modifier.clip(RoundedCornerShape(size * 0.22f)) else Modifier),
            )
        }
        // A newer app's reaction: shown as something, never dropped.
        null -> Text("•", fontSize = with(LocalDensity.current) { (size * 0.7f).toSp() }, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Reactions under a message ────────────────────────────────────────────────────────────────────

/**
 * The reactions under a message: mine tinted, a "2" when both of us put the same one. A tap on one
 * puts (or takes back) the same from me; a tap on mine that didn't go sends it again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReactionChips(
    reactions: List<ChatReaction>?,
    me: String?,
    mine: Boolean,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val present = reactions?.takeIf { list -> list.any { it.keyList.isNotEmpty() } }
    val shown = lastNonNull(present)
    AnimatedVisibility(
        visible = present != null,
        enter = expandVertically(tween(CHAT_MOTION_MS, easing = FastOutSlowInEasing)) + fadeIn(tween(CHAT_MOTION_MS)),
        exit = shrinkVertically(tween(CHAT_MOTION_MS, easing = FastOutSlowInEasing)) + fadeOut(tween(CHAT_MOTION_MS)),
        modifier = modifier,
    ) {
        val list = shown ?: return@AnimatedVisibility
        val myRow = list.firstOrNull { it.reactor == me }
        val mineKeys = myRow?.keyList.orEmpty()
        val theirKeys = list.filter { it.reactor != me }.flatMap { it.keyList }
        val keys = (mineKeys + theirKeys).distinct()
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp, if (mine) Alignment.End else Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.widthIn(max = 300.dp).padding(top = 3.dp),
        ) {
            keys.forEach { key ->
                val isMine = key in mineKeys
                val count = (if (isMine) 1 else 0) + (if (key in theirKeys) 1 else 0)
                val failed = isMine && myRow?.state == ChatMessage.STATE_FAILED
                val label = ChatReactions.of(key)?.let { (it as? ChatReactions.Reaction.Emoji)?.emoji ?: (it as? ChatReactions.Reaction.Sticker)?.sticker?.code }.orEmpty()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isMine) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                        .clickable { if (failed && myRow != null) ChatHub.retryReaction(myRow) else onToggle(key) }
                        .semantics { contentDescription = label + if (count > 1) " ×2" else "" }
                        // On its way it looks the same as sent (faded, it looked covered by a film);
                        // only one that didn't go shows, with its "!".
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    ReactionGlyph(key, 22.dp)
                    if (count > 1) {
                        Text("2", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 3.dp))
                    }
                    if (failed) {
                        Text("!", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 3.dp))
                    }
                }
            }
        }
    }
}

// ── The menu a tap on a message opens ────────────────────────────────────────────────────────────

/**
 * Where a bubble is on screen (root px). Kept current only while its menu is open, so scrolling
 * does no work for it; while open the menu follows the bubble (the keyboard going down, a new
 * message pushing the list up) instead of staying where the bubble was at the tap.
 */
internal class BubbleBounds {
    private var coords: androidx.compose.ui.layout.LayoutCoordinates? = null
    var tracking = false
    val rect = mutableStateOf<Rect?>(null)

    fun place(c: androidx.compose.ui.layout.LayoutCoordinates) {
        coords = c
        if (tracking) rect.value = c.boundsInRoot()
    }

    /** Starts following; null when the bubble isn't on screen. */
    fun start(): Rect? {
        tracking = true
        coords?.takeIf { it.isAttached }?.let { rect.value = it.boundsInRoot() }
        return rect.value
    }
}

/** A message the menu is open for: its bubble, and on which side. */
internal data class MenuTarget(
    val message: ChatMessage,
    val batch: List<ChatMessage>,
    val bubble: BubbleBounds,
    val mine: Boolean,
) {
    val bounds: Rect get() = bubble.rect.value ?: Rect.Zero
}

internal data class MenuAction(val label: String, val icon: Int, val danger: Boolean = false, val run: () -> Unit)

/**
 * Over the dimmed chat: the seven reactions used most above the message (with a button that opens
 * them all) and its actions under it, as in Telegram. Placed by the bubble, moved in when it would
 * leave the chat's area (below [topPx], above [bottomPx] from the bottom; root px).
 */
@Composable
internal fun MessageMenu(
    target: MenuTarget?,
    topPx: Int,
    bottomPx: Int,
    actions: List<MenuAction>,
    myKeys: List<String>,
    oldAppNote: String?,
    onReact: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val shown = lastNonNull(target)
    val shownActions = lastNonNull(actions.takeIf { target != null })
    // Keyed on what is shown, not on the target: closing (the target gone first) used to fold the
    // full set back into the seven and the actions for the length of the fade.
    // Each opening is its own (a new target even for the same message): opening the same message
    // again used to show the full set it was closed with.
    val opening = shown?.let { System.identityHashCode(it) }
    var expanded by remember(opening) { mutableStateOf(false) }
    // The seven are read once per opening, so they never move under the finger.
    val top = remember(opening) { ChatReactions.top() }
    // Where the seven stood (x, y, right edge): the full set grows out of that spot.
    val anchor = remember(opening) { IntArray(3) }
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    AnimatedVisibility(visible = target != null, enter = fadeIn(tween(CHAT_MOTION_MS)), exit = fadeOut(tween(160))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    val t = shown ?: return
    val transformOrigin = TransformOrigin(if (t.mine) 1f else 0f, 0.5f)
    AnimatedVisibility(
        visible = target != null,
        enter = fadeIn(tween(220)) + scaleIn(tween(280, easing = FastOutSlowInEasing), initialScale = 0.85f, transformOrigin = transformOrigin),
        exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.92f, transformOrigin = transformOrigin),
        modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        val density = LocalDensity.current
        val margin = with(density) { 12.dp.roundToPx() }
        val gap = with(density) { 8.dp.roundToPx() }
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                // 0: the reactions (or all of them, grown out of the seven), 1: the actions.
                androidx.compose.animation.AnimatedContent(
                    targetState = expanded,
                    transitionSpec = {
                        (fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120))) using
                            androidx.compose.animation.SizeTransform(clip = true) { _, _ -> tween(280, easing = FastOutSlowInEasing) }
                    },
                    contentAlignment = if (t.mine) Alignment.TopEnd else Alignment.TopStart,
                    modifier = Modifier.clip(RoundedCornerShape(22.dp)),
                    label = "reactions",
                ) { all ->
                    if (all) {
                        ReactionPicker(myKeys = myKeys, onPick = onReact)
                    } else {
                        ReactionBar(top = top, myKeys = myKeys, note = oldAppNote, onReact = onReact, onExpand = { expanded = true })
                    }
                }
                Box {
                    AnimatedVisibility(!expanded, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
                        shownActions?.let { MenuActions(it) }
                    }
                }
            },
        ) { measurables, constraints ->
            val loose = Constraints(maxWidth = constraints.maxWidth - 2 * margin, maxHeight = (constraints.maxHeight - bottomPx - 2 * margin).coerceAtLeast(0))
            val bar = measurables[0].measure(loose)
            val menu = measurables[1].measure(loose)
            val h = constraints.maxHeight - bottomPx
            val w = constraints.maxWidth
            // Read here, so the menu moves with the bubble without composing again. Of a long
            // bubble only the part in the chat's area counts.
            val whole = t.bounds.translate(-origin.x, -origin.y)
            val areaTop = (topPx - origin.y).coerceIn(0f, h.toFloat())
            val bubble = Rect(whole.left, whole.top.coerceIn(areaTop, h.toFloat()), whole.right, whole.bottom.coerceIn(areaTop, h.toFloat()))
            val minY = areaTop.roundToInt() + margin
            fun xFor(width: Int) = (if (t.mine) bubble.right.roundToInt() - width else bubble.left.roundToInt()).coerceIn(margin, (w - margin - width).coerceAtLeast(margin))
            var barY = bubble.top.roundToInt() - gap - bar.height
            var menuY = bubble.bottom.roundToInt() + gap
            val fits = barY >= minY && menuY + menu.height <= h - margin
            if (!fits) {
                // Not both around it: the two together, as close to the message as the screen allows.
                val total = bar.height + (if (menu.height > 0) gap + menu.height else 0)
                val want = if (barY < minY) bubble.bottom.roundToInt() + gap else bubble.top.roundToInt() - gap - total
                barY = want.coerceIn(minY, (h - margin - total).coerceAtLeast(minY))
                menuY = barY + bar.height + gap
            }
            if (!expanded) {
                anchor[0] = xFor(bar.width)
                anchor[1] = barY
                anchor[2] = anchor[0] + bar.width
            }
            // The full set grows from where the seven were (down, and to the left for one's own),
            // moved up only as far as it must to stay on screen.
            val barX = if (expanded) {
                (if (t.mine) anchor[2] - bar.width else anchor[0]).coerceIn(margin, (w - margin - bar.width).coerceAtLeast(margin))
            } else {
                xFor(bar.width)
            }
            if (expanded) barY = anchor[1].coerceIn(minY, (h - margin - bar.height).coerceAtLeast(minY))
            layout(w, constraints.maxHeight) {
                bar.place(barX, barY)
                menu.place(xFor(menu.width), menuY)
            }
        }
    }
}

@Composable
private fun ReactionBar(top: List<String>, myKeys: List<String>, note: String?, onReact: (String) -> Unit, onExpand: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                top.forEach { key ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (key in myKeys) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color.Transparent)
                            .clickable { onReact(key) },
                    ) {
                        ReactionGlyph(key, 30.dp)
                    }
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable(onClick = onExpand),
                ) {
                    Icon(painterResource(R.drawable.expand_more), stringResource(R.string.chat_reactions_all), modifier = Modifier.size(20.dp))
                }
            }
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(300.dp).padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MenuActions(actions: List<MenuAction>) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
        Column(Modifier.width(220.dp).padding(vertical = 6.dp)) {
            actions.forEach { action ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = action.run)
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                ) {
                    val color = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    Icon(painterResource(action.icon), null, tint = color, modifier = Modifier.size(20.dp))
                    Text(action.label, color = color, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 14.dp))
                }
            }
        }
    }
}

/** Every reaction (emoji, then stickers), with a search by name or sticker code. */
@Composable
internal fun ReactionPicker(myKeys: List<String>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val found = remember(query) { ChatReactions.search(query) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp, modifier = modifier) {
        Column(Modifier.width(320.dp).height(340.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(painterResource(R.drawable.search), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Box(Modifier.padding(start = 8.dp).weight(1f)) {
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.chat_reactions_search), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it.take(24) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(50.dp),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(found, key = { it.key }) { r ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (r.key in myKeys) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color.Transparent)
                            .clickable { onPick(r.key) },
                    ) {
                        ReactionGlyph(r.key, 36.dp)
                    }
                }
            }
        }
    }
}

/**
 * A person's app version, small, next to their name ("v2.2.1"): mine from this app, a friend's from
 * their hello. Nothing when their app doesn't say (older than the version that started saying it).
 */
@Composable
internal fun AppVersion(pubkey: String, modifier: Modifier = Modifier) {
    val peers by ChatHub.peers.collectAsState()
    val profile by com.metrolist.music.friends.FriendsHub.profile.collectAsState()
    val version = if (pubkey == profile?.pubkey) {
        com.metrolist.music.BuildConfig.VERSION_NAME.substringBefore('-')
    } else {
        peers[pubkey]?.version
    } ?: return
    Text(
        "v$version",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier,
    )
}

/** Settings → Messages: the reaction a double tap puts. */
@Composable
internal fun QuickReactionPicker(selected: String, onPick: (String) -> Unit) {
    ReactionPicker(myKeys = listOf(selected), onPick = onPick)
}

@Composable
internal fun rememberHaptic() = LocalHapticFeedback.current

@Composable
internal fun rememberLauncher(): (suspend () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(scope) { { block -> scope.launch { block() } } }
}
