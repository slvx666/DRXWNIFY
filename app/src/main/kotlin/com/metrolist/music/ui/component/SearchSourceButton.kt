/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.metrolist.music.R
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.constants.ExperimentalSearchAckKey
import com.metrolist.music.constants.ExperimentalSearchEnabledKey
import com.metrolist.music.constants.SearchSource
import com.metrolist.music.utils.rememberPreference

/** Amber, not red: the experimental mode is a caveat, not an error. */
val SearchExperimentalColor = Color(0xFFE0A030)

/**
 * The little button next to the search box's clear button: it says where the search is going and
 * lets the source be changed at any moment, from the search input as well as from the results.
 * In the experimental mode it turns into an amber exclamation mark.
 */
@Composable
fun SearchSourceButton(
    current: SearchSource,
    onSelect: (SearchSource) -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val catalogState by Catalog.state.collectAsState()
    val hasCatalog = catalogState.isActive
    val (experimentalAvailable, _) = rememberPreference(ExperimentalSearchEnabledKey, defaultValue = true)
    val (acknowledged, setAcknowledged) = rememberPreference(ExperimentalSearchAckKey, defaultValue = false)

    var expanded by remember { mutableStateOf(false) }
    var showWarning by remember { mutableStateOf(false) }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = { showWarning = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.search_experimental),
                    contentDescription = null,
                    tint = SearchExperimentalColor,
                )
            },
            title = { Text(text = stringResource(R.string.search_sources_title)) },
            text = { Text(text = stringResource(R.string.search_sources_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        setAcknowledged(true)
                        showWarning = false
                        onSelect(SearchSource.SOURCES)
                    },
                ) {
                    Text(text = stringResource(R.string.got_it))
                }
            },
            dismissButton = {
                TextButton(onClick = { showWarning = false }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(searchSourceIcon(current)),
                contentDescription = stringResource(R.string.search_source_picker),
                tint = when {
                    current == SearchSource.SOURCES -> SearchExperimentalColor
                    tint != null -> tint
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val sources = buildList {
                add(SearchSource.LOCAL)
                add(SearchSource.ONLINE)
                if (hasCatalog) add(SearchSource.YOUTUBE)
                if (experimentalAvailable) add(SearchSource.SOURCES)
            }
            sources.forEach { source ->
                val isExperimental = source == SearchSource.SOURCES
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(searchSourceLabel(source, hasCatalog)),
                            color = if (isExperimental) SearchExperimentalColor else LocalContentColor.current,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(searchSourceIcon(source)),
                            contentDescription = null,
                            tint = if (isExperimental) SearchExperimentalColor else LocalContentColor.current,
                        )
                    },
                    trailingIcon = {
                        if (source == current) {
                            Icon(painter = painterResource(R.drawable.check), contentDescription = null)
                        }
                    },
                    onClick = {
                        expanded = false
                        if (isExperimental && !acknowledged) showWarning = true else onSelect(source)
                    },
                )
            }
        }
    }
}

fun searchSourceIcon(source: SearchSource): Int = when (source) {
    SearchSource.LOCAL -> R.drawable.library_music
    SearchSource.ONLINE -> R.drawable.language
    SearchSource.YOUTUBE -> R.drawable.music_note
    SearchSource.SOURCES -> R.drawable.search_experimental
}

fun searchSourceLabel(source: SearchSource, hasCatalog: Boolean): Int = when (source) {
    SearchSource.LOCAL -> R.string.search_source_library
    SearchSource.ONLINE -> if (hasCatalog) R.string.search_source_catalog else R.string.search_source_youtube
    SearchSource.YOUTUBE -> R.string.search_source_youtube
    SearchSource.SOURCES -> R.string.search_source_experimental
}

/** The name of the service that answers a search, for the line under the search box. */
fun searchSourceName(source: SearchSource, hasCatalog: Boolean, catalogName: String): String = when (source) {
    SearchSource.LOCAL -> ""
    SearchSource.ONLINE -> if (hasCatalog) catalogName else "YouTube Music"
    SearchSource.YOUTUBE -> "YouTube Music"
    SearchSource.SOURCES -> "VK • SoundCloud • Bandcamp • Audius"
}
