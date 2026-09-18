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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

/** Results of the experimental "search the sources directly" mode for one query. */
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

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val found = SourceSearch.search(query)
            results.value = found
            isLoading.value = false
            // Remember where each result came from, so it still plays (and downloads) after a restart.
            SourceSearch.remember(found)
        }
    }
}
