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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

/** What part of the experimental results is on screen. */
enum class SourceSearchFilter { TRACKS, ARTISTS }

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
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val query = try {
        URLDecoder.decode(savedStateHandle.get<String>("query").orEmpty(), "UTF-8")
    } catch (e: IllegalArgumentException) {
        savedStateHandle.get<String>("query").orEmpty()
    }

    val results = MutableStateFlow<List<ProviderMatch>>(emptyList())
    val isLoading = MutableStateFlow(true)

    val filter = MutableStateFlow(SourceSearchFilter.TRACKS)

    /** null = every usable source at once; otherwise only that one, which then returns far more. */
    val source = MutableStateFlow<AudioProviderId?>(null)

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
            // Re-runs whenever the user narrows the search down to one source.
            source.collectLatest { only ->
                isLoading.value = true
                closeArtist()
                val found = SourceSearch.search(query, only)
                results.value = found
                isLoading.value = false
                // Remember where each result came from, so it still plays (and downloads) after a restart.
                SourceSearch.remember(found)
            }
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

    fun closeArtist() {
        artistJob?.cancel()
        artistLoading.value = false
        selectedArtist.value = null
        artistTracks.value = emptyList()
    }
}
