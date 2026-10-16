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

/** Shares a Drxwnify link to an album, playlist or artist (see [AppLinks]). */
@Composable
fun ShareLinkButton(kind: AppLinks.Kind, id: String?, title: String?, by: String?) {
    if (id.isNullOrBlank()) return
    val context = LocalContext.current
    androidx.compose.material3.IconButton(
        onClick = {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, AppLinks.shareText(kind, id, title, by))
            context.startActivity(Intent.createChooser(send, null))
        },
    ) {
        Icon(painterResource(R.drawable.share), contentDescription = stringResource(R.string.share))
    }
}
