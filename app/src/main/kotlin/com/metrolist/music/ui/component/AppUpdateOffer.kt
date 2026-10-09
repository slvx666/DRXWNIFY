/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.metrolist.music.R
import com.metrolist.music.utils.AppUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one update check of the app: shortly after launch it asks GitHub for the latest release (the
 * settings row shows the same result), and a new version is offered once — open its page, or
 * later.
 */
@Composable
fun AppUpdateOffer() {
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsState()
    var offered by remember { mutableStateOf<AppUpdater.State.Available?>(null) }
    LaunchedEffect(Unit) {
        delay(LAUNCH_DELAY_MS)
        AppUpdater.check()
    }
    LaunchedEffect(state) {
        val available = state as? AppUpdater.State.Available ?: return@LaunchedEffect
        if (AppUpdater.shouldOffer(context, available.version)) offered = available
    }
    offered?.let { available ->
        AppUpdateDialog(available, onDismiss = { offered = null })
    }
}

/** "Version X is out": open its page (site / GitHub) or install it right away. */
@Composable
fun AppUpdateDialog(available: AppUpdater.State.Available, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_update_offer_title, available.version)) },
        text = { Text(stringResource(R.string.app_update_offer_text, AppUpdater.currentVersion)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                AppUpdater.openUpdatePage(context, available)
            }) { Text(stringResource(R.string.app_update_open_page)) }
        },
        dismissButton = {
            if (available.apkUrl != null) {
                TextButton(onClick = {
                    onDismiss()
                    AppUpdater.startDownloadAndInstall(context, available)
                }) { Text(stringResource(R.string.app_update_install_now)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.app_update_later)) }
            }
        },
    )
}

private const val LAUNCH_DELAY_MS = 6_000L
