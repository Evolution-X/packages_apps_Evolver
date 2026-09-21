/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences;

import android.content.Context;
import android.util.AttributeSet;

import lineageos.preference.LineageSecureSettingListPreference;

/**
 * A {@link LineageSecureSettingListPreference} that opens its entries in the Expressive picker
 * instead of the stock list dialog.
 */
public class LineageSecureSettingPickerListPreference extends LineageSecureSettingListPreference {
    private ComposeDialogHandle mPickerDialog;

    public LineageSecureSettingPickerListPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public LineageSecureSettingPickerListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onClick() {
        mPickerDialog = ListPreferencePicker.openListPicker(this, mPickerDialog);
    }

    @Override
    public void onDetached() {
        if (mPickerDialog != null) {
            mPickerDialog.dismiss();
            mPickerDialog = null;
        }
        super.onDetached();
    }
}
