/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import com.metrolist.music.ui.theme.themeSectionTitle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.ui.theme.AppThemePreset
import com.metrolist.music.ui.theme.LocalAppThemePreset
import com.metrolist.music.ui.theme.SatanicThemeConfig

/**
 * Section titles without their "Play all" button and arrow (the home page: a title alone reads
 * cleaner there; tapping it still opens the section where it can).
 */
val LocalPlainSectionTitles = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun NavigationTitle(
    title: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    thumbnail: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onPlayAllClick: (() -> Unit)? = null,
    /** A quiet text link on the right ("Show all"). */
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val plain = LocalPlainSectionTitles.current
    val isSatanic = LocalAppThemePreset.current == AppThemePreset.SATANIC
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    val context = LocalContext.current
    val dividerPainter =
        if (isSatanic) {
            SatanicThemeConfig.loadBitmapAsset(context, SatanicThemeConfig.PATH_SECTION_DIVIDER)
        } else {
            null
        }

    Column(modifier = modifier.fillMaxWidth()) {
        if (isSatanic && dividerPainter != null) {
            Image(
                painter = dividerPainter,
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .height(8.dp),
                contentScale = ContentScale.FillWidth,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                    .clickable(enabled = onClick != null) {
                        onClick?.invoke()
                    }
                    .padding(horizontal = 12.dp, vertical = if (isSatanic) 8.dp else 12.dp),
        ) {
            thumbnail?.invoke()

            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f),
            ) {
                label?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = if (isSatanic) SatanicThemeConfig.SerifFont else null,
                        color = if (isSatanic) SatanicThemeConfig.getSecondaryText(isDark) else MaterialTheme.colorScheme.onSurfaceVariant,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Text(
                    text = title,
                    style = com.metrolist.music.ui.theme.themeSectionStyle(MaterialTheme.typography.titleLarge),
                    fontWeight = com.metrolist.music.ui.theme.themeSectionWeight(),
                    fontFamily = if (isSatanic) SatanicThemeConfig.TitleFont else null,
                    color = if (isSatanic) SatanicThemeConfig.getPrimaryText(isDark) else MaterialTheme.colorScheme.primary,
                    overflow = TextOverflow.Ellipsis,
                    // Spotify's long section names ("Friday new releases for you") need the room.
                    maxLines = if (plain) 2 else 1,
                    // An experimental theme draws around the words (crosses, drips, a pen); nothing moves.
                    modifier = Modifier.themeSectionTitle(title),
                )
            }

        onPlayAllClick?.takeUnless { plain }?.let { playAllClick ->
            OutlinedButton(
                onClick = playAllClick,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (isSatanic) SatanicThemeConfig.getBorder(isDark) else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isSatanic) SatanicThemeConfig.getPrimaryText(isDark) else MaterialTheme.colorScheme.primary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                modifier = Modifier
                    .height(24.dp)
            ) {
                Text(
                    text = stringResource(R.string.play_all),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = if (isSatanic) SatanicThemeConfig.SerifFont else null,
                )
            }
        }

        if (actionText != null && onAction != null) {
            Text(
                text = actionText,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = if (isSatanic) SatanicThemeConfig.SerifFont else null,
                color = (if (isSatanic) SatanicThemeConfig.getSecondaryText(isDark) else MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (onClick != null && !plain) {
            Icon(
                painter = painterResource(R.drawable.arrow_forward),
                contentDescription = null,
                tint = if (isSatanic) SatanicThemeConfig.getPrimaryText(isDark) else MaterialTheme.colorScheme.primary
            )
        }
    }
}
}
