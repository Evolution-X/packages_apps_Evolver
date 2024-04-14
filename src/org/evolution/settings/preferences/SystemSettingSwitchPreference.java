/*
 * Copyright (C) 2016-2018 crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences;

import android.content.Context;
import android.provider.Settings;
import android.os.UserHandle;
import android.util.AttributeSet;

import lineageos.preference.SelfRemovingSwitchPreference;

public class SystemSettingSwitchPreference extends SelfRemovingSwitchPreference {

    public SystemSettingSwitchPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public SystemSettingSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SystemSettingSwitchPreference(Context context) {
        super(context);
    }

    @Override
    protected boolean isPersisted() {
        return Settings.System.getString(getContext().getContentResolver(), getKey()) != null;
    }

    @Override
    protected void putBoolean(String key, boolean value) {
        Settings.System.putIntForUser(getContext().getContentResolver(), key, value ? 1 : 0, UserHandle.USER_CURRENT);
    }

    @Override
    protected boolean getBoolean(String key, boolean defaultValue) {
        return Settings.System.getIntForUser(getContext().getContentResolver(),
                key, defaultValue ? 1 : 0, UserHandle.USER_CURRENT) != 0;
    }
}
