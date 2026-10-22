package com.metrolist.music.ui.screens.equalizer

import android.annotation.SuppressLint
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.session.PlaybackState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.eq.data.SavedEQProfile
import timber.log.Timber

/**
 * EQ Screen - Manage and select EQ profiles
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun EqScreen(
    viewModel: EQViewModel = hiltViewModel(),
    playbackState: PlaybackState? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current

    // Activity result launcher for system equalizer
    val activityResultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    EqScreenContent(
        profiles = state.profiles,
        activeProfileId = state.activeProfileId,
        graphic = {
            GraphicEqSection(
                gains = state.graphicGains,
                selectedPreset = state.graphicPreset.takeIf { state.activeProfileId == GraphicEq.PROFILE_ID },
                onPreset = viewModel::selectPreset,
                onBand = viewModel::setBand,
            )
        },
        onProfileSelected = { viewModel.selectProfile(it) },
        onEnable = viewModel::enable,
        onOpenSystemEqualizer = {
            playerConnection?.let { connection ->
                val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                    putExtra(
                        AudioEffect.EXTRA_AUDIO_SESSION,
                        connection.player.audioSessionId
                    )
                    putExtra(
                        AudioEffect.EXTRA_PACKAGE_NAME,
                        context.packageName
                    )
                    putExtra(
                        AudioEffect.EXTRA_CONTENT_TYPE,
                        AudioEffect.CONTENT_TYPE_MUSIC
                    )
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    activityResultLauncher.launch(intent)
                }
            }
        },
        onDeleteProfile = { viewModel.deleteProfile(it) }
    )

    // Error dialog for apply failure
    if (state.error != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = {
                Text(stringResource(R.string.error_title))
            },
            text = {
                Text(stringResource(R.string.error_eq_apply_failed, state.error ?: ""))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqScreenContent(
    profiles: List<SavedEQProfile>,
    activeProfileId: String?,
    graphic: @Composable () -> Unit,
    onProfileSelected: (String?) -> Unit,
    onEnable: () -> Unit,
    onOpenSystemEqualizer: () -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .heightIn(max = 720.dp)
            .padding(vertical = 24.dp) // Optional extra padding if desired, but dialog handles it.
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.equalizer_header),
                    style = MaterialTheme.typography.headlineSmall
                )
                Row {
                    IconButton(onClick = onOpenSystemEqualizer) {
                        Icon(
                            painter = painterResource(R.drawable.equalizer),
                            contentDescription = stringResource(R.string.system_equalizer)
                        )
                    }
                }
            }

            // Profile list
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                // On / off first: turning the equalizer on used to need a preset or a slider.
                item {
                    EqPowerRow(
                        enabled = activeProfileId != null,
                        onOff = { onProfileSelected(null) },
                        onOn = onEnable,
                    )
                }

                // The graphic equalizer with its presets: what most people come here for.
                item { graphic() }

                // Custom profiles only
                val customProfiles = profiles.filter { it.isCustom }

                if (customProfiles.isNotEmpty()) {
                    items(customProfiles) { profile ->
                        EQProfileItem(
                            profile = profile,
                            isSelected = activeProfileId == profile.id,
                            onSelected = { onProfileSelected(profile.id) },
                            onDelete = { onDeleteProfile(profile.id) }
                        )
                    }
                }
            }
        }
    }
}

// --- HELPER COMPOSABLES ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqPowerRow(
    enabled: Boolean,
    onOff: () -> Unit,
    onOn: () -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        SegmentedButton(
            selected = !enabled,
            onClick = onOff,
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            label = { Text(stringResource(R.string.eq_off)) },
        )
        SegmentedButton(
            selected = enabled,
            onClick = { if (!enabled) onOn() },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            label = { Text(stringResource(R.string.eq_on)) },
        )
    }
}

@Composable
private fun EQProfileItem(
    profile: SavedEQProfile,
    isSelected: Boolean,
    onSelected: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Text(
                text = profile.deviceModel,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        supportingContent = {
            Text(
                pluralStringResource(
                    id = R.plurals.band_count,
                    count = profile.bands.size,
                    profile.bands.size
                )
            )
        },
        leadingContent = {
            RadioButton(
                selected = isSelected,
                onClick = onSelected
            )
        },
        trailingContent = {
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = stringResource(R.string.delete_profile_desc),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        modifier = Modifier
            .clickable(onClick = onSelected)
            .padding(horizontal = 8.dp)
    )

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_profile_desc)) },
            text = {
                Text(
                    stringResource(R.string.delete_profile_confirmation, profile.name)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                , shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}