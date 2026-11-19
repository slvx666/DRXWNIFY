/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.annotation.SuppressLint
import androidx.compose.foundation.BorderStroke
import com.metrolist.music.ui.theme.AppThemePreset
import com.metrolist.music.ui.theme.LocalAppThemePreset
import com.metrolist.music.ui.theme.SatanicThemeConfig
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.ui.screens.OptionStats

@Composable
fun <E> ChipsRow(
    chips: List<Pair<E, String>>,
    currentValue: E,
    onValueUpdate: (E) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    val isSatanic = LocalAppThemePreset.current == AppThemePreset.SATANIC
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
    ) {
        Spacer(Modifier.width(12.dp))

        chips.forEach { (value, label) ->
            val isSelected = currentValue == value
            FilterChip(
                label = {
                    Text(
                        text = label,
                        fontFamily = if (isSatanic) SatanicThemeConfig.SerifFont else null,
                    )
                },
                selected = isSelected,
                colors =
                    if (isSatanic) {
                        FilterChipDefaults.filterChipColors(
                            containerColor = SatanicThemeConfig.getSurface(isDark),
                            selectedContainerColor = SatanicThemeConfig.getElevated(isDark),
                            labelColor = SatanicThemeConfig.getSecondaryText(isDark),
                            selectedLabelColor = SatanicThemeConfig.getPrimaryText(isDark),
                        )
                    } else {
                        FilterChipDefaults.filterChipColors(
                            containerColor = containerColor,
                        )
                    },
                onClick = { onValueUpdate(value) },
                shape = RoundedCornerShape(16.dp),
                border =
                    if (isSatanic && !isSelected) {
                        BorderStroke(1.dp, SatanicThemeConfig.getBorder(isDark))
                    } else {
                        null
                    },
            )

            Spacer(Modifier.width(8.dp))
        }
    }
}

@SuppressLint("UnusedContentLambdaTargetStateParameter")
@Composable
fun <Int> ChoiceChipsRow(
    chips: List<Pair<Int, String>>,
    options: List<Pair<OptionStats, String>>,
    selectedOption: OptionStats,
    onSelectionChange: (OptionStats) -> Unit,
    currentValue: Int,
    onValueUpdate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    var expandIconDegree by remember { mutableFloatStateOf(0f) }
    val rotationAnimation by animateFloatAsState(
        targetValue = expandIconDegree,
        animationSpec = tween(durationMillis = 400),
        label = "",
    )

    val isSatanic = LocalAppThemePreset.current == AppThemePreset.SATANIC
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.4f

    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
    ) {
        var expanded by remember { mutableStateOf(false) }

        Column {
            AssistChip(
                onClick = {
                    expanded = !expanded
                    expandIconDegree -= 180
                },
                label = {
                    Text(
                        text =
                        when (selectedOption) {
                            OptionStats.WEEKS -> stringResource(id = R.string.weeks)
                            OptionStats.MONTHS -> stringResource(id = R.string.months)
                            OptionStats.YEARS -> stringResource(id = R.string.years)
                            OptionStats.CONTINUOUS -> stringResource(id = R.string.continuous)
                        },
                        fontFamily = if (isSatanic) SatanicThemeConfig.SerifFont else null,
                    )
                },
                trailingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.expand_more),
                        contentDescription = null,
                        modifier = Modifier.graphicsLayer(rotationZ = rotationAnimation),
                        tint = if (isSatanic) SatanicThemeConfig.getPrimaryText(isDark) else MaterialTheme.colorScheme.onSurface,
                    )
                },
                shape = RoundedCornerShape(16.dp),
                border = if (isSatanic) BorderStroke(1.dp, SatanicThemeConfig.getBorder(isDark)) else null,
                colors = if (isSatanic) {
                    AssistChipDefaults.assistChipColors(
                        containerColor = SatanicThemeConfig.getSurface(isDark),
                        labelColor = SatanicThemeConfig.getPrimaryText(isDark),
                    )
                } else {
                    AssistChipDefaults.assistChipColors(
                        containerColor = containerColor,
                        labelColor = MaterialTheme.colorScheme.onSurface
                    )
                }
            )

            AnimatedVisibility(
                visible = expanded,
                enter = expandIn() + fadeIn(),
                exit = shrinkOut() + fadeOut(),
            ) {
                DropdownMenu(
                    modifier = Modifier.padding(start = 12.dp),
                    expanded = expanded,
                    onDismissRequest = {
                        expanded = false
                        expandIconDegree -= 180
                    },
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(text = option.second, fontFamily = if (isSatanic) SatanicThemeConfig.SerifFont else null) },
                            onClick = {
                                onSelectionChange(option.first)
                                expandIconDegree -= 180
                                expanded = false
                            },
                        )
                    }
                }
            }
        }

        AnimatedContent(
            targetState = selectedOption,
            transitionSpec = { slideInHorizontally() + fadeIn() togetherWith slideOutHorizontally() + fadeOut() },
            label = "",
        ) {
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
            ) {
                chips.forEach { (value, label) ->
                    val isSelected = currentValue == value
                    Spacer(Modifier.width(8.dp))

                    FilterChip(
                        label = { Text(label, fontFamily = if (isSatanic) SatanicThemeConfig.SerifFont else null) },
                        selected = isSelected,
                        colors = if (isSatanic) {
                            FilterChipDefaults.filterChipColors(
                                containerColor = SatanicThemeConfig.getSurface(isDark),
                                selectedContainerColor = SatanicThemeConfig.getElevated(isDark),
                                labelColor = SatanicThemeConfig.getSecondaryText(isDark),
                                selectedLabelColor = SatanicThemeConfig.getPrimaryText(isDark),
                            )
                        } else {
                            FilterChipDefaults.filterChipColors(
                                containerColor = containerColor,
                            )
                        },
                        onClick = { onValueUpdate(value) },
                        shape = RoundedCornerShape(16.dp),
                        border = if (isSatanic && !isSelected) {
                            BorderStroke(1.dp, SatanicThemeConfig.getBorder(isDark))
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}
