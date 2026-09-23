/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.resolver.ProviderMatch
import com.metrolist.music.resolver.SourceSearch
import com.metrolist.music.resolver.VkMusic
import com.metrolist.music.resolver.providers.VkPlaylist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One VK album/playlist and its tracks. */
@HiltViewModel
class VkPlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val playlist: VkPlaylist? = savedStateHandle.get<String>("key")?.let(VkMusic::playlist)

    val tracks = MutableStateFlow<List<ProviderMatch>>(emptyList())
    val isLoading = MutableStateFlow(true)
    val failed = MutableStateFlow(false)

    init {
        load()
    }

    fun load() {
        val target = playlist ?: run {
            isLoading.value = false
            failed.value = true
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            failed.value = false
            VkMusic.tracks(target)
                .onSuccess { found ->
                    tracks.value = found
                    // Pinned to VK right away, so they play (and download) after a restart as well.
                    SourceSearch.remember(found)
                }
                .onFailure { failed.value = true }
            isLoading.value = false
        }
    }
}

/** What the library's VK section shows. */
enum class VkLibraryTab { TRACKS, ALBUMS, PLAYLISTS }

/** The signed-in account's own VK music: tracks, added albums, playlists. */
@HiltViewModel
class VkLibraryViewModel @Inject constructor() : ViewModel() {
    val tab = MutableStateFlow(VkLibraryTab.ALBUMS)

    /** Albums and playlists come in one VK list; the tabs split it. */
    val playlists = MutableStateFlow<List<VkPlaylist>>(emptyList())
    val tracks = MutableStateFlow<List<ProviderMatch>>(emptyList())
    val isLoading = MutableStateFlow(true)
    val failed = MutableStateFlow(false)

    private var tracksLoaded = false

    init {
        refresh()
    }

    fun selectTab(value: VkLibraryTab) {
        tab.value = value
        if (value == VkLibraryTab.TRACKS && !tracksLoaded) loadTracks()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            failed.value = false
            VkMusic.myPlaylists()
                .onSuccess { playlists.value = it }
                .onFailure { failed.value = true }
            isLoading.value = false
        }
        if (tab.value == VkLibraryTab.TRACKS) loadTracks()
    }

    private fun loadTracks() {
        tracksLoaded = true
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            failed.value = false
            VkMusic.myTracks()
                .onSuccess { found ->
                    tracks.value = found
                    SourceSearch.remember(found)
                }
                .onFailure {
                    failed.value = true
                    tracksLoaded = false
                }
            isLoading.value = false
        }
    }
}
