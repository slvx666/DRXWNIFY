package com.metrolist.music.ui.screens.settings

import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.constants.AppIconKey
import com.metrolist.music.constants.AppThemePresetKey
import com.metrolist.music.constants.DarkModeKey
import com.metrolist.music.constants.EnableDynamicIconKey
import com.metrolist.music.constants.ThemeAnimationsKey
import com.metrolist.music.ui.theme.AppThemePreset
import com.metrolist.music.ui.theme.MetrolistTheme
import com.metrolist.music.ui.theme.ThemeTitle
import com.metrolist.music.ui.theme.ThemeTopBarBackground
import com.metrolist.music.utils.AppIcon
import com.metrolist.music.utils.IconUtils
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference

/** Whole-app theme cards, the decoration switch and the app-icon picker (top of Settings → Theme). */
@Composable
fun AppThemePicker() {
    val context = LocalContext.current
    val (preset, onPresetChange) = rememberEnumPreference(AppThemePresetKey, AppThemePreset.STANDARD)
    val (animations, onAnimationsChange) = rememberPreference(ThemeAnimationsKey, defaultValue = true)
    val (appIcon, onAppIconChange) = rememberEnumPreference(AppIconKey, AppIcon.CLASSIC)
    val dynamicIcon by rememberPreference(EnableDynamicIconKey, defaultValue = true)
    val darkMode by rememberEnumPreference(DarkModeKey, DarkMode.AUTO)
    val dark = when (darkMode) {
        DarkMode.AUTO -> isSystemInDarkTheme()
        DarkMode.ON -> true
        DarkMode.OFF -> false
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.app_theme_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // A grid, not a sideways row: in a row the themes past the screen edge went unnoticed.
        AppThemePreset.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { item ->
                    ThemePresetCard(
                        preset = item,
                        dark = dark,
                        selected = item == preset,
                        onClick = { if (item != preset) onPresetChange(item) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text(
            text = stringResource(preset.descriptionRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (preset != AppThemePreset.STANDARD) {
            ThemeSwitchRow(
                title = stringResource(R.string.theme_animations),
                description = stringResource(R.string.theme_animations_desc),
                checked = animations,
                onCheckedChange = onAnimationsChange,
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.app_icon_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        AppIcon.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { icon ->
                    AppIconCell(
                        icon = icon,
                        selected = icon == appIcon,
                        onClick = {
                            if (icon != appIcon) {
                                onAppIconChange(icon)
                                runCatching { IconUtils.setAppIcon(context, icon, dynamicIcon) }
                                    .onSuccess {
                                        Toast.makeText(context, R.string.theme_launcher_icon_applied, Toast.LENGTH_LONG).show()
                                    }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text(
            text = stringResource(R.string.theme_launcher_icon_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppIconCell(
    icon: AppIcon,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(22.dp))
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(22.dp),
                )
                .clickable(onClick = onClick),
        ) {
            // The adaptive foreground keeps the launcher's safe-zone margin: zoom in like a launcher mask.
            Image(
                painter = painterResource(icon.previewRes),
                contentDescription = stringResource(icon.nameRes),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.45f
                        scaleY = 1.45f
                    },
            )
        }
        Text(
            text = stringResource(icon.nameRes),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ThemePresetCard(
    preset: AppThemePreset,
    dark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border by animateDpAsState(if (selected) 3.dp else 1.dp, label = "presetBorder")
    val accent = MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(136.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(
                    border,
                    if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(18.dp),
                )
                .clickable(onClick = onClick),
        ) {
            // The preview is drawn in the theme itself (the standard one with the default seed).
            MetrolistTheme(darkTheme = dark, preset = preset, animations = false) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    ThemeTopBarBackground(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp),
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 8.dp),
                        ) { MiniTitle() }
                    }
                    Column(
                        Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val corner = MaterialTheme.shapes.small
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .background(MaterialTheme.colorScheme.primary, corner),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .background(MaterialTheme.colorScheme.secondaryContainer, corner),
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .background(MaterialTheme.colorScheme.tertiaryContainer, corner),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(6.dp)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), CircleShape),
                            )
                        }
                    }
                }
            }
            if (selected) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .background(accent, CircleShape),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Text(
            text = stringResource(preset.nameRes),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** The theme's own title, shrunk for the preview card. */
@Composable
private fun MiniTitle() {
    Box(
        Modifier.graphicsLayer {
            scaleX = 0.7f
            scaleY = 0.7f
            transformOrigin = TransformOrigin(0f, 0.5f)
        },
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            ThemeTitle(text = "Aa", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ThemeSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        com.metrolist.music.ui.theme.ThemedSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
