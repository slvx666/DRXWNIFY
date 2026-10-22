/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.metrolist.music.ui.theme.AppThemePreset
import com.metrolist.music.ui.theme.LocalAppThemePreset

/**
 * A Material 3 Expressive style settings group component
 * @param title The title of the settings group
 * @param items List of settings items to display
 */
@Composable
fun Material3SettingsGroup(
    title: String? = null,
    items: List<Material3SettingsItem>,
    useLowContrast: Boolean = false
) {
    if (LocalAppThemePreset.current == AppThemePreset.LINUX) {
        TerminalSettingsGroup(title, items)
        return
    }
    // Each theme its own touch: Gruvbox a little sharper, Catppuccin softer, Synthwave neon-edged.
    val preset = LocalAppThemePreset.current
    val outer = when (preset) {
        AppThemePreset.GRUVBOX -> 10.dp
        AppThemePreset.CATPPUCCIN -> 30.dp
        else -> 24.dp
    }
    val inner = when (preset) {
        AppThemePreset.GRUVBOX -> 3.dp
        AppThemePreset.CATPPUCCIN -> 10.dp
        else -> 6.dp
    }
    val neonEdge = preset == AppThemePreset.SYNTHWAVE
    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        // Section title
        title?.let {
            Text(
                text = if (preset == AppThemePreset.GRUVBOX) "# $it" else it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp, top = 8.dp)
            )
        }

        // Settings items
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEachIndexed { index, item ->
                val shape = when {
                    items.size == 1 -> RoundedCornerShape(outer)
                    index == 0 -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
                    index == items.size - 1 -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
                    else -> RoundedCornerShape(inner)
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                    shape = shape,
                    colors = CardDefaults.cardColors(
                        containerColor = if (!useLowContrast) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        }
                    ),
                    border = if (neonEdge) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)) else null,
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Material3SettingsItemRow(item = item)
                }
            }
        }
    }
}

/**
 * The Linux theme's settings group: a window of a tiling WM — a border, the title set into the top
 * edge ("┌─[ title ]"), items as plain rows split by lines, square icon cells.
 */
@Composable
private fun TerminalSettingsGroup(
    title: String?,
    items: List<Material3SettingsItem>,
) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(top = if (title != null) 10.dp else 0.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, colors.outline)
                .padding(top = if (title != null) 6.dp else 0.dp)
        ) {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(colors.outlineVariant))
                }
                Material3SettingsItemRow(item = item, terminal = true)
            }
        }
        title?.let {
            Text(
                text = "┌─[ ${it.lowercase()} ]",
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
                modifier = Modifier
                    .offset(x = 8.dp, y = (-9).dp)
                    .background(colors.surface)
                    .padding(horizontal = 4.dp)
            )
        }
    }
}

/**
 * Individual settings item row with Material 3 styling
 */
@Composable
private fun Material3SettingsItemRow(
    item: Material3SettingsItem,
    terminal: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = item.enabled && item.onClick != null,
                onClick = { item.onClick?.invoke() }
            )
            .padding(horizontal = if (terminal) 14.dp else 20.dp, vertical = if (terminal) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Custom leading content or Icon with background
        if (item.leadingContent != null) {
            item.leadingContent.invoke()
            Spacer(modifier = Modifier.width(16.dp))
        } else if (item.icon != null) {
            Box(
                modifier = Modifier
                    .size(if (terminal) 32.dp else 40.dp)
                    .clip(RoundedCornerShape(if (terminal) 0.dp else 12.dp))
                    .background(
                        MaterialTheme.colorScheme.primary.copy(
                            alpha = if (terminal) 0f else if (item.isHighlighted) 0.15f else 0.1f
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (item.showBadge) {
                    BadgedBox(
                        badge = {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        }
                    ) {
                        Icon(
                            painter = item.icon,
                            contentDescription = null,
                            tint = if (!item.enabled)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else if (item.isHighlighted)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    Icon(
                        painter = item.icon,
                        contentDescription = null,
                        tint = if (!item.enabled)
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        else if (item.isHighlighted)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))
        }

        // Title and description
        Column(
            modifier = Modifier.weight(1f)
        ) {
            // Title content
            ProvideTextStyle(
                MaterialTheme.typography.titleMedium.copy(
                    color = if (!item.enabled) 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            ) {
                item.title()
            }

            // Description if provided
            item.description?.let { desc ->
                Spacer(modifier = Modifier.height(2.dp))
                ProvideTextStyle(
                    MaterialTheme.typography.bodyMedium.copy(
                        color = if (!item.enabled)
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    desc()
                }
            }
        }

        // Trailing content
        item.trailingContent?.let { trailing ->
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

/**
 * Data class for Material 3 settings item
 */
data class Material3SettingsItem(
    val icon: Painter? = null,
    val leadingContent: (@Composable () -> Unit)? = null,
    val title: @Composable () -> Unit,
    val description: (@Composable () -> Unit)? = null,
    val trailingContent: (@Composable () -> Unit)? = null,
    val showBadge: Boolean = false,
    val isHighlighted: Boolean = false,
    val enabled: Boolean = true,
    val onClick: (() -> Unit)? = null
)
