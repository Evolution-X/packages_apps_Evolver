/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.widget.dialog.getDialogWidth
import com.android.settingslib.spa.widget.ui.Footer

/** Dialog shown over the Play Integrity Fix screen. The fragment sets it, [PifDialogs] renders it. */
internal sealed interface PifDialog {
    object ResetAdvanced : PifDialog
    class Delete(val configName: String) : PifDialog

    /** Pixel profile picker; [names] are display labels and [selected] is the preselected row. */
    class DevicePicker(val names: List<String>, val selected: Int) : PifDialog

    /** Offer to clear Play Store data after a new fingerprint was applied. */
    object WipePlayStore : PifDialog
}

/** Observable state the [PlayIntegrityFix] fragment pushes into the Compose screen. */
internal class PifUiState {
    var dialog by mutableStateOf<PifDialog?>(null)

    var enabled by mutableStateOf(true)
    var config by mutableStateOf<Map<String, String>>(emptyMap())
    var fetching by mutableStateOf(false)

    /** Days until the Canary fingerprint expires, null when unknown or not a Canary build. */
    var daysLeft by mutableStateOf<Long?>(null)

    /** Security-patch age in days, null when unknown. */
    var patchAgeDays by mutableStateOf<Long?>(null)
}

internal interface PifActions {
    fun onEnabledChange(enabled: Boolean)
    fun onFetch()
    fun onImport()
    fun onDelete()
    fun onResetAdvanced()
    fun onFlagChange(name: String, value: Boolean)
    fun onVendingFingerChange(value: String)
    fun onVendingSdkChange(value: Boolean)

    fun onConfirmResetAdvanced()
    fun onConfirmDelete()

    /** The user picked row [index] of the [PifDialog.DevicePicker]. */
    fun onPickDevice(index: Int)

    /** [wipe] is true when the user agreed to clear Play Store data, false for cancel or dismiss. */
    fun onWipePlayStore(wipe: Boolean)

    /** Returns an error message to keep the edit dialog open, or null once the value is saved. */
    fun onEditValue(key: String, value: String): String?
}

private val FLAG_ROWS = listOf(
    Triple("spoofBuild", R.string.pif_spoof_build_title, R.string.pif_spoof_build_summary),
    Triple("spoofProps", R.string.pif_spoof_props_title, R.string.pif_spoof_props_summary),
    Triple("spoofProvider", R.string.pif_spoof_provider_title, R.string.pif_spoof_provider_summary),
    Triple("spoofSignature", R.string.pif_spoof_signature_title, R.string.pif_spoof_signature_summary),
)

private val FLAG_DEFAULTS = mapOf(
    "spoofBuild" to true,
    "spoofProps" to true,
    "spoofProvider" to false,
    "spoofSignature" to false,
)

private val DISPLAY_ORDER = listOf(
    "MODEL", "MANUFACTURER", "BRAND", "PRODUCT", "DEVICE",
    "FINGERPRINT", "SECURITY_PATCH", "ID", "RELEASE", "DEVICE_INITIAL_SDK_INT",
)

private fun flagOn(value: String?, default: Boolean) =
    if (value.isNullOrEmpty()) default else value == "1" || value.equals("true", true)

private fun vendingSdkOn(value: String?) =
    value.equals("true", true) || (value?.toIntOrNull() ?: 0) > 0

@Composable
internal fun PlayIntegrityFixContent(state: PifUiState, actions: PifActions) {
    val config = state.config
    val hasConfig = config.isNotEmpty()
    val controlsEnabled = state.enabled && hasConfig
    var editKey by rememberSaveable { mutableStateOf<String?>(null) }

    ExpressiveScreen {
        ExpressiveHeaderCard(
            modifier = Modifier.expressiveInset(),
            title = stringResource(R.string.pif_fingerprint_spoofing),
            subtitle = config["MODEL"]?.takeIf { it.isNotEmpty() && state.enabled }
                ?.let { stringResource(R.string.pif_active_model, it) }
                ?: if (state.enabled) stringResource(R.string.pif_no_config)
                else stringResource(R.string.app_spoofing_disabled),
            checked = state.enabled,
            onCheckedChange = actions::onEnabledChange,
        ) {
            Icon(
                imageVector = Icons.Rounded.Fingerprint,
                contentDescription = null,
                tint = if (state.enabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ExpressiveAnimatedVisibility(visible = state.enabled) {
            Column {
                Spacer(Modifier.height(SettingsSpace.extraSmall6))

                ExpressivePrimaryAction(
                    text = stringResource(R.string.pif_fetch_pixel_beta),
                    icon = Icons.Rounded.CloudDownload,
                    onClick = actions::onFetch,
                    loading = state.fetching,
                )
                Spacer(Modifier.height(SettingsSpace.extraSmall4))
                ExpressiveActionRow(
                    listOf(
                        ExpressiveAction(
                            stringResource(R.string.action_import),
                            Icons.Rounded.FileOpen,
                            onClick = actions::onImport,
                        ),
                        ExpressiveAction(
                            stringResource(R.string.action_delete),
                            Icons.Rounded.DeleteOutline,
                            enabled = hasConfig,
                            onClick = actions::onDelete,
                        ),
                        ExpressiveAction(
                            stringResource(R.string.action_reset),
                            Icons.Rounded.History,
                            enabled = hasConfig,
                            onClick = actions::onResetAdvanced,
                        ),
                    ),
                )

                ExpressiveGroup(
                    title = stringResource(R.string.pif_active_config),
                    collapsible = true,
                ) {
                    if (!hasConfig) {
                        item { ExpressiveRow(title = stringResource(R.string.pif_no_config)) }
                    } else {
                        val days = state.daysLeft
                        val patch = config["SECURITY_PATCH"].orEmpty()
                        if (days != null) {
                            item {
                                ExpressiveNavRow(
                                    title = stringResource(R.string.pif_canary_fingerprint),
                                    summary = state.patchAgeDays?.let {
                                        stringResource(R.string.pif_patch_age, patch, it)
                                    } ?: patch,
                                    chip = if (days < 0) stringResource(R.string.pif_expired)
                                    else stringResource(R.string.pif_days_left, days),
                                    chipTone = when {
                                        days < 0 -> ExpressiveTone.Negative
                                        days <= 7 -> ExpressiveTone.Warning
                                        else -> ExpressiveTone.Positive
                                    },
                                )
                            }
                        }
                        val extras = config.keys.filter {
                            it !in DISPLAY_ORDER && !it.startsWith("spoof") &&
                                !it.startsWith("_") && it != "DEBUG" &&
                                it != "verboseLogs" && it != "manually_imported"
                        }
                        (DISPLAY_ORDER + extras).forEach { key ->
                            val value = config[key] ?: return@forEach
                            item {
                                ExpressiveValueRow(
                                    label = key,
                                    value = value,
                                    onClick = { editKey = key },
                                )
                            }
                        }
                    }
                }

                ExpressiveGroup(title = stringResource(R.string.pif_advanced_settings)) {
                    val finger = config["spoofVendingFinger"].orEmpty()
                    val customFinger = finger.isNotEmpty() &&
                        finger !in listOf("0", "1", "true", "false")
                    if (customFinger) {
                        item {
                            ExpressiveNavRow(
                                title = stringResource(R.string.pif_spoof_vending_finger_title),
                                summary = stringResource(
                                    R.string.pif_vending_finger_custom_summary, finger) + "\n" +
                                    stringResource(R.string.pif_vending_finger_custom_clear_hint),
                                enabled = controlsEnabled,
                                onClick = { actions.onVendingFingerChange("0") },
                            )
                        }
                    } else {
                        item {
                            ExpressiveSwitchRow(
                                titleText = stringResource(R.string.pif_spoof_vending_finger_title),
                                summaryText = stringResource(R.string.pif_vending_finger_same),
                                isChecked = finger == "1" || finger.equals("true", true),
                                isEnabled = controlsEnabled,
                                onChange = { actions.onVendingFingerChange(if (it) "1" else "0") },
                            )
                        }
                    }
                    item {
                        ExpressiveSwitchRow(
                            titleText = stringResource(R.string.pif_spoof_vending_sdk_title),
                            summaryText = stringResource(R.string.pif_spoof_vending_sdk_summary),
                            isChecked = vendingSdkOn(config["spoofVendingSdk"]),
                            isEnabled = controlsEnabled,
                            onChange = actions::onVendingSdkChange,
                        )
                    }
                    FLAG_ROWS.forEach { (name, title, summary) ->
                        item {
                            ExpressiveSwitchRow(
                                titleText = stringResource(title),
                                summaryText = stringResource(summary),
                                isChecked = flagOn(config[name], FLAG_DEFAULTS.getValue(name)),
                                isEnabled = controlsEnabled,
                                onChange = { actions.onFlagChange(name, it) },
                            )
                        }
                    }
                }

                Footer(stringResource(R.string.pif_footer_body))
            }
        }
    }

    PifDialogs(state, actions)

    editKey?.let { key ->
        PifEditDialog(
            key = key,
            initial = config[key].orEmpty(),
            onSave = { actions.onEditValue(key, it) },
            onDismiss = { editKey = null },
        )
    }
}

@Composable
private fun PifEditDialog(
    key: String,
    initial: String,
    onSave: (String) -> String?,
    onDismiss: () -> Unit,
) {
    var text by remember(key) { mutableStateOf(initial) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = { Text(key, style = MaterialTheme.typography.headlineSmall) },
        text = {
            ExpressiveTextField(
                value = text,
                onValueChange = { text = it; error = null },
                isError = error != null,
                supportingText = error,
                singleLine = false,
                minLines = if (key == "FINGERPRINT") 3 else 1,
            )
        },
        confirmButton = {
            ExpressiveTextButton(onClick = {
                val result = onSave(text.trim())
                if (result == null) onDismiss() else error = result
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            ExpressiveTextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun PifDialogs(state: PifUiState, actions: PifActions) {
    val dismiss = { state.dialog = null }
    when (val dialog = state.dialog) {
        null -> Unit
        PifDialog.ResetAdvanced -> ExpressiveConfirmDialog(
            title = stringResource(R.string.pif_reset_advanced_title),
            message = stringResource(R.string.pif_reset_advanced_message),
            confirmText = stringResource(android.R.string.ok),
            onConfirm = actions::onConfirmResetAdvanced,
            onDismiss = dismiss,
        )
        is PifDialog.Delete -> ExpressiveConfirmDialog(
            title = stringResource(R.string.pif_delete_title, dialog.configName),
            message = stringResource(R.string.pif_delete_message),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = actions::onConfirmDelete,
            onDismiss = dismiss,
            destructive = true,
        )
        is PifDialog.DevicePicker -> ExpressiveDialog(
            onDismissRequest = dismiss,
            title = {
                Text(
                    text = stringResource(R.string.pif_select_device),
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .expressiveVerticalScroll(),
                ) {
                    ExpressiveGroup(title = "", horizontalInset = 0.dp) {
                        dialog.names.forEachIndexed { index, name ->
                            item {
                                ExpressiveChoiceRow(
                                    title = name,
                                    selected = index == dialog.selected,
                                    onClick = { dismiss(); actions.onPickDevice(index) },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                ExpressiveTextButton(onClick = dismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
        PifDialog.WipePlayStore -> {
            // Cancel and tap-outside both mean "don't wipe", so the pending follow-up
            // (toast + refresh) always runs exactly once.
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
                            text = stringResource(R.string.pif_wipe_playstore_message),
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
