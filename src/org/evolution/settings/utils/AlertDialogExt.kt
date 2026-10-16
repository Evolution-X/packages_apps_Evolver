/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.utils

import androidx.appcompat.app.AlertDialog
import com.android.settings.R

/**
 * The SettingsLib expressive dialog button backgrounds are plain shapes
 * without a state layer, so the buttons give no feedback when pressed.
 * Swap in the same shapes with a pill shaped ripple. Call after show().
 */
fun AlertDialog.applyExpressiveButtonFeedback() {
    getButton(AlertDialog.BUTTON_NEGATIVE)
        ?.setBackgroundResource(R.drawable.bg_dialog_button_outline)
    getButton(AlertDialog.BUTTON_NEUTRAL)
        ?.setBackgroundResource(R.drawable.bg_dialog_button_text)
}
