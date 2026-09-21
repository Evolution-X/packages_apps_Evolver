/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

@file:JvmName("ExpressiveDialogs")

package org.evolution.settings.preferences

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.evolution.settings.fragments.miscellaneous.ExpressiveDialog
import org.evolution.settings.fragments.miscellaneous.ExpressiveTextButton
import org.evolution.settings.fragments.miscellaneous.ExpressiveTextField
import org.evolution.settings.fragments.miscellaneous.expressiveVerticalScroll

/** Java-friendly callback for [showTextInputDialog]. */
fun interface OnTextConfirmed {
    fun onConfirmed(text: String)
}

/** Java-friendly callback for [showConfirmDialog]. */
fun interface OnConfirmed {
    fun onConfirmed()
}

/**
 * Message dialog with a confirm button and, unless [dismissText] is null, a dismiss button.
 * With [cancelable] false the dialog ignores back and outside taps, like `setCancelable(false)`.
 * Returns null if [Context] isn't backed by an activity.
 */
@JvmOverloads
fun showConfirmDialog(
    context: Context,
    title: CharSequence,
    message: CharSequence,
    confirmText: CharSequence,
    dismissText: CharSequence? = null,
    cancelable: Boolean = true,
    onConfirm: OnConfirmed? = null,
): ComposeDialogHandle? = context.showComposeDialog { dismiss ->
    ExpressiveDialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            dismissOnBackPress = cancelable,
            dismissOnClickOutside = cancelable,
            usePlatformDefaultWidth = false,
        ),
        title = { Text(text = title.toString(), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(Modifier.expressiveVerticalScroll()) {
                Text(text = message.toString(), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            ExpressiveTextButton(onClick = { dismiss(); onConfirm?.onConfirmed() }) {
                Text(confirmText.toString())
            }
        },
        dismissButton = dismissText?.let { label ->
            { ExpressiveTextButton(onClick = dismiss) { Text(label.toString()) } }
        },
    )
}

/**
 * Single-line text input dialog. [onConfirm] gets the entered text; when [allowEmpty] is false the
 * confirm button is disabled while the field is blank. With [singleLine] false the field grows
 * between [minLines] and [maxLines]. Returns null if [Context] isn't backed by an activity.
 */
@JvmOverloads
fun showTextInputDialog(
    context: Context,
    title: CharSequence,
    message: CharSequence?,
    initialText: String?,
    confirmText: CharSequence,
    dismissText: CharSequence,
    allowEmpty: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    placeholder: String? = null,
    onConfirm: OnTextConfirmed,
): ComposeDialogHandle? = context.showComposeDialog { dismiss ->
    var text by remember { mutableStateOf(initialText.orEmpty()) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ExpressiveDialog(
        onDismissRequest = dismiss,
        title = { Text(text = title.toString(), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(Modifier.expressiveVerticalScroll()) {
                if (!message.isNullOrEmpty()) {
                    Text(text = message.toString(), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                }
                ExpressiveTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    singleLine = singleLine,
                    minLines = minLines,
                    maxLines = maxLines,
                    placeholder = placeholder,
                )
            }
        },
        confirmButton = {
            ExpressiveTextButton(
                onClick = { dismiss(); onConfirm.onConfirmed(text) },
                enabled = allowEmpty || text.isNotBlank(),
            ) { Text(confirmText.toString()) }
        },
        dismissButton = {
            ExpressiveTextButton(onClick = dismiss) { Text(dismissText.toString()) }
        },
    )
}
