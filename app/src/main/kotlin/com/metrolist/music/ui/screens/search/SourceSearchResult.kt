/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.PauseSearchHistoryKey
import com.metrolist.music.constants.SearchSource
import com.metrolist.music.constants.SearchSourceKey
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.db.entities.SearchHistory
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.ProviderMatch
import com.metrolist.music.resolver.SourceSearch
import com.metrolist.music.ui.component.ChipsRow
import com.metrolist.music.ui.component.EmptyPlaceholder
import com.metrolist.music.ui.component.ItemThumbnail
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.SearchSourceButton
import com.metrolist.music.ui.component.shimmer.ListItemPlaceHolder
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.ui.menu.SourceTrackMenu
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.SourceSearchFilter
import com.metrolist.music.viewmodels.SourceSearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * Results of the experimental search: tracks taken straight from the audio sources, with no catalog
 * entry behind them. The sources return tracks only, so "Artists" is folded out of those results and
 * opening one searches the sources again by name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SourceSearchResult(
    navController: NavController,
    viewModel: SourceSearchViewModel = hiltViewModel(),
    pureBlack: Boolean = false,
) {
    val database = LocalDatabase.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    val results by viewModel.results.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val selectedArtist by viewModel.selectedArtist.collectAsState()
    val source by viewModel.source.collectAsState()
    val artistTracks by viewModel.artistTracks.collectAsState()
    val artistLoading by viewModel.artistLoading.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val pauseSearchHistory by rememberPreference(PauseSearchHistoryKey, defaultValue = false)

    var searchSource by rememberEnumPreference(SearchSourceKey, SearchSource.SOURCES)
    var pendingSource by remember { mutableStateOf<SearchSource?>(null) }

    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(viewModel.query, TextRange(viewModel.query.length)))
    }

    // Leaving the experimental mode reloads the query from whichever source was picked.
    LaunchedEffect(searchSource, pendingSource) {
        val target = pendingSource ?: return@LaunchedEffect
        if (searchSource != target) return@LaunchedEffect
        pendingSource = null
        if (target == SearchSource.LOCAL) {
            navController.navigateUp()
        } else {
            val encoded = URLEncoder.encode(viewModel.query, "UTF-8")
            navController.navigate("search/$encoded") {
                popUpTo("search/$encoded") { inclusive = true }
            }
        }
    }

    val onSearch: (String) -> Unit = { text ->
        if (text.isNotEmpty()) {
            focusManager.clearFocus()
            if (!pauseSearchHistory) {
                coroutineScope.launch(Dispatchers.IO) {
                    database.query { insert(SearchHistory(query = text)) }
                }
            }
            navController.navigate("search/${URLEncoder.encode(text, "UTF-8")}") {
                popUpTo("search/${URLEncoder.encode(viewModel.query, "UTF-8")}") { inclusive = true }
            }
        }
    }

    /** Plays [list] starting at [index]; the whole list becomes the queue. */
    fun play(list: List<ProviderMatch>, index: Int) {
        playerConnection.playQueue(
            ListQueue(
                title = selectedArtist ?: query.text,
                items = list.map { SourceSearch.mediaItemOf(it) },
                startIndex = index,
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (pureBlack) Color.Black else MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                Text(
                    text = stringResource(R.string.search_sources_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            leadingIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = stringResource(R.string.dismiss),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.text.isNotEmpty()) {
                        IconButton(onClick = { query = TextFieldValue("") }) {
                            Icon(
                                painter = painterResource(R.drawable.close),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    SearchSourceButton(
                        current = searchSource,
                        onSelect = { source ->
                            if (source != searchSource) {
                                searchSource = source
                                pendingSource = source
                            }
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query.text) }),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = if (pureBlack) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = if (pureBlack) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Text(
            text = stringResource(R.string.search_in_sources_line),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
        )

        if (selectedArtist == null) {
            ChipsRow(
                chips = listOf(
                    SourceSearchFilter.TRACKS to stringResource(R.string.filter_songs),
                    SourceSearchFilter.ARTISTS to stringResource(R.string.filter_artists),
                ),
                currentValue = filter,
                onValueUpdate = { viewModel.filter.value = it },
                modifier = Modifier.fillMaxWidth(),
            )
            // One source at a time digs much deeper than the mixed view, which has to leave room
            // for every source.
            ChipsRow(
                chips = listOf<Pair<AudioProviderId?, String>>(
                    null to stringResource(R.string.filter_all),
                ) + SourceSearch.PROVIDERS.map { it as AudioProviderId? to providerName(it) },
                currentValue = source,
                onValueUpdate = { viewModel.source.value = it },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.closeArtist() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = selectedArtist.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }

        val shownTracks = if (selectedArtist != null) artistTracks else results
        val loading = if (selectedArtist != null) artistLoading else isLoading

        LazyColumn(
            state = lazyListState,
            // Keeps the last track above the mini player instead of behind it.
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (selectedArtist == null && filter == SourceSearchFilter.ARTISTS) {
                items(
                    items = artists,
                    key = { "artist_${it.name.lowercase()}" },
                ) { artist ->
                    ListItem(
                        title = artist.name,
                        subtitle = pluralTracks(artist.trackCount),
                        thumbnailContent = {
                            ItemThumbnail(
                                thumbnailUrl = artist.thumbnailUrl,
                                isActive = false,
                                isPlaying = false,
                                shape = CircleShape,
                                modifier = Modifier.size(ListThumbnailSize),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.openArtist(artist.name) }
                            .animateItem(),
                    )
                }
            } else {
                itemsIndexed(
                    items = shownTracks,
                    key = { index, match -> "${SourceSearch.catalogIdOf(match)}_$index" },
                ) { index, match ->
                    val mediaId = remember(match) { SourceSearch.mediaIdOf(match) }
                    val isActive = mediaMetadata?.id == mediaId
                    ListItem(
                        title = match.title,
                        subtitle = joinByBullet(
                            match.artist.takeIf { it.isNotBlank() },
                            match.durationMs?.takeIf { it > 0 }?.let { makeTimeString(it) },
                            sourceName(match),
                        ),
                        isActive = isActive,
                        thumbnailContent = {
                            ItemThumbnail(
                                thumbnailUrl = match.thumbnailUrl,
                                isActive = isActive,
                                isPlaying = isPlaying,
                                shape = RoundedCornerShape(ThumbnailCornerRadius),
                                modifier = Modifier.size(ListThumbnailSize),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { play(shownTracks, index) },
                                onLongClick = {
                                    menuState.show {
                                        SourceTrackMenu(match = match, onDismiss = menuState::dismiss)
                                    }
                                },
                            )
                            .animateItem(),
                    )
                }
            }

            if (loading) {
                item(key = "loading") {
                    ShimmerHost {
                        repeat(4) { ListItemPlaceHolder() }
                    }
                }
            } else if (shownTracks.isEmpty() && (filter == SourceSearchFilter.TRACKS || artists.isEmpty())) {
                item(key = "empty") {
                    // When one source was picked and it can't answer, say why instead of "nothing found".
                    val status = source?.let { SourceSearch.statusOf(it) }
                    EmptyPlaceholder(
                        icon = R.drawable.search,
                        text = when (status) {
                            SourceSearch.Status.DISABLED ->
                                stringResource(R.string.search_source_disabled, providerName(source!!))
                            SourceSearch.Status.NEEDS_ACCOUNT ->
                                stringResource(R.string.search_source_needs_account, providerName(source!!))
                            else -> stringResource(R.string.search_sources_empty)
                        },
                    )
                }
            }
        }
    }
}

/** "VK", "SoundCloud", … as the user knows them. */
fun providerName(id: AudioProviderId): String = when (id) {
    AudioProviderId.VK -> "VK"
    AudioProviderId.SOUNDCLOUD -> "SoundCloud"
    AudioProviderId.BANDCAMP -> "Bandcamp"
    AudioProviderId.AUDIUS -> "Audius"
    AudioProviderId.YOUTUBE -> "YouTube"
    AudioProviderId.SOULSEEK -> "Soulseek"
    AudioProviderId.QOBUZ -> "Qobuz"
}

/** "12 tracks" under an artist's name. */
@Composable
private fun pluralTracks(count: Int): String =
    androidx.compose.ui.res.pluralStringResource(R.plurals.n_song, count, count)

/** Where this result came from. */
fun sourceName(match: ProviderMatch): String = providerName(match.provider)
