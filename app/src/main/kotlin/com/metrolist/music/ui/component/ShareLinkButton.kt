/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.content.Intent
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.metrolist.music.R
import com.metrolist.music.utils.AppLinks

/**
 * Shares an album, playlist or artist: as its Drxwnify link (see [AppLinks]), to a chat, or as the
 * downloaded files when [files] finds some.
 */
@Composable
fun ShareLinkButton(
    kind: AppLinks.Kind,
    id: String?,
    title: String?,
    by: String?,
    icon: Int = R.drawable.share,
    files: (suspend () -> List<String>)? = null,
) {
    if (id.isNullOrBlank()) return
    androidx.compose.material3.IconButton(
        onClick = {
            ShareSheet.open(
                title = listOfNotNull(by?.takeIf { it.isNotBlank() }, title).joinToString(" — "),
                music = listOf(com.metrolist.music.friends.ChatMusic(kind, id, title.orEmpty(), by.orEmpty())),
                linkText = AppLinks.shareText(kind, id, title, by),
                files = files,
            )
        },
    ) {
        Icon(painterResource(icon), contentDescription = stringResource(R.string.share))
    }
}
