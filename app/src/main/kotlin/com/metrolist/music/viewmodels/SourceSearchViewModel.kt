/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.ProviderMatch
import com.metrolist.music.resolver.SourceSearch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

/** What part of the experimental results is on screen. */
enum class SourceSearchFilter { TRACKS, ARTISTS, ALBUMS }

/** One performer found in the results, with how many of their tracks the sources returned. */
data class SourceArtist(
    val name: String,
    val trackCount: Int,
    val thumbnailUrl: String?,
)

/**
 * Results of the experimental "search the sources directly" mode for one query.
 *
 * The sources answer with tracks only, so the artist list is folded out of those tracks; opening an
 * artist runs a second search for their name, which is as close to an artist page as sources that
 * have no artist entities can get.
 */
@HiltViewModel
class SourceSearchViewModel
@Inject
constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val query = try {
        URLDecoder.decode(savedStateHandle.get<String>("query").orEmpty(), "UTF-8")
    } catch (e: IllegalArgumentException) {
        savedStateHandle.get<String>("query").orEmpty()
    }

    val results = MutableStateFlow<List<ProviderMatch>>(emptyList())
    val isLoading = MutableStateFlow(true)

    /** VK albums and playlists for the query — whole releases, which a track search can't show. */
    val vkPlaylists = MutableStateFlow<List<com.metrolist.music.resolver.providers.VkPlaylist>>(emptyList())
    val vkPlaylistsLoading = MutableStateFlow(false)

    val filter = MutableStateFlow(SourceSearchFilter.TRACKS)

    /**
     * null = every usable source at once; otherwise only that one, which then returns far more.
     * Chosen in the search box's source picker, so it survives leaving the screen.
     */
    val source = MutableStateFlow<AudioProviderId?>(null)

    /**
     * False until the picked source has been read. Before that [source] is only a placeholder null
     * ("all sources"), which must neither start a search of everything nor show VK's albums tab.
     */
    val sourceKnown = MutableStateFlow(false)

    private val pickedSource = context.dataStore.data
        .map { prefs ->
            prefs[com.metrolist.music.constants.ExperimentalSearchSourceKey]
                ?.let { name -> AudioProviderId.entries.firstOrNull { it.name == name } }
        }
        .distinctUntilChanged()

    /** Non-null while one artist's tracks are shown instead of the result list. */
    val selectedArtist = MutableStateFlow<String?>(null)
    val artistTracks = MutableStateFlow<List<ProviderMatch>>(emptyList())
    val artistLoading = MutableStateFlow(false)

    /** Performers behind the results, most tracks first. */
    val artists: StateFlow<List<SourceArtist>> = results
        .map { matches ->
            matches.asSequence()
                .filter { it.artist.isNotBlank() }
                .groupBy { it.artist.trim().lowercase() }
                .map { (_, group) ->
                    SourceArtist(
                        name = group.first().artist.trim(),
                        trackCount = group.size,
                        thumbnailUrl = group.firstNotNullOfOrNull { it.thumbnailUrl },
                    )
                }
                .sortedByDescending { it.trackCount }
                .toList()
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private var artistJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Re-runs whenever the user narrows the search down to one source. The first run must
            // leave an artist opened straight from a track's menu alone. A new pick takes over at
            // once: the old search is cancelled without waiting for it to wind down (collectLatest
            // waits, and a source stuck in the network held the switch up until it gave up).
            var firstRun = true
            var searchJob: Job? = null
            pickedSource.collect { only ->
                searchJob?.cancel()
                if (!firstRun) closeArtist()
                firstRun = false
                source.value = only
                sourceKnown.value = true
                isLoading.value = true
                results.value = emptyList()
                vkPlaylists.value = emptyList()
                vkPlaylistsLoading.value = false
                searchJob = launch { runSearch(only) }
            }
        }
    }

    private suspend fun runSearch(only: AudioProviderId?) = kotlinx.coroutines.coroutineScope {
        if ((only == null || only == AudioProviderId.VK) && com.metrolist.music.resolver.VkMusic.isReady) {
            vkPlaylistsLoading.value = true
            launch {
                vkPlaylists.value = com.metrolist.music.resolver.VkMusic.searchPlaylists(query).getOrDefault(emptyList())
                vkPlaylistsLoading.value = false
            }
        }
        val found = SourceSearch.search(query, only)
        results.value = found
        isLoading.value = false
        // Remember where each result came from, so it still plays (and downloads) after a restart.
        SourceSearch.remember(found)
    }

    init {
        // Opened from a track's menu: show that performer straight away.
        SourceSearchViewModel.pendingArtist?.let { artist ->
            pendingArtist = null
            filter.value = SourceSearchFilter.ARTISTS
            openArtist(artist)
        }
    }

    fun openArtist(name: String) {
        selectedArtist.value = name
        artistTracks.value = results.value.filter { it.artist.equals(name, ignoreCase = true) }
        artistJob?.cancel()
        artistJob = viewModelScope.launch(Dispatchers.IO) {
            artistLoading.value = true
            // A fresh search by the artist's name finds more than the tracks that happened to match
            // the original query.
            val found = SourceSearch.search(name, source.value).filter {
                it.artist.equals(name, ignoreCase = true) ||
                    it.artist.contains(name, ignoreCase = true)
            }
            val merged = (artistTracks.value + found).distinctBy { SourceSearch.catalogIdOf(it) }
            artistTracks.value = merged
            artistLoading.value = false
            SourceSearch.remember(merged)
        }
    }

    companion object {
        /** Set right before navigating from "find this performer" in a track's menu. */
        @Volatile
        var pendingArtist: String? = null
    }

    fun closeArtist() {
        artistJob?.cancel()
        artistLoading.value = false
        selectedArtist.value = null
        artistTracks.value = emptyList()
    }
}
