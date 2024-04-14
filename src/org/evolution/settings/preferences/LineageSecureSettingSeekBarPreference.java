/*
 * Copyright (C) 2016-2022 crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences;

import android.content.Context;
import android.util.AttributeSet;

public class LineageSecureSettingSeekBarPreference extends CustomSeekBarPreference {

    public LineageSecureSettingSeekBarPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setPreferenceDataStore(new LineageSecureSettingsStore(context.getContentResolver()));
    }

    public LineageSecureSettingSeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPreferenceDataStore(new LineageSecureSettingsStore(context.getContentResolver()));
    }

    public LineageSecureSettingSeekBarPreference(Context context) {
        super(context, null);
        setPreferenceDataStore(new LineageSecureSettingsStore(context.getContentResolver()));
    }
}
