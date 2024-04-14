/*
 * Copyright (C) 2020 crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences.colorpicker;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.provider.Settings;

import org.evolution.settings.preferences.SecureSettingsStore;

public class SecureSettingColorPickerPreference extends ColorPickerPreference {

    public SecureSettingColorPickerPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setPreferenceDataStore(new SecureSettingsStore(context.getContentResolver()));
    }

    public SecureSettingColorPickerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPreferenceDataStore(new SecureSettingsStore(context.getContentResolver()));
    }

    public SecureSettingColorPickerPreference(Context context) {
        super(context, null);
        setPreferenceDataStore(new SecureSettingsStore(context.getContentResolver()));
    }
}
