/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.datastore.preferences.core.edit
import com.metrolist.music.utils.AppUpdater
import com.metrolist.music.utils.dataStore
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
    var hideProgress by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LAUNCH_DELAY_MS)
        AppUpdater.check()
    }
    LaunchedEffect(state) {
        val available = state as? AppUpdater.State.Available ?: return@LaunchedEffect
        if (offered == null && AppUpdater.shouldOffer(context, available.version)) offered = available
    }
    // Back from the system screen that allows installing: the update carries on by itself.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) AppUpdater.resumeAfterPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    offered?.let { available ->
        AppUpdateDialog(available, onDismiss = { offered = null })
    }
    // The download, with its progress: 50+ MB used to go in silence until the installer opened.
    val downloading = state as? AppUpdater.State.Downloading
    if (downloading != null && !hideProgress) {
        AlertDialog(
            onDismissRequest = { hideProgress = true },
            title = { Text(stringResource(R.string.app_update_offer_title, downloading.version)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(stringResource(R.string.app_update_downloading, (downloading.progress * 100).toInt()))
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { downloading.progress },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { hideProgress = true }) { Text(stringResource(R.string.app_update_hide)) } },
        )
    }
    LaunchedEffect(downloading == null) { if (downloading == null) hideProgress = false }
}

/** "Version X is out": what changed, and update right here (download + installer) or later. */
@Composable
fun AppUpdateDialog(available: AppUpdater.State.Available, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val oldApp = com.metrolist.music.utils.AppMigration.isOldApp(context)
    val backupModel: com.metrolist.music.viewmodels.BackupRestoreViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_update_offer_title, available.version)) },
        text = {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.app_update_you_have, AppUpdater.currentVersion),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                available.notes?.let { notes ->
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(10.dp))
                    Text(
                        notes.lines().joinToString("\n") { line ->
                            // The notes are Markdown: headings shown as plain lines.
                            line.trim().removePrefix("###").removePrefix("##").removePrefix("#").trim()
                        },
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    )
                }
                // The new version is a new app (its own id): the data goes over through a file.
                if (oldApp) {
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(10.dp))
                    Text(stringResource(R.string.migration_update_note))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                if (available.apkUrl == null) {
                    AppUpdater.openUpdatePage(context, available)
                    return@TextButton
                }
                if (oldApp) {
                    // Everything saved first, so nothing is lost in the move.
                    val file = com.metrolist.music.utils.AppMigration.createBackupFile(context)
                    if (file != null) {
                        backupModel.backup(context, file)
                        android.widget.Toast.makeText(context, R.string.migration_backup_saved, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                AppUpdater.startDownloadAndInstall(context, available)
            }) { Text(stringResource(R.string.app_update_now)) }
        },
        dismissButton = {
            TextButton(onClick = {
                AppUpdater.snooze(context, available.version)
                onDismiss()
            }) { Text(stringResource(R.string.app_update_later)) }
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

/**
 * Shown when a download can't be written to the chosen folder (a folder brought over from the old
 * app id: Android granted it to that app only). One tap opens the system picker right at that
 * folder; "keep" leaves the downloads in Music/Drxwnify and doesn't ask again for this folder.
 */
@Composable
fun DownloadFolderAccessPrompt() {
    val context = LocalContext.current
    val downloadUtil = com.metrolist.music.LocalDownloadUtil.current
    val lost by com.metrolist.music.utils.DownloadExportState.folderAccessLost.collectAsState()
    val scope = rememberCoroutineScope()
    val folder by com.metrolist.music.utils.rememberPreference(com.metrolist.music.constants.DownloadFolderUriKey, "")
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { return@rememberLauncherForActivityResult }
        scope.launch {
            context.dataStore.edit { it[com.metrolist.music.constants.DownloadFolderUriKey] = uri.toString() }
            com.metrolist.music.utils.DownloadExportState.folderAccessLost.value = false
            downloadUtil.writeMissingFilesInBackground()
            val appContext = context.applicationContext
            downloadUtil.downloadExporter.moveAllToCurrentFolderInBackground(
                onStart = { total ->
                    android.widget.Toast.makeText(appContext, appContext.getString(R.string.moving_downloads, total), android.widget.Toast.LENGTH_SHORT).show()
                },
                onDone = { moved ->
                    android.widget.Toast.makeText(appContext, appContext.getString(R.string.moved_downloads, moved), android.widget.Toast.LENGTH_LONG).show()
                },
            )
        }
    }
    if (!lost || folder.isEmpty()) return
    val folderName = remember(folder) {
        runCatching {
            android.net.Uri.decode(android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(folder)))
                .substringAfter(':')
        }.getOrDefault("")
    }
    AlertDialog(
        onDismissRequest = { com.metrolist.music.utils.DownloadExportState.folderAccessLost.value = false },
        title = { Text(stringResource(R.string.download_folder_access_title)) },
        text = { Text(stringResource(R.string.download_folder_access_text, folderName)) },
        confirmButton = {
            TextButton(onClick = {
                com.metrolist.music.utils.DownloadExportState.folderAccessLost.value = false
                val initial = runCatching {
                    val tree = android.net.Uri.parse(folder)
                    android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        tree,
                        android.provider.DocumentsContract.getTreeDocumentId(tree),
                    )
                }.getOrNull()
                runCatching { pick.launch(initial) }
            }) { Text(stringResource(R.string.download_folder_access_pick)) }
        },
        dismissButton = {
            TextButton(onClick = {
                com.metrolist.music.utils.DownloadExportState.folderAccessLost.value = false
                downloadUtil.downloadExporter.declineFolder(folder)
            }) { Text(stringResource(R.string.download_folder_access_keep)) }
        },
    )
}

private const val LAUNCH_DELAY_MS = 6_000L
