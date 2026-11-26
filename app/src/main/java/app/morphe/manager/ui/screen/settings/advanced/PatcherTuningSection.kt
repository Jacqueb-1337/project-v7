/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.advanced

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.SettingsViewModel

/**
 * Patcher tuning: process runtime, native library stripping and external batch patch triggers.
 */
@Composable
fun PatcherTuningSection(
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    onProcessRuntimePositioned: ((Rect) -> Unit)? = null
) {
    val prefs = settingsViewModel.prefs
    val useProcessRuntime by prefs.useProcessRuntime.getAsState()
    val memoryLimit by prefs.patcherProcessMemoryLimit.getAsState()
    val heapLimitIgnored by prefs.patcherHeapLimitIgnored.getAsState()
    val stripUnusedNativeLibs by prefs.stripUnusedNativeLibs.getAsState()
    val externalBatchPatchEnabled by prefs.externalBatchPatchEnabled.getAsState()

    val showExperimentalPv7Games by prefs.showExperimentalPv7Games.getAsState()
    val showProcessRuntimeDialog = remember { mutableStateOf(false) }

    if (showProcessRuntimeDialog.value) {
        ProcessRuntimeDialog(
            currentEnabled = useProcessRuntime,
            currentLimit = memoryLimit,
            heapLimitIgnored = heapLimitIgnored,
            onDismiss = { showProcessRuntimeDialog.value = false },
            onEnabledChange = { settingsViewModel.setProcessRuntime(it) },
            onLimitChange = { settingsViewModel.setMemoryLimit(it) }
        )
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Defaults.ContentPadding)
    ) {
        SectionTitle(
            text = stringResource(R.string.settings_advanced_patcher),
            icon = Icons.Outlined.Speed
        )

        SettingsGroup {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SettingsItem(
                    modifier = if (onProcessRuntimePositioned != null)
                        Modifier.onGloballyPositioned { coords -> onProcessRuntimePositioned(coords.boundsInWindow()) }
                    else Modifier,
                    onClick = { showProcessRuntimeDialog.value = true },
                    title = stringResource(R.string.settings_system_process_runtime),
                    subtitle = if (useProcessRuntime)
                        stringResource(
                            R.string.settings_system_process_runtime_enabled_description,
                            memoryLimit
                        )
                    else stringResource(R.string.settings_system_process_runtime_disabled_description),
                    leadingContent = { ThemedIcon(icon = Icons.Outlined.Memory) },
                    statusContent = {
                        StatusCircleIcon(
                            icon = Icons.Outlined.Check,
                            containerColor = if (useProcessRuntime) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (useProcessRuntime) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            } else {
                IconTextRow(
                    modifier = Modifier.padding(16.dp),
                    leadingContent = {
                        ThemedIcon(
                            icon = Icons.Outlined.Memory,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    },
                    title = stringResource(R.string.settings_system_process_runtime),
                    description = stringResource(R.string.settings_system_process_runtime_description_not_available)
                )
            }

            SettingsDivider()

            // Strip unused native libraries + filter split APKs for device
            SettingsSwitchItem(
                checked = stripUnusedNativeLibs,
                onToggle = {
                    settingsViewModel.setStripUnusedNativeLibs(!stripUnusedNativeLibs)
                },
                icon = Icons.Outlined.LayersClear,
                title = stringResource(R.string.settings_advanced_strip_unused_libs),
                subtitle = stringResource(R.string.settings_advanced_strip_unused_libs_description)
            )

            SettingsDivider()

            SettingsSwitchItem(
                checked = showExperimentalPv7Games,
                onToggle = { settingsViewModel.setShowExperimentalPv7Games(!showExperimentalPv7Games) },
                icon = Icons.Outlined.Speed,
                title = stringResource(R.string.pv7_experimental_games),
                subtitle = stringResource(R.string.pv7_experimental_games_description)
            )

            SettingsDivider()

            // Entry point other apps use to start a re-patch queue
            SettingsSwitchItem(
                checked = externalBatchPatchEnabled,
                onToggle = { settingsViewModel.toggleExternalBatchPatch(externalBatchPatchEnabled) },
                icon = Icons.Outlined.Api,
                title = stringResource(R.string.settings_advanced_external_batch_patch),
                subtitle = stringResource(R.string.settings_advanced_external_batch_patch_description)
            )
        }
    }
}
