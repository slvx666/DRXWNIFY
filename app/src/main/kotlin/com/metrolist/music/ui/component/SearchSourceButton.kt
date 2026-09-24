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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch

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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    val (_, setAcknowledged) = rememberPreference(ExperimentalSearchAckKey, defaultValue = false)
    val (experimentalSource, setExperimentalSource) =
        rememberPreference(ExperimentalSearchSourceKey, defaultValue = "")
    var open by remember { androidx.compose.runtime.mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                painter = painterResource(searchSourceIcon(current)),
                contentDescription = stringResource(R.string.search_source_picker),
                tint = when {
                    // Soulseek, the last resort, keeps its own colour here too.
                    current == SearchSource.SOURCES && experimentalSource == AudioProviderId.SOULSEEK.name -> LastResortColor
                    current == SearchSource.SOURCES -> SearchExperimentalColor
                    else -> searchSourceColor(current) ?: tint ?: MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }

    if (open) {
        // Its own sheet, opened fully: the whole list fits at once instead of a half-open sheet
        // that has to be pulled up.
        val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        fun closeThen(action: () -> Unit) {
            scope.launch { sheetState.hide() }.invokeOnCompletion {
                open = false
                action()
            }
        }
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheetState,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 8.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            },
        ) {
            SearchSourceSheet(
                current = current,
                hasCatalog = hasCatalog,
                yandexConnected = catalogState.yandexConnected,
                experimentalAvailable = experimentalAvailable,
                experimentalSource = experimentalSource,
                onSelect = { source -> closeThen { onSelect(source) } },
                onSelectExperimental = { value ->
                    closeThen {
                        setExperimentalSource(value)
                        setAcknowledged(true)
                        if (current != SearchSource.SOURCES) onSelect(SearchSource.SOURCES)
                    }
                },
            )
        }
    }
}

/** Soulseek's colour: one warm red-orange that says "last resort" without shouting. */
val LastResortColor = Color(0xFFF0712C)

@Composable
private fun SearchSourceSheet(
    current: SearchSource,
    hasCatalog: Boolean,
    yandexConnected: Boolean,
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
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
        )

        // YouTube Music is always offered, account or not: it holds artists the catalog does not,
        // and hiding it was the one way to end up unable to search them at all.
        // Spotify is always there (its public catalog needs no account); Yandex Music only once
        // it is linked — it has what Spotify lacks, mostly Russian releases since 2022.
        val standardSources = listOfNotNull(
            SearchSource.LOCAL,
            SearchSource.ONLINE,
            SearchSource.YANDEX.takeIf { yandexConnected },
            SearchSource.YOUTUBE,
        )
        standardSources.forEach { source ->
            SourceRow(
                label = stringResource(searchSourceLabel(source, hasCatalog)),
                icon = searchSourceIcon(source),
                accent = searchSourceColor(source),
                selected = source == current,
                onClick = { onSelect(source) },
            )
        }

        if (!experimentalAvailable) return@Column

        // A plain section under a thin divider: no coloured box, only the small mark by the title
        // keeps the amber of the experimental mode.
        androidx.compose.material3.HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        run {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
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
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(4.dp))
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
                    // VK stands out on its own line: it is the source worth signing in for.
                    FeaturedVkChip(
                        selected = current == SearchSource.SOURCES && experimentalSource == AudioProviderId.VK.name,
                        status = SourceSearch.statusOf(AudioProviderId.VK),
                        onClick = { onSelectExperimental(AudioProviderId.VK.name) },
                    )
                    SourceSearch.PROVIDERS.filter { it != AudioProviderId.SOULSEEK && it != AudioProviderId.VK }.forEach { provider ->
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
                    // Last in the row, in its own warning colour: the source to reach for last.
                    LastResortSourceChip(
                        selected = current == SearchSource.SOURCES && experimentalSource == AudioProviderId.SOULSEEK.name,
                        status = SourceSearch.statusOf(AudioProviderId.SOULSEEK),
                        onClick = { onSelectExperimental(AudioProviderId.SOULSEEK.name) },
                    )
                }

            }
        }
    }
}

/** VK, the best source: the same chip as the others, on its own line with a crown and why. */
@Composable
private fun FeaturedVkChip(
    selected: Boolean,
    status: SourceSearch.Status,
    onClick: () -> Unit,
) {
    SourceChip(
        label = "VK",
        note = when (status) {
            SourceSearch.Status.READY -> stringResource(R.string.vk_best_source)
            SourceSearch.Status.DISABLED -> stringResource(R.string.source_status_off)
            SourceSearch.Status.NEEDS_ACCOUNT -> stringResource(R.string.vk_best_source_sign_in)
        },
        selected = selected,
        onClick = onClick,
        leadingIcon = R.drawable.crown,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Soulseek, the last resort (slow, never part of "All sources"): a plain chip, its name in orange. */
@Composable
private fun LastResortSourceChip(
    selected: Boolean,
    status: SourceSearch.Status,
    onClick: () -> Unit,
) {
    SourceChip(
        label = "Soulseek",
        note = when (status) {
            SourceSearch.Status.READY -> stringResource(R.string.soulseek_last_resort_short)
            SourceSearch.Status.DISABLED -> stringResource(R.string.source_status_off)
            SourceSearch.Status.NEEDS_ACCOUNT -> stringResource(R.string.soulseek_last_resort_unavailable)
        },
        selected = selected,
        onClick = onClick,
        labelColor = LastResortColor,
    )
}

@Composable
private fun SourceRow(
    label: String,
    icon: Int,
    accent: Color?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedColor = accent ?: MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = accent ?: if (selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) selectedColor else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.check),
                contentDescription = null,
                tint = selectedColor,
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
    labelColor: Color? = null,
    leadingIcon: Int? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .let {
                if (selected) it else it.border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    RoundedCornerShape(12.dp),
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (leadingIcon != null) {
            Icon(
                painter = painterResource(leadingIcon),
                contentDescription = null,
                tint = SearchExperimentalColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = if (leadingIcon != null) Modifier.weight(1f) else Modifier) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = labelColor
                    ?: if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
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
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
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

/** Each service in its own colour; the library keeps the theme's. */
fun searchSourceColor(source: SearchSource): Color? = when (source) {
    SearchSource.ONLINE -> Color(0xFF1ED760)
    SearchSource.YANDEX -> Color(0xFFFFCC00)
    SearchSource.YOUTUBE -> Color(0xFFFF2D2D)
    SearchSource.LOCAL, SearchSource.SOURCES -> null
}

fun searchSourceIcon(source: SearchSource): Int = when (source) {
    SearchSource.LOCAL -> R.drawable.library_music
    SearchSource.ONLINE -> R.drawable.spotify
    SearchSource.YANDEX -> R.drawable.yandex_music
    SearchSource.YOUTUBE -> R.drawable.youtube_music
    SearchSource.SOURCES -> R.drawable.search_experimental
}

fun searchSourceLabel(source: SearchSource, hasCatalog: Boolean): Int = when (source) {
    SearchSource.LOCAL -> R.string.search_source_library
    SearchSource.ONLINE -> R.string.search_source_spotify
    SearchSource.YANDEX -> R.string.search_source_yandex
    SearchSource.YOUTUBE -> R.string.search_source_youtube
    SearchSource.SOURCES -> R.string.search_source_experimental
}

/** The name of the service that answers a search, for the line under the search box. */
fun searchSourceName(source: SearchSource, hasCatalog: Boolean, catalogName: String): String = when (source) {
    SearchSource.LOCAL -> ""
    SearchSource.ONLINE -> "Spotify"
    SearchSource.YANDEX -> "Yandex Music"
    SearchSource.YOUTUBE -> "YouTube Music"
    SearchSource.SOURCES -> "VK • SoundCloud • Bandcamp • Audius"
}
