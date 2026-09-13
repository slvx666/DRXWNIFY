/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.SpotifyLikedSortDescendingKey
import com.metrolist.music.constants.SpotifyLikedSortTypeKey
import com.metrolist.music.constants.SpotifySortType
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.playback.queues.SpotifyLikedSongsQueue
import com.metrolist.music.ui.component.DraggableScrollbar
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.ItemThumbnail
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.SortHeader
import com.metrolist.music.ui.menu.SpotifyTrackMenu
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.LocalDatabase
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.playback.SpotifyBatchDownload
import com.metrolist.music.playback.SpotifyYouTubeMapper
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import com.metrolist.music.viewmodels.SpotifyLikedSongsViewModel
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyTrack

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SpotifyLikedSongsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: SpotifyLikedSongsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val downloadUtil = com.metrolist.music.LocalDownloadUtil.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current
    val menuState = LocalMenuState.current
    val coroutineScope = rememberCoroutineScope()

    val tracks by viewModel.tracks.collectAsState()
    val total by viewModel.total.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val error by viewModel.error.collectAsState()

    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val currentSpotifyId by produceState<String?>(initialValue = null, mediaMetadata?.id) {
        val ytId = mediaMetadata?.id
        value = if (ytId != null) {
            withContext(Dispatchers.IO) { database.getSpotifyMatchByYouTubeId(ytId)?.spotifyId }
        } else {
            null
        }
    }

    // Downloaded state per track (spotifyId → resolved youtubeId → live download map).
    val downloads by downloadUtil.downloads.collectAsState()
    val downloadProgress by SpotifyBatchDownload.progress.collectAsState()
    val spotifyToYt by produceState<Map<String, String>>(initialValue = emptyMap(), tracks) {
        value = if (tracks.isEmpty()) {
            emptyMap()
        } else {
            withContext(Dispatchers.IO) {
                database.getSpotifyMatchesBySpotifyIds(tracks.map { it.id })
                    .associate { it.spotifyId to it.youtubeId }
            }
        }
    }

    val (sortType, onSortTypeChange) = rememberEnumPreference(
        SpotifyLikedSortTypeKey,
        SpotifySortType.ORIGINAL,
    )
    // Default sort: original API order (newest-added first) without reversing — the reverse of the
    // previous default, which showed oldest-added first.
    val (sortDescending, onSortDescendingChange) = rememberPreference(
        SpotifyLikedSortDescendingKey,
        false,
    )

    // Every track here is liked by definition — seed the shared cache so hearts also show wherever
    // else these tracks appear (top tracks, albums, search, player).
    LaunchedEffect(tracks) {
        if (tracks.isNotEmpty()) {
            com.metrolist.music.playback.SpotifyLikeCache.markLiked(tracks.map { it.id })
        }
    }

    val lazyListState = rememberLazyListState()
    val pullRefreshState = rememberPullToRefreshState()

    val mapper = remember { SpotifyYouTubeMapper(database) }

    var isSearching by rememberSaveable { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }

    val sortedTracks = remember(tracks, sortType, sortDescending) {
        val sorted = when (sortType) {
            SpotifySortType.ORIGINAL -> tracks
            SpotifySortType.NAME -> tracks.sortedBy { it.name.lowercase() }
            SpotifySortType.ARTIST -> tracks.sortedBy {
                it.artists.firstOrNull()?.name?.lowercase() ?: ""
            }
            SpotifySortType.DURATION -> tracks.sortedBy { it.durationMs }
        }
        if (sortDescending) sorted.reversed() else sorted
    }

    val filteredTracks = remember(sortedTracks, query) {
        if (query.text.isEmpty()) {
            sortedTracks
        } else {
            sortedTracks.filter { track ->
                track.name.contains(query.text, ignoreCase = true) ||
                    track.artists.any { it.name.contains(query.text, ignoreCase = true) }
            }
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
        }
    }

    BackHandler(enabled = isSearching) {
        isSearching = false
        query = TextFieldValue()
    }

    PullToRefreshBox(
        state = pullRefreshState,
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        indicator = {
            Indicator(
                isRefreshing = isRefreshing,
                state = pullRefreshState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()),
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            // Header
            item(key = "header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.spotify_liked_songs),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = pluralStringResource(R.plurals.n_song, total, total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (!isLoading && tracks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row {
                            androidx.compose.material3.Button(
                                onClick = {
                                    playerConnection.playQueue(
                                        SpotifyLikedSongsQueue(
                                            startIndex = 0,
                                            mapper = viewModel.mapper,
                                            tracks = sortedTracks,
                                        )
                                    )
                                },
                            ) {
                                Icon(
                                    painterResource(R.drawable.play),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(stringResource(R.string.play))
                            }

                            // "Download all" with the staged progress bar (right of Play).
                            // While a batch is running the button turns into a Cancel action (P14).
                            Spacer(modifier = Modifier.size(8.dp))
                            val isDownloading = downloadProgress != null
                            if (isDownloading) {
                                androidx.compose.material3.OutlinedButton(
                                    onClick = { SpotifyBatchDownload.cancel() },
                                ) {
                                    Icon(
                                        painterResource(R.drawable.close),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.size(8.dp))
                                    Text(stringResource(R.string.cancel))
                                }
                            } else {
                                androidx.compose.material3.OutlinedButton(
                                    onClick = {
                                        val toDownload = sortedTracks
                                        if (toDownload.isEmpty()) return@OutlinedButton
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.spotify_download_started, toDownload.size),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        // Run on the batch's own scope so it keeps going if the user
                                        // navigates away mid-download.
                                        val appContext = context.applicationContext
                                        SpotifyBatchDownload.start(
                                            appContext = appContext,
                                            tracks = toDownload,
                                            mapper = mapper,
                                            label = context.getString(R.string.liked_songs),
                                            downloads = downloadUtil.downloads,
                                            onFinished = { result ->
                                                Toast.makeText(
                                                    appContext,
                                                    appContext.getString(
                                                        R.string.spotify_dl_finished,
                                                        result.current, result.skipped, result.failed,
                                                    ),
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            },
                                        )
                                    },
                                ) {
                                    Icon(
                                        painterResource(R.drawable.download),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.size(8.dp))
                                    Text(stringResource(R.string.spotify_download_all))
                                }
                            }
                        }

                        // Staged progress: Searching → Downloading → Formatting, with skipped/failed.
                        downloadProgress?.let { p ->
                            Spacer(modifier = Modifier.height(12.dp))
                            val text = when (p.phase) {
                                SpotifyBatchDownload.Phase.SEARCHING ->
                                    stringResource(R.string.spotify_dl_searching, p.current, p.total)
                                SpotifyBatchDownload.Phase.DOWNLOADING ->
                                    stringResource(R.string.spotify_dl_downloading, p.current, p.total)
                                SpotifyBatchDownload.Phase.FORMATTING ->
                                    stringResource(R.string.spotify_dl_formatting)
                                SpotifyBatchDownload.Phase.DONE ->
                                    stringResource(R.string.spotify_dl_downloading, p.current, p.total)
                            }
                            val extra = buildList {
                                if (p.skipped > 0) add("⃠ ${p.skipped}")
                                if (p.failed > 0) add("✕ ${p.failed}")
                            }.joinToString("  ")
                            Text(
                                text = if (extra.isEmpty()) text else "$text   $extra",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { p.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            if (!isLoading && tracks.isNotEmpty()) {
                item(key = "sort") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        SortHeader(
                            sortType = sortType,
                            sortDescending = sortDescending,
                            onSortTypeChange = onSortTypeChange,
                            onSortDescendingChange = onSortDescendingChange,
                            sortTypeText = { type ->
                                when (type) {
                                    SpotifySortType.ORIGINAL -> R.string.sort_by_original
                                    SpotifySortType.NAME -> R.string.sort_by_name
                                    SpotifySortType.ARTIST -> R.string.sort_by_artist
                                    SpotifySortType.DURATION -> R.string.sort_by_duration
                                }
                            },
                        )
                    }
                }
            }

            if (isLoading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (error != null) {
                item(key = "error") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = error ?: "",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        androidx.compose.material3.OutlinedButton(
                            onClick = { viewModel.retry() },
                        ) {
                            Text(stringResource(R.string.retry_button))
                        }
                    }
                }
            }

            if (!isLoading && error == null && tracks.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.spotify_no_tracks),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Track list
            val displayTracks = if (isSearching) filteredTracks else sortedTracks
            itemsIndexed(
                items = displayTracks,
                key = { index, track -> "liked_${track.id}_$index" },
            ) { index, track ->
                val thumbnailUrl = SpotifyMapper.getTrackThumbnail(track)
                val originalIndex = if (isSearching) sortedTracks.indexOf(track).coerceAtLeast(0) else index

                val isActive = currentSpotifyId != null && currentSpotifyId == track.id
                val trackDownloadState = spotifyToYt[track.id]?.let { downloads[it]?.state }
                ListItem(
                    title = track.name,
                    subtitle = joinByBullet(
                        track.artists.joinToString { it.name },
                        makeTimeString((track.durationMs).toLong()),
                    ),
                    isActive = isActive,
                    badges = {
                        // Filled heart BEFORE the artist name (every track here is liked on Spotify).
                        // Tapping it unlikes on Spotify and drops the row.
                        Icon(
                            painterResource(R.drawable.favorite),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 2.dp)
                                .clickable { viewModel.unlike(track) },
                        )
                    },
                    trailingContent = {
                        // Downloaded badge on the right.
                        com.metrolist.music.ui.component.Icon.Download(trackDownloadState)
                    },
                    thumbnailContent = {
                        ItemThumbnail(
                            thumbnailUrl = thumbnailUrl,
                            isActive = isActive,
                            isPlaying = isPlaying,
                            shape = RoundedCornerShape(ThumbnailCornerRadius),
                            modifier = Modifier.size(ListThumbnailSize),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                playerConnection.playQueue(
                                    SpotifyLikedSongsQueue(
                                        startIndex = originalIndex,
                                        mapper = viewModel.mapper,
                                        tracks = sortedTracks,
                                    )
                                )
                            },
                            onLongClick = {
                                menuState.show {
                                    SpotifyTrackMenu(
                                        track = track,
                                        mapper = mapper,
                                        onDismiss = menuState::dismiss,
                                        navController = navController,
                                    )
                                }
                            },
                        )
                        .animateItem(),
                )
            }
        }

        DraggableScrollbar(
            modifier = Modifier
                .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues())
                .align(Alignment.CenterEnd),
            scrollState = lazyListState,
            headerItems = 1,
        )

        TopAppBar(
            title = {
                if (isSearching) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                } else {
                    Text(stringResource(R.string.spotify_liked_songs))
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = {
                        if (isSearching) {
                            isSearching = false
                            query = TextFieldValue()
                        } else {
                            navController.navigateUp()
                        }
                    },
                    onLongClick = {
                        if (!isSearching) {
                            navController.backToMain()
                        }
                    },
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
            actions = {
                if (!isSearching) {
                    IconButton(
                        onClick = { isSearching = true },
                        onLongClick = {},
                    ) {
                        Icon(
                            painterResource(R.drawable.search),
                            contentDescription = null,
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            onLongClick = {},
                        ) {
                            Icon(
                                painterResource(R.drawable.more_vert),
                                contentDescription = null,
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_download)) },
                                leadingIcon = {
                                    Icon(
                                        painterResource(R.drawable.download),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    val toDownload = sortedTracks
                                    if (toDownload.isNotEmpty()) {
                                        Timber.d("SpotifyLikedDownload: started, ${toDownload.size} tracks")
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.spotify_download_started, toDownload.size),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        coroutineScope.launch {
                                            // Shared helper: bounded-parallel resolve + enqueue with live progress,
                                            // instead of the old silent one-by-one loop that looked frozen.
                                            val result = SpotifyBatchDownload.run(
                                                context = context,
                                                tracks = toDownload,
                                                mapper = mapper,
                                                label = context.getString(R.string.liked_songs),
                                                downloads = downloadUtil.downloads,
                                            )
                                            Toast.makeText(
                                                context,
                                                context.getString(
                                                    R.string.spotify_dl_finished,
                                                    result.current, result.skipped, result.failed,
                                                ),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}
