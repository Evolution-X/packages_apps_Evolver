/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
@file:JvmName("ListPreferencePicker")

package org.evolution.settings.preferences

import androidx.preference.ListPreference

/**
 * Opens [pref]'s entries in the Expressive picker instead of the stock list dialog. Closes
 * [previous] first and returns the new handle, or null when there is nothing to show. A tap runs
 * the same change-listener then setValue path as the stock dialog.
 */
fun openListPicker(pref: ListPreference, previous: ComposeDialogHandle?): ComposeDialogHandle? {
    previous?.dismiss()
    val entries = pref.entries ?: return null
    val values = pref.entryValues ?: return null
    if (entries.size != values.size) return null

    val selected = pref.findIndexOfValue(pref.value).coerceAtLeast(0)
    return pref.context.showPickerDialog(
        pref.dialogTitle ?: pref.title,
        entries.map { PickerOption(it.toString()) },
        selected,
    ) { position ->
        val value = values[position].toString()
        if (pref.callChangeListener(value)) pref.value = value
        true
    }
}
