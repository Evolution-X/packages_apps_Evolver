/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.widget.ui.Footer

/** Dialog shown over the TrickyStore screen. The fragment sets it, [TsDialogs] renders it. */
internal sealed interface TsDialog {
    object DeleteKeybox : TsDialog
    object UntrustedRoot : TsDialog
    object ChainInvalid : TsDialog
    class ExpiringSoon(val expiry: String) : TsDialog
    class PatchDate(val current: String) : TsDialog
    object WipePlayStore : TsDialog
}

/** Observable state the [TrickyStore] fragment pushes into the Compose screen. */
internal class TsUiState {
    var dialog by mutableStateOf<TsDialog?>(null)

    var enabled by mutableStateOf(true)
    var keyboxExists by mutableStateOf(false)
    var targetCount by mutableStateOf(0)

    var fetchEnabled by mutableStateOf(true)
    var fetchSummary by mutableStateOf("")
    var fetching by mutableStateOf(false)

    var revocationChip by mutableStateOf("")
    var revocationTone by mutableStateOf(ExpressiveTone.Neutral)
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

    /** The user confirmed deleting the installed keybox. */
    fun onConfirmDeleteKeybox()

    /** The user chose to replace the keybox with a freshly fetched official one. */
    fun onReplaceKeybox()

    /** Returns an error message to keep the patch dialog open, or null once the value is saved. */
    fun onSavePatch(value: String): String?

    fun onDeletePatch()

    /** The user answered the Play Store wipe prompt shown after importing a keybox. */
    fun onWipePlayStore(wipe: Boolean)
}

@Composable
internal fun TrickyStoreContent(state: TsUiState, actions: TsActions) {
    TsDialogs(state, actions)
    ExpressiveScreen {
        ExpressiveHeaderCard(
            modifier = Modifier.expressiveInset(),
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

        ExpressiveAnimatedVisibility(visible = state.enabled) {
            Column {
                Spacer(Modifier.height(SettingsSpace.extraSmall6))

                ExpressivePrimaryAction(
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
                            .expressiveInset()
                            .padding(
                                horizontal = SettingsSpace.small1,
                                vertical = SettingsSpace.extraSmall3,
                            ),
                    )
                }
                Spacer(Modifier.height(SettingsSpace.extraSmall2))
                ExpressiveActionRow(
                    listOf(
                        ExpressiveAction(
                            stringResource(R.string.action_import),
                            Icons.Rounded.FileOpen,
                            onClick = actions::onImportKeybox,
                        ),
                        ExpressiveAction(
                            stringResource(R.string.action_delete),
                            Icons.Rounded.DeleteOutline,
                            enabled = state.keyboxExists,
                            onClick = actions::onDeleteKeybox,
                        ),
                    ),
                )

                ExpressiveGroup(title = stringResource(R.string.ts_keybox_management)) {
                    item {
                        ExpressiveNavRow(
                            title = stringResource(R.string.ts_revocation_status),
                            summary = state.revocationSummary,
                            chip = state.revocationChip,
                            chipTone = state.revocationTone,
                            enabled = !state.revocationChecking,
                            onClick = actions::onCheckRevocation,
                        )
                    }
                }

                ExpressiveGroup(title = stringResource(R.string.ts_target_configuration)) {
                    item {
                        ExpressiveNavRow(
                            title = stringResource(R.string.ts_manage_target_apps),
                            summary = if (state.targetCount > 0)
                                stringResource(R.string.ts_target_apps_count, state.targetCount)
                            else stringResource(R.string.ts_no_targets),
                            onClick = actions::onManageTargets,
                        )
                    }
                    item {
                        ExpressiveNavRow(
                            title = stringResource(R.string.ts_import_target_list),
                            summary = stringResource(R.string.ts_import_target_list_summary),
                            onClick = actions::onImportTargets,
                        )
                    }
                    item {
                        ExpressiveNavRow(
                            title = stringResource(R.string.ts_verification_mode),
                            chip = state.verificationSummary,
                        )
                    }
                    item {
                        ExpressiveNavRow(
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

@Composable
private fun TsDialogs(state: TsUiState, actions: TsActions) {
    val dismiss = { state.dialog = null }
    when (val dialog = state.dialog) {
        null -> Unit
        TsDialog.DeleteKeybox -> ExpressiveConfirmDialog(
            title = stringResource(R.string.ts_delete_keybox_title),
            message = stringResource(R.string.ts_delete_keybox_message),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = actions::onConfirmDeleteKeybox,
            onDismiss = dismiss,
            destructive = true,
        )
        TsDialog.UntrustedRoot -> TsRevocationDialog(
            title = stringResource(R.string.ts_untrusted_root_title),
            message = stringResource(R.string.ts_untrusted_root_message),
            onKeep = dismiss,
            onReplace = { dismiss(); actions.onReplaceKeybox() },
        )
        TsDialog.ChainInvalid -> TsRevocationDialog(
            title = stringResource(R.string.ts_chain_invalid_title),
            message = stringResource(R.string.ts_chain_invalid_message),
            onKeep = dismiss,
            onReplace = { dismiss(); actions.onReplaceKeybox() },
        )
        is TsDialog.ExpiringSoon -> TsRevocationDialog(
            title = stringResource(R.string.ts_expiring_soon_title),
            message = stringResource(R.string.ts_expiring_soon_message, dialog.expiry),
            onKeep = dismiss,
            onReplace = { dismiss(); actions.onReplaceKeybox() },
        )
        is TsDialog.PatchDate -> TsPatchDateDialog(
            current = dialog.current,
            onSave = actions::onSavePatch,
            onDelete = actions::onDeletePatch,
            onDismiss = dismiss,
        )
        TsDialog.WipePlayStore -> {
            // Cancel and tap-outside both mean "don't wipe".
            val decline = { dismiss(); actions.onWipePlayStore(false) }
            ExpressiveDialog(
                onDismissRequest = decline,
                title = {
                    Text(
                        text = stringResource(R.string.pif_wipe_playstore_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
                text = {
                    Column(Modifier.expressiveVerticalScroll()) {
                        Text(
                            text = stringResource(R.string.ts_wipe_playstore_message),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                confirmButton = {
                    ExpressiveTextButton(onClick = { dismiss(); actions.onWipePlayStore(true) }) {
                        Text(stringResource(R.string.pif_wipe_confirm))
                    }
                },
                dismissButton = {
                    ExpressiveTextButton(onClick = decline) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
    }
}

/**
 * Keep-or-replace prompt for a keybox the revocation check flagged. Not cancelable: one of the two
 * answers has to be chosen, as with the AppCompat dialogs this replaces.
 */
@Composable
private fun TsRevocationDialog(
    title: String,
    message: String,
    onKeep: () -> Unit,
    onReplace: () -> Unit,
) {
    ExpressiveDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(Modifier.expressiveVerticalScroll()) {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            ExpressiveTextButton(onClick = onKeep) {
                Text(stringResource(R.string.ts_untrusted_root_keep))
            }
        },
        dismissButton = {
            ExpressiveTextButton(onClick = onReplace) {
                Text(stringResource(R.string.ts_untrusted_root_replace))
            }
        },
    )
}

@Composable
private fun TsPatchDateDialog(
    current: String,
    onSave: (String) -> String?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.ts_security_patch),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            ExpressiveTextField(
                value = text,
                onValueChange = { text = it; error = null },
                placeholder = stringResource(R.string.ts_patch_date_hint),
                singleLine = false,
                minLines = 3,
                maxLines = 10,
                isError = error != null,
                supportingText = error,
            )
        },
        confirmButton = {
            ExpressiveTextButton(onClick = {
                val result = onSave(text.trim())
                if (result == null) onDismiss() else error = result
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            Row {
                ExpressiveTextButton(
                    onClick = { onDelete(); onDismiss() },
                    enabled = current.isNotEmpty(),
                ) { Text(stringResource(R.string.action_delete)) }
                ExpressiveTextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}
