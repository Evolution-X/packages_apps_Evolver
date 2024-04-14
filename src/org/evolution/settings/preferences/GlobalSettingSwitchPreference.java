/*
 * Copyright (C) 2016-2019 crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences;

import android.content.Context;
import android.provider.Settings;
import android.os.UserHandle;
import android.util.AttributeSet;

import lineageos.preference.SelfRemovingSwitchPreference;

public class GlobalSettingSwitchPreference extends SelfRemovingSwitchPreference {

    public GlobalSettingSwitchPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public GlobalSettingSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GlobalSettingSwitchPreference(Context context) {
        super(context);
    }

    @Override
    protected boolean isPersisted() {
        return Settings.Global.getString(getContext().getContentResolver(), getKey()) != null;
    }

    @Override
    protected void putBoolean(String key, boolean value) {
        Settings.Global.putInt(getContext().getContentResolver(), key, value ? 1 : 0);
    }

    @Override
    protected boolean getBoolean(String key, boolean defaultValue) {
        return Settings.Global.getInt(getContext().getContentResolver(),
                key, defaultValue ? 1 : 0) != 0;
    }
}
