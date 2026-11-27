/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import android.security.trickystore.TrickyStoreService
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Save
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
    var autoTarget by mutableStateOf(true)

    var fetchEnabled by mutableStateOf(true)
    var fetchSummary by mutableStateOf("")
    var fetching by mutableStateOf(false)

    var revocationChip by mutableStateOf("")
    var revocationTone by mutableStateOf(ExpressiveTone.Neutral)
    var revocationSummary by mutableStateOf("")
    var revocationChecking by mutableStateOf(false)

    /** Offline description of the installed keybox, null without one or when unreadable. */
    var details by mutableStateOf<KeyboxDetails.Result?>(null)

    var verificationSummary by mutableStateOf("")
    var patchSummary by mutableStateOf("")
    var patchStale by mutableStateOf(false)

    var rkpSupported by mutableStateOf(false)
    var rkpAvailable by mutableStateOf(false)
    var rkpEnabled by mutableStateOf(false)
    var rkpImported by mutableStateOf(false)
    var rkpBusy by mutableStateOf(false)
    var rkpActive by mutableStateOf(false)
    var rkpChanging by mutableStateOf(false)
    var rkpCanProvision by mutableStateOf(false)
    var rkpStatus by mutableStateOf("")
}

internal interface TsActions {
    fun onEnabledChange(enabled: Boolean)
    fun onFetchKeybox()
    fun onImportKeybox()
    fun onExportKeybox()
    fun onDeleteKeybox()
    fun onCheckRevocation()
    fun onManageTargets()
    fun onAutoTargetChange(enabled: Boolean)
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

    fun onRkpEnabledChange(enabled: Boolean)
    fun onRkpProvision()
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
                state.rkpActive -> stringResource(R.string.ts_rkp_active_subtitle)
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

                if (!state.rkpEnabled) {
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
                }
                ExpressiveActionRow(
                    buildList {
                        add(
                            ExpressiveAction(
                                stringResource(R.string.action_import),
                                Icons.Rounded.FileOpen,
                                onClick = actions::onImportKeybox,
                            ),
                        )
                        add(
                            ExpressiveAction(
                                stringResource(R.string.action_export),
                                Icons.Rounded.Save,
                                enabled = state.keyboxExists || state.rkpActive,
                                onClick = actions::onExportKeybox,
                            ),
                        )
                        if (!state.rkpEnabled) {
                            add(
                                ExpressiveAction(
                                    stringResource(R.string.action_delete),
                                    Icons.Rounded.DeleteOutline,
                                    enabled = state.keyboxExists,
                                    onClick = actions::onDeleteKeybox,
                                ),
                            )
                        }
                    },
                )

                if (state.rkpSupported) {
                    ExpressiveGroup(title = stringResource(R.string.ts_rkp_title)) {
                        item {
                            ExpressiveSwitchRow(
                                titleText = stringResource(R.string.ts_rkp_enabled),
                                summaryText = stringResource(R.string.ts_rkp_enabled_summary),
                                isChecked = state.rkpEnabled,
                                onChange = actions::onRkpEnabledChange,
                                isEnabled = state.rkpAvailable && !state.rkpImported &&
                                    !state.rkpBusy && !state.rkpChanging,
                            )
                        }
                        item {
                            ExpressiveNavRow(
                                title = stringResource(R.string.ts_rkp_provision),
                                summary = stringResource(R.string.ts_rkp_provision_summary),
                                enabled = state.rkpCanProvision && !state.rkpChanging,
                                onClick = actions::onRkpProvision,
                            )
                        }
                        item {
                            ExpressiveNavRow(
                                title = stringResource(R.string.ts_rkp_status),
                                summary = state.rkpStatus,
                            )
                        }
                    }
                }

                if (!state.rkpEnabled) {
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
                }

                ExpressiveAnimatedVisibility(visible = state.details != null) {
                    state.details?.let { TsKeyboxDetails(it) }
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
                        ExpressiveSwitchRow(
                            titleText = stringResource(R.string.ts_auto_target),
                            summaryText = stringResource(R.string.ts_auto_target_summary),
                            isChecked = state.autoTarget,
                            onChange = actions::onAutoTargetChange,
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
private fun TsKeyboxDetails(details: KeyboxDetails.Result) {
    ExpressiveGroup(
        title = stringResource(R.string.ts_kb_details_title),
        collapsible = true,
    ) {
        item {
            ExpressiveValueRow(
                label = stringResource(R.string.ts_kb_manufacturer),
                value = details.manufacturer ?: stringResource(R.string.ts_kb_manufacturer_unknown),
                note = when (details.manufacturerSource) {
                    KeyboxDetails.ManufacturerSourceKind.RKP ->
                        stringResource(R.string.ts_kb_source_rkp)
                    KeyboxDetails.ManufacturerSourceKind.SUBJECT ->
                        details.issuerOrganisation
                            ?.let { stringResource(R.string.ts_kb_source_subject_org, it) }
                            ?: stringResource(R.string.ts_kb_source_subject)
                    KeyboxDetails.ManufacturerSourceKind.NONE ->
                        details.issuerOrganisation
                            ?.let { stringResource(R.string.ts_kb_source_org_only, it) }
                            ?: stringResource(R.string.ts_kb_source_none)
                },
            )
        }
        details.teeVendor?.let { vendor ->
            item {
                ExpressiveValueRow(label = stringResource(R.string.ts_kb_tee_vendor), value = vendor)
            }
        }
        item {
            val software = KeyboxDetails.SecurityLevel.SOFTWARE in details.securityLevels
            ExpressiveValueRow(
                label = stringResource(R.string.ts_kb_security_level),
                value = details.securityLevels
                    .map { stringResource(securityLevelLabel(it)) }
                    .joinToString(" \u00b7 "),
                note = stringResource(
                    if (software) R.string.ts_kb_level_software_note else R.string.ts_kb_level_note),
            )
        }
        item {
            ExpressiveValueRow(
                label = stringResource(R.string.ts_kb_root),
                value = details.rootLabels.joinToString(" \u00b7 ")
                    .ifEmpty { stringResource(R.string.ts_kb_root_unknown) },
            )
        }
        details.keys.forEach { key ->
            val rkp = key.rkp ?: return@forEach
            item {
                ExpressiveValueRow(
                    label = stringResource(R.string.ts_kb_rkp_title, key.algorithm),
                    value = listOfNotNull(rkp.manufacturer, rkp.validatedEntity)
                        .joinToString(" \u00b7 ")
                        .ifEmpty { stringResource(R.string.ts_kb_manufacturer_unknown) },
                    note = listOfNotNull(
                        rkp.certsIssued?.let { stringResource(R.string.ts_kb_rkp_certs, it) },
                        rkp.issue?.let { stringResource(R.string.ts_kb_rkp_issue, it) },
                    ).joinToString("\n").ifEmpty { null },
                )
            }
        }
        details.keys.forEach { key ->
            key.certs.forEachIndexed { index, cert ->
                item {
                    val role = when {
                        index == 0 -> R.string.ts_kb_cert_attest_key
                        cert.isRoot -> R.string.ts_kb_cert_root
                        else -> R.string.ts_kb_cert_intermediate
                    }
                    ExpressiveValueRow(
                        label = stringResource(role, key.algorithm, cert.publicKey),
                        value = cert.subject,
                        note = stringResource(
                            R.string.ts_kb_cert_note,
                            cert.serialHex,
                            KeyboxDetails.formatDate(cert.notBefore),
                            KeyboxDetails.formatDate(cert.notAfter),
                        ),
                    )
                }
            }
        }
    }
}

private fun securityLevelLabel(level: KeyboxDetails.SecurityLevel): Int = when (level) {
    KeyboxDetails.SecurityLevel.STRONGBOX -> R.string.ts_kb_level_strongbox
    KeyboxDetails.SecurityLevel.TEE -> R.string.ts_kb_level_tee
    KeyboxDetails.SecurityLevel.SOFTWARE -> R.string.ts_kb_level_software
    KeyboxDetails.SecurityLevel.UNKNOWN -> R.string.ts_kb_level_unknown
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
    // What a single template value (today, YYYY-MM-05, ...) resolves to right now. Blocks
    // with several values are left alone, and so is a plain date, which resolves to itself.
    val resolved = remember(text) {
        val v = text.trim()
        if (v.isEmpty() || v.contains('\n') || v.contains('=')) null
        else TrickyStoreService.resolvePatchTemplate(v).takeIf { it != v }
    }
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
                supportingText = error
                    ?: resolved?.let { stringResource(R.string.ts_patch_resolves_to, it) },
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
