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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    /** In the user's VK music ("saved"); only other owners' albums/playlists can be saved. */
    val isSaved: StateFlow<Boolean> = VkMusic.savedKeys
        .map { keys -> playlist?.key in keys }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val canSave = MutableStateFlow(false)
    val saving = MutableStateFlow(false)

    init {
        load()
        viewModelScope.launch(Dispatchers.IO) {
            // Whether it's saved is only known once the user's own list was read.
            if (!VkMusic.libraryLoaded) VkMusic.myPlaylists()
            val me = VkMusic.myId()
            canSave.value = playlist != null && playlist.ownerId != me
        }
    }

    fun toggleSaved(onDone: (Result<Boolean>) -> Unit) {
        val target = playlist ?: return
        if (saving.value) return
        viewModelScope.launch(Dispatchers.IO) {
            saving.value = true
            val result = VkMusic.toggleSaved(target)
            saving.value = false
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(result) }
        }
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
        // An album saved or removed elsewhere (its page) shows up here without reopening the app.
        viewModelScope.launch {
            VkMusic.libraryVersion.drop(1).collect { refresh() }
        }
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
