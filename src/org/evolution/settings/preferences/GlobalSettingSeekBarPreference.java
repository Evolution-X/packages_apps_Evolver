/*
 * Copyright (C) 2016-2019 crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences;

import android.content.Context;
import android.util.AttributeSet;

public class GlobalSettingSeekBarPreference extends CustomSeekBarPreference {

    public GlobalSettingSeekBarPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setPreferenceDataStore(new GlobalSettingsStore(context.getContentResolver()));
    }

    public GlobalSettingSeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPreferenceDataStore(new GlobalSettingsStore(context.getContentResolver()));
    }

    public GlobalSettingSeekBarPreference(Context context) {
        super(context, null);
        setPreferenceDataStore(new GlobalSettingsStore(context.getContentResolver()));
    }
}
