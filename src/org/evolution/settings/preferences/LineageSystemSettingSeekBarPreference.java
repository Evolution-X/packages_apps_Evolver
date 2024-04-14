/*
 * Copyright (C) 2016-2019 crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences;

import android.content.Context;
import android.util.AttributeSet;

public class LineageSystemSettingSeekBarPreference extends CustomSeekBarPreference {

    public LineageSystemSettingSeekBarPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setPreferenceDataStore(new LineageSystemSettingsStore(context.getContentResolver()));
    }

    public LineageSystemSettingSeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPreferenceDataStore(new LineageSystemSettingsStore(context.getContentResolver()));
    }

    public LineageSystemSettingSeekBarPreference(Context context) {
        super(context, null);
        setPreferenceDataStore(new LineageSystemSettingsStore(context.getContentResolver()));
    }
}
