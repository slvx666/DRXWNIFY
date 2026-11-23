/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.friends

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.height
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.friends.ChatMusic
import com.metrolist.music.friends.FriendsHub
import com.metrolist.music.utils.AppLinks
import com.metrolist.spotify.models.SpotifyLibraryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAB_RECENT = 0
private const val TAB_LIBRARY = 1
private const val TAB_SEARCH = 2

private val ChatMusic.key: String get() = kind.path + ":" + id

/**
 * Recent and library lists, kept between openings of the panel: shown at once, and looked up again
 * (quietly, the old list staying) only when a few minutes old. Both start loading as the panel opens.
 */
private object PickerCache {
    private const val FRESH_MS = 5 * 60_000L
    private const val PINNED_IN_ACCOUNT = "pinned|"
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    val recent = kotlinx.coroutines.flow.MutableStateFlow<List<ChatMusic>?>(null)
    /** What the library tab shows; filled in parts (the phone's own first, the account's after). */
    val library = kotlinx.coroutines.flow.MutableStateFlow<PickerLibrary?>(null)
    private var recentAt = 0L
    private var libraryAt = 0L
    private var recentJob: kotlinx.coroutines.Job? = null
    private var libraryJob: kotlinx.coroutines.Job? = null

    @Synchronized
    fun refresh() {
        val now = System.currentTimeMillis()
        if (now - recentAt > FRESH_MS && recentJob?.isActive != true) {
            recentJob = scope.launch {
                loadRecent()?.let { recent.value = it; recentAt = System.currentTimeMillis() }
            }
        }
        if (now - libraryAt > FRESH_MS && libraryJob?.isActive != true) {
            libraryJob = scope.launch {
                val loaded = loadLibrary()
                if (loaded) libraryAt = System.currentTimeMillis()
                // Failed with nothing shown yet: an empty library rather than loading forever.
                if (library.value == null) library.value = PickerLibrary()
            }
        }
    }

    private suspend fun loadRecent(): List<ChatMusic>? {
        val db = FriendsHub.databaseOrNull() ?: return emptyList()
        return runCatching {
            db.events().first().asSequence()
                .map { it.song }
                .distinctBy { it.id }
                .mapNotNull(::pickable)
                .take(300)
                .toList()
        }.getOrNull()
    }

    /**
     * The phone's own part first (pinned, the app's playlists, hearts, added tracks: no network, so
     * shown at once and the same without a music account), then the account's albums, playlists
     * and liked tracks page by page, each page shown as it comes. Before, nothing showed until the
     * whole account library and up to 3000 likes had been fetched.
     */
    private suspend fun loadLibrary(): Boolean {
        val db = FriendsHub.databaseOrNull()
        val pins = com.metrolist.music.catalog.LibraryPins.pinned.value
        fun songMusic(s: com.metrolist.music.db.entities.Song) = pickable(s)

        val covers = runCatching {
            db?.playlistLatestThumbnails()?.first().orEmpty().associate { it.playlistId to it.thumbnailUrl }
        }.getOrDefault(emptyMap())
        val own = runCatching {
            db?.playlists(com.metrolist.music.constants.PlaylistSortType.CREATE_DATE, descending = true)?.first().orEmpty()
                .filter { it.playlist.isEditable }
                .map { p ->
                    OwnPlaylist(
                        id = p.id,
                        name = p.playlist.name,
                        cover = listOfNotNull(p.playlist.thumbnailUrl, p.thumbnails.firstOrNull(), covers[p.id]).firstOrNull { it.startsWith("http") },
                        count = p.songCount,
                        pinKey = com.metrolist.music.viewmodels.SpotifyLibraryViewModel.LOCAL_PLAYLIST_URI_PREFIX + p.id,
                    )
                }
        }.getOrDefault(emptyList())
        val localLiked = runCatching {
            db?.likedSongsByCreateDateAsc()?.first().orEmpty().asReversed().mapNotNull(::songMusic)
        }.getOrDefault(emptyList())
        val added = runCatching {
            db?.songsByCreateDateAsc()?.first().orEmpty().asReversed().filterNot { it.song.liked }.mapNotNull(::songMusic)
        }.getOrDefault(emptyList())
        val local = PickerLibrary(
            pinnedOwn = own.filter { it.pinKey in pins }.sortedBy { pins.indexOf(it.pinKey) },
            own = own.filterNot { it.pinKey in pins },
            liked = localLiked,
            added = added,
        )
        library.value = (library.value ?: PickerLibrary()).copy(
            pinnedOwn = local.pinnedOwn, own = local.own, added = local.added,
            liked = mergeLiked(library.value?.serviceLiked.orEmpty(), localLiked),
            localLiked = localLiked,
        )

        if (Catalog.source == null) return true
        return runCatching {
            runCatching { Catalog.ensureAuthenticated() }
            val entries = mutableListOf<Pair<String, ChatMusic>>()
            var offset = 0
            while (offset < 1000) {
                val page = Catalog.myLibrary(null, 50, offset).getOrNull() ?: break
                page.items.mapNotNullTo(entries) { e ->
                    val kind = when (e.kind) {
                        SpotifyLibraryEntry.Kind.ALBUM -> AppLinks.Kind.ALBUM
                        SpotifyLibraryEntry.Kind.PLAYLIST -> AppLinks.Kind.PLAYLIST
                        SpotifyLibraryEntry.Kind.ARTIST -> AppLinks.Kind.ARTIST
                        else -> return@mapNotNullTo null
                    }
                    // Pinned in the account itself (a green pin in the library) counts as pinned too.
                    (if (e.pinned) PINNED_IN_ACCOUNT + e.uri.ifBlank { e.id } else e.uri.ifBlank { e.id }) to
                        ChatMusic(kind, e.id, e.name, e.creator.orEmpty(), e.imageUrl?.takeIf { it.startsWith("http") }, count = e.totalCount)
                }
                offset += 50
                // Pinned ones on top, as in the library itself.
                val (pinned, rest) = entries.partition { it.first in pins || it.first.startsWith(PINNED_IN_ACCOUNT) }
                library.value = library.value?.copy(
                    pinned = pinned.sortedBy { pins.indexOf(it.first).let { i -> if (i < 0) Int.MAX_VALUE else i } }.map { it.second },
                    collections = rest.map { it.second },
                )
                if (page.items.isEmpty() || offset >= page.total) break
            }
            // All liked tracks: the app's own copy of them when it has one, else page by page.
            val cachedLikes = Catalog.source?.let { com.metrolist.music.catalog.LikedSongsCache.get(it.name) }?.tracks.orEmpty()
            fun publish(tracks: List<com.metrolist.spotify.models.SpotifyTrack>) {
                val service = tracks.filter { !it.isLocal }.mapNotNull { ChatMusic.ofTrack(FriendsHub.refOfCatalog(it)) }.distinctBy { it.key }
                library.value = library.value?.let { it.copy(serviceLiked = service, liked = mergeLiked(service, it.localLiked)) }
            }
            if (cachedLikes.isNotEmpty()) {
                publish(cachedLikes)
            } else {
                val out = mutableListOf<com.metrolist.spotify.models.SpotifyTrack>()
                var likedOffset = 0
                while (likedOffset < 3000) {
                    val page = Catalog.likedSongs(50, likedOffset).getOrNull() ?: break
                    page.items.mapTo(out) { it.track }
                    likedOffset += 50
                    publish(out)
                    if (page.items.isEmpty() || likedOffset >= page.total) break
                }
            }
            true
        }.getOrDefault(false)
    }

    /** The account's liked tracks, then hearts given in the app that the account doesn't have. */
    private fun mergeLiked(service: List<ChatMusic>, local: List<ChatMusic>): List<ChatMusic> {
        if (service.isEmpty()) return local
        val keys = service.mapTo(HashSet()) { it.key }
        return service + local.filterNot { it.key in keys }
    }

    /** A playlist made in the app: its tracks, looked up when it is opened in the panel. */
    suspend fun tracksOf(playlistId: String): List<ChatMusic> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val db = FriendsHub.databaseOrNull()
        runCatching {
            val songs = db?.playlistSongs(playlistId)?.first().orEmpty()
            songs.mapNotNull { ps -> pickable(ps.song) }.also { android.util.Log.i("DrxwPicker", "playlist $playlistId: ${songs.size} rows, ${it.size} sendable (db=${db != null})") }
        }.onFailure { android.util.Log.w("DrxwPicker", "playlist $playlistId tracks failed", it) }.getOrDefault(emptyList())
    }
}

/**
 * A track of the phone's library as something to send. A track found on a source site (VK…) goes
 * too, by its source id: the friend's app plays the same upload, or finds it by title and artist.
 * Only files from the phone itself can't be sent.
 */
private fun pickable(s: com.metrolist.music.db.entities.Song): ChatMusic? {
    val title = s.title
    val by = s.orderedArtists.joinToString { it.name }
    val ms = s.song.duration.coerceAtLeast(0) * 1000L
    ChatMusic.ofMedia(s.id, title, by, s.thumbnailUrl, ms)?.let { return it }
    if (s.id.startsWith("local:")) return null
    val catalogId = com.metrolist.music.resolver.FallbackIds.catalogIdOf(s.id) ?: return null
    return ChatMusic(AppLinks.Kind.TRACK, catalogId, title, by, s.thumbnailUrl?.takeIf { it.startsWith("http") }, ms)
}

/** A playlist made in the app, in the library tab (opened in place to pick its tracks). */
private data class OwnPlaylist(val id: String, val name: String, val cover: String?, val count: Int, val pinKey: String)

private data class PickerLibrary(
    val pinned: List<ChatMusic> = emptyList(),
    val pinnedOwn: List<OwnPlaylist> = emptyList(),
    val own: List<OwnPlaylist> = emptyList(),
    val collections: List<ChatMusic> = emptyList(),
    val liked: List<ChatMusic> = emptyList(),
    val serviceLiked: List<ChatMusic> = emptyList(),
    val localLiked: List<ChatMusic> = emptyList(),
    val added: List<ChatMusic> = emptyList(),
)

/**
 * The chat's music panel (the right button with nothing typed): about half the space, pulled up for
 * more, down to close. Recently played, the library, and a search, side by side (a swipe or a tab);
 * several tracks, albums, playlists or artists can be ticked and go as one card each.
 *
 * Drawn inside the chat's own window, not as a dialog; the keyboard goes as it opens (the panel can't
 * be put over the keyboard), and the panel sits above it while its own search is typed in.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MusicPickerPanel(visible: Boolean, onDismiss: () -> Unit, onSend: (List<ChatMusic>) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var mounted by remember { mutableStateOf(false) }
    // The sheet's height in px; 0 is closed.
    var sheet by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var expanded by remember { mutableStateOf(false) }
    var motion by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val selected = remember { mutableStateListOf<ChatMusic>() }

    val imePx = WindowInsets.ime.getBottom(density)
    val imeTargetPx = WindowInsets.imeAnimationTarget.getBottom(density)
    val navPx = WindowInsets.navigationBars.getBottom(density)
    val topPx = WindowInsets.statusBars.getTop(density)
    // Above the keyboard only while it is up or rising (the panel's own search). A keyboard going
    // away (the chat's, as the panel opens) just slides down over it: following it frame by frame,
    // together with the panel's own rise, made the opening stutter.
    val bottomPx = if (imeTargetPx > navPx) maxOf(imePx, navPx) else navPx

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullPx = (constraints.maxHeight - bottomPx - topPx - with(density) { 24.dp.toPx() }).coerceAtLeast(0f)
        val halfPx = fullPx * 0.55f

        fun moveTo(target: Float, then: () -> Unit = {}) {
            motion?.cancel()
            motion = scope.launch {
                androidx.compose.animation.core.animate(
                    sheet, target,
                    animationSpec = androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                ) { v, _ -> sheet = v }
                then()
            }
        }

        LaunchedEffect(visible) {
            if (visible) {
                mounted = true
                selected.clear()
                expanded = false
                PickerCache.refresh()
                moveTo(halfPx)
            } else if (mounted) {
                // It stays built (not drawn) while the chat is open: built again on every opening,
                // its first frames took 30-45 ms.
                moveTo(0f)
            }
        }
        // The keyboard rising or going: the sheet keeps its place relative to the space left. While
        // it is still rising it heads for the new place: opened with the chat's keyboard up, it
        // used to aim at the half of the space over the keyboard and stay that small after it went.
        LaunchedEffect(halfPx, fullPx) {
            if (!visible || !mounted) return@LaunchedEffect
            val target = if (expanded) fullPx else halfPx
            if (motion?.isActive == true) moveTo(target) else sheet = target
        }
        androidx.activity.compose.BackHandler(enabled = visible) { onDismiss() }
        if (!mounted) return@BoxWithConstraints

        /** After a drag or fling: closed, half or full, by where it is and how fast it went. */
        fun settle(velocity: Float) {
            val h = sheet
            val target = when {
                velocity < -1200f -> fullPx
                velocity > 1200f -> if (h > halfPx + 1f) halfPx else 0f
                h < halfPx * 0.5f -> 0f
                h < (halfPx + fullPx) / 2 -> halfPx
                else -> fullPx
            }
            if (target == 0f) {
                onDismiss()
            } else {
                expanded = target == fullPx
                moveTo(target)
            }
        }

        // The lists pull the sheet up before they scroll, and down once they're at their top.
        var draggedSheet by remember { mutableStateOf(false) }
        val nested = remember(fullPx) {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    if (available.y < 0f && sheet < fullPx) {
                        motion?.cancel()
                        val new = (sheet - available.y).coerceAtMost(fullPx)
                        val used = new - sheet
                        sheet = new
                        draggedSheet = true
                        return androidx.compose.ui.geometry.Offset(0f, -used)
                    }
                    return androidx.compose.ui.geometry.Offset.Zero
                }

                override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    if (available.y > 0f && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) {
                        motion?.cancel()
                        val new = (sheet - available.y).coerceAtLeast(0f)
                        val used = sheet - new
                        sheet = new
                        draggedSheet = true
                        return androidx.compose.ui.geometry.Offset(0f, used)
                    }
                    return androidx.compose.ui.geometry.Offset.Zero
                }

                override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                    if (!draggedSheet) return androidx.compose.ui.unit.Velocity.Zero
                    draggedSheet = false
                    settle(available.y)
                    return available
                }
            }
        }

        // The shade over the chat: darker as the sheet rises; a tap on it closes. The sheet's height
        // is read only while drawing and placing, so its motion composes nothing.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val shade = if (halfPx > 0f) (sheet / halfPx).coerceIn(0f, 1f) * 0.4f else 0f
                    if (shade > 0f) drawRect(Color.Black.copy(alpha = shade))
                }
                .then(
                    if (visible) {
                        Modifier.pointerInput(Unit) { detectTapGestures { onDismiss() } }
                    } else {
                        Modifier
                    },
                ),
        )

        val selectedKeys = selected.map { it.key }.toSet()
        fun toggle(m: ChatMusic) {
            val at = selected.indexOfFirst { it.key == m.key }
            if (at >= 0) selected.removeAt(at) else selected.add(m)
        }
        val pager = androidx.compose.foundation.pager.rememberPagerState { 3 }
        val dragHeader = androidx.compose.foundation.gestures.rememberDraggableState { d ->
            motion?.cancel()
            sheet = (sheet - d).coerceIn(0f, fullPx)
        }
        // Closed, it keeps its last height (its lists stay built) but isn't placed: not drawn, not touched.
        val lastPx = remember { FloatArray(1) }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val open = sheet > 0f
                    if (open) lastPx[0] = sheet
                    val h = ((if (open) sheet else lastPx[0]) + bottomPx).roundToInt().coerceIn(0, constraints.maxHeight)
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, h) { if (open) placeable.place(0, 0) }
                }
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                // Taps inside never reach the shade.
                .pointerInput(Unit) { detectTapGestures { } }
                .nestedScroll(nested)
                .padding(bottom = with(density) { bottomPx.toDp() }),
        ) {
            // The handle, the title and the tabs: dragged, they move the sheet.
            Column(
                Modifier.draggable(
                    state = dragHeader,
                    orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                    onDragStopped = { v -> settle(v) },
                ),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                }
                // As tall as the "Send" button from the start: it coming in used to push all below down.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 12.dp, bottom = 4.dp).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.chat_music_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    AnimatedVisibility(selected.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                        Button(onClick = { onSend(selected.toList()) }) {
                            Icon(painterResource(R.drawable.chat_send), null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.chat_send_n, selected.size), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                PrimaryTabRow(selectedTabIndex = pager.targetPage, containerColor = Color.Transparent) {
                    listOf(R.string.chat_tab_recent, R.string.chat_tab_library, R.string.chat_tab_search).forEachIndexed { i, label ->
                        Tab(
                            selected = pager.targetPage == i,
                            onClick = {
                                scope.launch {
                                    pager.animateScrollToPage(i, animationSpec = androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                                }
                            },
                            text = { Text(stringResource(label)) },
                        )
                    }
                }
            }
            // The other tabs are built once the panel is up, not in its first frames, and one at a
            // time: both in one frame took 40+ ms.
            var beyond by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(400)
                beyond = 1
                kotlinx.coroutines.delay(250)
                beyond = 2
            }
            androidx.compose.foundation.pager.HorizontalPager(
                state = pager,
                beyondViewportPageCount = beyond,
                modifier = Modifier.weight(1f),
            ) { page ->
                when (page) {
                    TAB_RECENT -> RecentList(selectedKeys, ::toggle)
                    TAB_LIBRARY -> LibraryList(selectedKeys, ::toggle)
                    else -> SearchList(selectedKeys, ::toggle)
                }
            }
        }
    }
}

/** Tracks played lately, newest first, each once. */
@Composable
private fun RecentList(selected: Set<String>, onToggle: (ChatMusic) -> Unit) {
    val items by PickerCache.recent.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        FilterField(query, { query = it }, R.string.chat_find_recent)
        val list = items
        val shown = remember(list, query) { list?.filter { it.matches(query) } }
        PickList(shown, selected, onToggle, empty = if (query.isBlank()) R.string.chat_nothing_played else R.string.chat_nothing_found)
    }
}

private sealed interface LibRow {
    data class Header(val title: Int) : LibRow
    data class Music(val m: ChatMusic, val nested: Boolean = false) : LibRow
    data class Own(val p: OwnPlaylist, val open: Boolean) : LibRow
    data class OwnLoading(val id: String) : LibRow
}

private val LibRow.rowKey: String get() = when (this) {
    is LibRow.Header -> "h$title"
    is LibRow.Music -> (if (nested) "n:" else "") + m.key
    is LibRow.Own -> "own:" + p.id
    is LibRow.OwnLoading -> "ownload:$id"
}

/**
 * Pinned first, then the playlists made in the app (opened in place to pick their tracks), the
 * account's albums, playlists and artists, liked tracks and tracks added to the library. The
 * phone's own part shows at once and the same without a music account.
 */
@Composable
private fun LibraryList(selected: Set<String>, onToggle: (ChatMusic) -> Unit) {
    val loaded by PickerCache.library.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val openTracks by produceState<List<ChatMusic>?>(null, openId) {
        value = null
        value = openId?.let { PickerCache.tracksOf(it) }
    }
    val list: List<LibRow>? = remember(loaded, query, openId, openTracks) {
        val lib = loaded ?: return@remember null
        fun ownRows(ps: List<OwnPlaylist>): List<LibRow> = ps.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }.flatMap { p ->
            val open = p.id == openId
            buildList {
                add(LibRow.Own(p, open))
                if (open) {
                    val tracks = openTracks
                    if (tracks == null) add(LibRow.OwnLoading(p.id)) else tracks.filter { it.matches(query) }.forEach { add(LibRow.Music(it, nested = true)) }
                }
            }
        }
        buildList {
            val pinned = lib.pinned.filter { it.matches(query) }
            val pinnedOwn = ownRows(lib.pinnedOwn)
            if (pinned.isNotEmpty() || pinnedOwn.isNotEmpty()) {
                add(LibRow.Header(R.string.chat_pinned))
                addAll(pinnedOwn)
                pinned.forEach { add(LibRow.Music(it)) }
            }
            val own = ownRows(lib.own)
            if (own.isNotEmpty()) {
                add(LibRow.Header(R.string.chat_own_playlists))
                addAll(own)
            }
            val c = lib.collections.filter { it.matches(query) }
            if (c.isNotEmpty()) {
                add(LibRow.Header(R.string.chat_collections))
                c.forEach { add(LibRow.Music(it)) }
            }
            val l = lib.liked.filter { it.matches(query) }
            if (l.isNotEmpty()) {
                add(LibRow.Header(R.string.chat_liked_tracks))
                l.forEach { add(LibRow.Music(it)) }
            }
            val likedKeys = lib.liked.mapTo(HashSet()) { it.key }
            val a = lib.added.filter { it.key !in likedKeys && it.matches(query) }
            if (a.isNotEmpty()) {
                add(LibRow.Header(R.string.chat_added_tracks))
                a.forEach { add(LibRow.Music(it)) }
            }
        }.distinctBy { it.rowKey }
    }
    Column(Modifier.fillMaxSize()) {
        FilterField(query, { query = it }, R.string.chat_find_library)
        when {
            list == null -> Loading()
            list.isEmpty() -> Note(stringResource(if (query.isBlank()) R.string.chat_library_empty else R.string.chat_nothing_found))
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                items(list, key = { it.rowKey }) { row ->
                    when (row) {
                        is LibRow.Header -> Text(
                            stringResource(row.title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                        )
                        is LibRow.Music -> Box(Modifier.padding(start = if (row.nested) 24.dp else 0.dp)) {
                            PickRow(row.m, row.m.key in selected) { onToggle(row.m) }
                        }
                        is LibRow.Own -> OwnPlaylistRow(row.p, row.open) { openId = if (row.open) null else row.p.id }
                        is LibRow.OwnLoading -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

/** A playlist made in the app: a tap opens its tracks right below it (and closes them again). */
@Composable
private fun OwnPlaylistRow(p: OwnPlaylist, open: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AsyncImage(
            model = p.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.chat_playlist) + " · " + androidx.compose.ui.res.pluralStringResource(R.plurals.n_song, p.count, p.count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(
            painterResource(if (open) R.drawable.expand_less else R.drawable.expand_more),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchList(selected: Set<String>, onToggle: (ChatMusic) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChatMusic>?>(emptyList()) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(400)
        results = null
        results = withContext(Dispatchers.IO) {
            runCatching { Catalog.ensureAuthenticated() }
            val r = Catalog.search(q, listOf("track", "album", "playlist", "artist"), 8).getOrNull()
            buildList {
                r?.tracks?.items.orEmpty().forEach { t -> ChatMusic.ofTrack(FriendsHub.refOfCatalog(t))?.let(::add) }
                r?.albums?.items.orEmpty().forEach { a ->
                    add(ChatMusic(AppLinks.Kind.ALBUM, a.id, a.name, a.artists.joinToString { it.name }, a.images.firstOrNull()?.url, count = a.totalTracks))
                }
                r?.playlists?.items.orEmpty().filterNotNull().forEach { p ->
                    add(ChatMusic(AppLinks.Kind.PLAYLIST, p.id, p.name, p.owner?.displayName.orEmpty(), p.images.firstOrNull()?.url, count = p.tracks?.total ?: 0))
                }
                r?.artists?.items.orEmpty().forEach { a ->
                    add(ChatMusic(AppLinks.Kind.ARTIST, a.id, a.name, "", a.images.firstOrNull()?.url))
                }
            }.filter { it.id.isNotBlank() }.ifEmpty {
                // No catalog answer (no music account, or it is down): YouTube's songs instead.
                com.metrolist.innertube.YouTube.search(q, com.metrolist.innertube.YouTube.SearchFilter.FILTER_SONG)
                    .getOrNull()?.items.orEmpty()
                    .filterIsInstance<com.metrolist.innertube.models.SongItem>()
                    .mapNotNull { song ->
                        ChatMusic.ofMedia(song.id, song.title, song.artists.joinToString { it.name }, song.thumbnail, (song.duration ?: 0) * 1000L)
                    }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        FilterField(query, { query = it }, R.string.chat_search_hint)
        if (query.trim().length >= 2) {
            PickList(results, selected, onToggle, empty = R.string.chat_nothing_found)
        }
    }
}

/** Every word typed is somewhere in the title or the artist (any case, any order). */
private fun ChatMusic.matches(query: String): Boolean {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val hay = "$title $by"
    return words.all { hay.contains(it, ignoreCase = true) }
}

@Composable
private fun FilterField(query: String, onChange: (String) -> Unit, hint: Int) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(stringResource(hint)) },
        leadingIcon = { Icon(painterResource(R.drawable.search), null) },
        trailingIcon = {
            AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                androidx.compose.material3.IconButton(onClick = { onChange("") }) { Icon(painterResource(R.drawable.close), null) }
            }
        },
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun PickList(items: List<ChatMusic>?, selected: Set<String>, onToggle: (ChatMusic) -> Unit, empty: Int) {
    when {
        items == null -> Loading()
        items.isEmpty() -> Note(stringResource(empty))
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            items(items, key = { it.key }) { m -> PickRow(m, m.key in selected) { onToggle(m) } }
        }
    }
}

@Composable
private fun Loading() {
    Column(Modifier.fillMaxWidth()) {
        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    )
}

/** One track, album, playlist or artist with a round tick on the right. */
@Composable
private fun PickRow(m: ChatMusic, checked: Boolean, onClick: () -> Unit) {
    val rowColor by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        label = "pick",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(rowColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AsyncImage(
            model = m.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(if (m.kind == AppLinks.Kind.ARTIST) CircleShape else RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
            Text(m.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val kind = stringResource(
                when (m.kind) {
                    AppLinks.Kind.TRACK -> R.string.chat_track
                    AppLinks.Kind.ALBUM -> R.string.chat_album
                    AppLinks.Kind.PLAYLIST -> R.string.chat_playlist
                    AppLinks.Kind.COLLAB -> R.string.collab_playlists_title
                    AppLinks.Kind.ARTIST -> R.string.chat_artist
                },
            )
            Text(
                listOf(kind, m.by).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val tickColor by animateColorAsState(if (checked) MaterialTheme.colorScheme.primary else Color.Transparent, label = "tick")
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(tickColor)
                .border(2.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
        ) {
            androidx.compose.animation.AnimatedVisibility(visible = checked, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                Icon(painterResource(R.drawable.check), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            }
        }
    }
}
