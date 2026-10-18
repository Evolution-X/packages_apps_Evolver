/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.widget.ui.Footer

/** Observable state the [TrickyStore] fragment pushes into the Compose screen. */
internal class TsUiState {
    var enabled by mutableStateOf(true)
    var keyboxExists by mutableStateOf(false)
    var targetCount by mutableStateOf(0)

    var fetchEnabled by mutableStateOf(true)
    var fetchSummary by mutableStateOf("")
    var fetching by mutableStateOf(false)

    var revocationChip by mutableStateOf("")
    var revocationTone by mutableStateOf(SpoofingTone.Neutral)
    var revocationSummary by mutableStateOf("")
    var revocationChecking by mutableStateOf(false)

    var verificationSummary by mutableStateOf("")
    var patchSummary by mutableStateOf("")
    var patchStale by mutableStateOf(false)
}

internal interface TsActions {
    fun onEnabledChange(enabled: Boolean)
    fun onFetchKeybox()
    fun onImportKeybox()
    fun onDeleteKeybox()
    fun onCheckRevocation()
    fun onManageTargets()
    fun onImportTargets()
    fun onEditPatch()
}

@Composable
internal fun TrickyStoreContent(state: TsUiState, actions: TsActions) {
    SpoofingScreen {
        SpoofingHeaderCard(
            modifier = Modifier.spoofingInset(),
            title = stringResource(R.string.tricky_store_title),
            subtitle = when {
                !state.enabled -> stringResource(R.string.app_spoofing_disabled)
                !state.keyboxExists -> stringResource(R.string.ts_no_keybox)
                else -> stringResource(R.string.ts_keybox_installed) + " \u00b7 " +
                    stringResource(R.string.ts_target_apps_count, state.targetCount)
            },
            checked = state.enabled,
            onCheckedChange = actions::onEnabledChange,
        ) {
            Icon(
                imageVector = Icons.Rounded.VpnKey,
                contentDescription = null,
                tint = if (state.enabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SpoofingAnimatedVisibility(visible = state.enabled) {
            Column {
                Spacer(Modifier.height(SettingsSpace.extraSmall6))

                SpoofingPrimaryAction(
                    text = stringResource(R.string.ts_fetch_keybox),
                    icon = Icons.Rounded.CloudDownload,
                    onClick = actions::onFetchKeybox,
                    enabled = state.fetchEnabled,
                    loading = state.fetching,
                )
                if (state.fetchSummary.isNotEmpty()) {
                    Text(
                        text = state.fetchSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .spoofingInset()
                            .padding(
                                horizontal = SettingsSpace.small1,
                                vertical = SettingsSpace.extraSmall3,
                            ),
                    )
                }
                Spacer(Modifier.height(SettingsSpace.extraSmall2))
                SpoofingActionRow(
                    listOf(
                        SpoofingAction(
                            stringResource(R.string.action_import),
                            Icons.Rounded.FileOpen,
                            onClick = actions::onImportKeybox,
                        ),
                        SpoofingAction(
                            stringResource(R.string.action_delete),
                            Icons.Rounded.DeleteOutline,
                            enabled = state.keyboxExists,
                            onClick = actions::onDeleteKeybox,
                        ),
                    ),
                )

                SpoofingGroup(title = stringResource(R.string.ts_keybox_management)) {
                    item {
                        SpoofingNavRow(
                            title = stringResource(R.string.ts_revocation_status),
                            summary = state.revocationSummary,
                            chip = state.revocationChip,
                            chipTone = state.revocationTone,
                            enabled = !state.revocationChecking,
                            onClick = actions::onCheckRevocation,
                        )
                    }
                }

                SpoofingGroup(title = stringResource(R.string.ts_target_configuration)) {
                    item {
                        SpoofingNavRow(
                            title = stringResource(R.string.ts_manage_target_apps),
                            summary = if (state.targetCount > 0)
                                stringResource(R.string.ts_target_apps_count, state.targetCount)
                            else stringResource(R.string.ts_no_targets),
                            onClick = actions::onManageTargets,
                        )
                    }
                    item {
                        SpoofingNavRow(
                            title = stringResource(R.string.ts_import_target_list),
                            summary = stringResource(R.string.ts_import_target_list_summary),
                            onClick = actions::onImportTargets,
                        )
                    }
                    item {
                        SpoofingNavRow(
                            title = stringResource(R.string.ts_verification_mode),
                            chip = state.verificationSummary,
                        )
                    }
                    item {
                        SpoofingNavRow(
                            title = stringResource(R.string.ts_security_patch),
                            summary = state.patchSummary,
                            onClick = actions::onEditPatch,
                        )
                    }
                }

                Footer(stringResource(R.string.ts_footer_body))
            }
        }
    }
}
