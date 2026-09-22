/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.catalog.Catalog
import com.metrolist.music.constants.ExperimentalSearchAckKey
import com.metrolist.music.constants.ExperimentalSearchEnabledKey
import com.metrolist.music.constants.ExperimentalSearchSourceKey
import com.metrolist.music.constants.SearchSource
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.SourceSearch
import com.metrolist.music.utils.rememberPreference

/** Amber, not red: the experimental mode is a caveat, not an error. */
val SearchExperimentalColor = Color(0xFFE0A030)

/**
 * The little button next to the search box's clear button: it says where the search is going and
 * lets the source be changed at any moment, from the search input as well as from the results.
 * In the experimental mode it turns into an amber exclamation mark.
 *
 * Tapping it opens one sheet with everything in it: the normal places to search as plain rows, and
 * below them a single amber block that holds the experimental sources together — its own heading,
 * one line saying what the mode does, and the sources as chips that also show when one is switched
 * off or still needs an account (the usual reason a source "finds nothing").
 */
@Composable
fun SearchSourceButton(
    current: SearchSource,
    onSelect: (SearchSource) -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val menuState = LocalMenuState.current
    val catalogState by Catalog.state.collectAsState()
    val hasCatalog = catalogState.isActive
    val (experimentalAvailable, _) = rememberPreference(ExperimentalSearchEnabledKey, defaultValue = true)
    val (_, setAcknowledged) = rememberPreference(ExperimentalSearchAckKey, defaultValue = false)
    val (experimentalSource, setExperimentalSource) =
        rememberPreference(ExperimentalSearchSourceKey, defaultValue = "")

    Box(modifier = modifier) {
        IconButton(
            onClick = {
                menuState.show {
                    SearchSourceSheet(
                        current = current,
                        hasCatalog = hasCatalog,
                        experimentalAvailable = experimentalAvailable,
                        experimentalSource = experimentalSource,
                        onSelect = { source ->
                            menuState.dismiss()
                            onSelect(source)
                        },
                        onSelectExperimental = { value ->
                            menuState.dismiss()
                            setExperimentalSource(value)
                            setAcknowledged(true)
                            if (current != SearchSource.SOURCES) onSelect(SearchSource.SOURCES)
                        },
                    )
                }
            },
        ) {
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
    }
}

@Composable
private fun SearchSourceSheet(
    current: SearchSource,
    hasCatalog: Boolean,
    experimentalAvailable: Boolean,
    experimentalSource: String,
    onSelect: (SearchSource) -> Unit,
    onSelectExperimental: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(
            text = stringResource(R.string.search_source_picker),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        )

        // YouTube Music is always offered, account or not: it holds artists the catalog does not,
        // and hiding it was the one way to end up unable to search them at all.
        val standardSources = listOf(SearchSource.LOCAL, SearchSource.ONLINE, SearchSource.YOUTUBE)
        standardSources.forEach { source ->
            SourceRow(
                label = stringResource(searchSourceLabel(source, hasCatalog)),
                icon = searchSourceIcon(source),
                selected = source == current,
                onClick = { onSelect(source) },
            )
        }

        if (!experimentalAvailable) return@Column

        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = SearchExperimentalColor.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, SearchExperimentalColor.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.search_experimental),
                        contentDescription = null,
                        tint = SearchExperimentalColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.search_sources_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = SearchExperimentalColor,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.search_sources_short),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SourceChip(
                        label = stringResource(R.string.search_all_sources),
                        note = null,
                        selected = current == SearchSource.SOURCES && experimentalSource.isBlank(),
                        onClick = { onSelectExperimental("") },
                    )
                    SourceSearch.PROVIDERS.forEach { provider ->
                        SourceChip(
                            label = providerLabel(provider),
                            note = when (SourceSearch.statusOf(provider)) {
                                SourceSearch.Status.READY -> null
                                SourceSearch.Status.DISABLED -> stringResource(R.string.source_status_off)
                                SourceSearch.Status.NEEDS_ACCOUNT -> stringResource(R.string.source_status_needs_account)
                            },
                            selected = current == SearchSource.SOURCES && experimentalSource == provider.name,
                            onClick = { onSelectExperimental(provider.name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    label: String,
    icon: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** One experimental source. [note] is why it cannot answer right now ("off", "sign-in needed"). */
@Composable
private fun SourceChip(
    label: String,
    note: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) SearchExperimentalColor.copy(alpha = 0.22f) else Color.Transparent,
            )
            .let {
                if (selected) it else it.border(
                    BorderStroke(1.dp, SearchExperimentalColor.copy(alpha = 0.35f)),
                    RoundedCornerShape(12.dp),
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) SearchExperimentalColor else MaterialTheme.colorScheme.onSurface,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.check),
                contentDescription = null,
                tint = SearchExperimentalColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** "VK", "SoundCloud", … as the user knows them. */
fun providerLabel(id: AudioProviderId): String = when (id) {
    AudioProviderId.VK -> "VK"
    AudioProviderId.SOUNDCLOUD -> "SoundCloud"
    AudioProviderId.BANDCAMP -> "Bandcamp"
    AudioProviderId.AUDIUS -> "Audius"
    AudioProviderId.YOUTUBE -> "YouTube"
    AudioProviderId.SOULSEEK -> "Soulseek"
    AudioProviderId.QOBUZ -> "Qobuz"
}

fun searchSourceIcon(source: SearchSource): Int = when (source) {
    SearchSource.LOCAL -> R.drawable.library_music
    SearchSource.ONLINE -> R.drawable.language
    SearchSource.YOUTUBE -> R.drawable.music_note
    SearchSource.SOURCES -> R.drawable.search_experimental
}

fun searchSourceLabel(source: SearchSource, hasCatalog: Boolean): Int = when (source) {
    SearchSource.LOCAL -> R.string.search_source_library
    SearchSource.ONLINE -> if (hasCatalog) R.string.search_source_catalog else R.string.search_source_spotify
    SearchSource.YOUTUBE -> R.string.search_source_youtube
    SearchSource.SOURCES -> R.string.search_source_experimental
}

/** The name of the service that answers a search, for the line under the search box. */
fun searchSourceName(source: SearchSource, hasCatalog: Boolean, catalogName: String): String = when (source) {
    SearchSource.LOCAL -> ""
    SearchSource.ONLINE -> if (hasCatalog) catalogName else "Spotify"
    SearchSource.YOUTUBE -> "YouTube Music"
    SearchSource.SOURCES -> "VK • SoundCloud • Bandcamp • Audius"
}
