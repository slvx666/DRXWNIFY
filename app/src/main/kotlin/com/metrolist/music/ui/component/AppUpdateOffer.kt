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
    val oldApp = com.metrolist.music.utils.AppMigration.isOldApp(context)
    val backupModel: com.metrolist.music.viewmodels.BackupRestoreViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_update_offer_title, available.version)) },
        text = {
            Text(
                stringResource(R.string.app_update_offer_text, AppUpdater.currentVersion) +
                    // The new version is a new app (its own id): the data goes over through a file.
                    if (oldApp) "\n\n" + stringResource(R.string.migration_update_note) else "",
            )
        },
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
                    if (oldApp) {
                        // Everything saved first, so nothing is lost in the move.
                        val file = com.metrolist.music.utils.AppMigration.createBackupFile(context)
                        if (file != null) {
                            backupModel.backup(context, file)
                            android.widget.Toast.makeText(context, R.string.migration_backup_saved, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    AppUpdater.startDownloadAndInstall(context, available)
                }) { Text(stringResource(R.string.app_update_install_now)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.app_update_later)) }
            }
        },
    )
}

/**
 * The new app's first start: bring the data over from the old one (the file it saved on the way),
 * then ask for the music permission so the downloaded files play from the folder.
 */
@Composable
fun MigrationRestoreOffer() {
    val context = LocalContext.current
    // Restored downloads play from their files, which takes the music permission.
    val permission = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
    else android.Manifest.permission.READ_EXTERNAL_STORAGE
    val askPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        delay(3_000L)
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val prefs = context.getSharedPreferences("app_migration", android.content.Context.MODE_PRIVATE)
        if (!granted && com.metrolist.music.utils.DownloadExportState.exported.value.isNotEmpty() &&
            !prefs.getBoolean("askedMusic", false)
        ) {
            prefs.edit().putBoolean("askedMusic", true).apply()
            askPermission.launch(permission)
        }
    }
    var show by remember { mutableStateOf(com.metrolist.music.utils.AppMigration.shouldOfferRestore(context)) }
    if (!show) return
    val backupModel: com.metrolist.music.viewmodels.BackupRestoreViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            com.metrolist.music.utils.AppMigration.markOffered(context)
            backupModel.restore(context, uri)
        }
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.migration_restore_title)) },
        text = { Text(stringResource(R.string.migration_restore_text)) },
        confirmButton = {
            TextButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.migration_restore_pick)) }
        },
        dismissButton = {
            TextButton(onClick = {
                com.metrolist.music.utils.AppMigration.markOffered(context)
                show = false
            }) { Text(stringResource(R.string.migration_restore_skip)) }
        },
    )
}

private const val LAUNCH_DELAY_MS = 6_000L
